package joiner

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/pion/webrtc/v4"
	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/tunnel"
	"whitelist-bypass/relay/wbstream"
)

const (
	reconnectInitialDelay = time.Second
	reconnectMaxDelay     = 16 * time.Second
)

type WBStreamHeadlessJoiner struct {
	logFn       func(string, ...any)
	OnConnected func(tunnel.DataTunnel)
	ResolveFn   ResolveFunc
	Status      StatusEmitter
	PCConfig    PeerConnectionConfigurer

	mu       sync.Mutex
	session  *wbstream.Session
	closed   bool
	stopCh   chan struct{}
	stopOnce sync.Once
}

func NewWBStreamHeadlessJoiner(logFn func(string, ...any), resolveFn ResolveFunc, status StatusEmitter, pcConfig PeerConnectionConfigurer) *WBStreamHeadlessJoiner {
	return &WBStreamHeadlessJoiner{
		logFn:     logFn,
		ResolveFn: resolveFn,
		Status:    status,
		PCConfig:  pcConfig,
		stopCh:    make(chan struct{}),
	}
}

func (j *WBStreamHeadlessJoiner) RunWithParams(jsonParams string) {
	var params struct {
		RoomID      string `json:"roomId"`
		DisplayName string `json:"displayName"`
		TunnelMode  string `json:"tunnelMode"`
		VP8FPS      int    `json:"vp8Fps"`
		VP8Batch    int    `json:"vp8Batch"`
	}
	if err := json.Unmarshal([]byte(jsonParams), &params); err != nil {
		j.logFn("wbstream-joiner: failed to parse params: %v", err)
		j.Status.EmitStatusError("bad params: " + err.Error())
		return
	}
	if params.RoomID == "" {
		j.logFn("wbstream-joiner: missing roomId")
		j.Status.EmitStatusError("missing roomId")
		return
	}
	if params.DisplayName == "" {
		params.DisplayName = "Joiner"
	}

	httpClient := j.makeHTTPClient()
	j.logFn("wbstream-joiner: room=%s name=%s vp8Fps=%d vp8Batch=%d", params.RoomID, params.DisplayName, params.VP8FPS, params.VP8Batch)

	obf, err := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(params.RoomID))
	if err != nil {
		j.logFn("wbstream-joiner: obfuscator init failed: %v", err)
		j.Status.EmitStatusError("obfuscator init: " + err.Error())
		return
	}
	j.logFn("wbstream-joiner: obf key-source=%q localEpoch=0x%08x", params.RoomID, obf.LocalEpoch())

	var settingEngine *webrtc.SettingEngine
	if j.PCConfig != nil {
		se := webrtc.SettingEngine{}
		j.PCConfig.ConfigureSettingEngine(&se)
		settingEngine = &se
	}

	attempt := 0
	for {
		if j.isClosed() {
			return
		}
		if attempt == 0 {
			j.Status.EmitStatus(common.StatusConnecting)
		} else {
			j.logFn("wbstream-joiner: reconnect attempt #%d", attempt)
			j.Status.EmitStatus(common.StatusReconnecting)
		}

		_, roomToken, _, serverURL, authErr := wbstream.AuthAndGetToken(httpClient, params.RoomID, params.DisplayName)
		if authErr != nil {
			j.logFn("wbstream-joiner: auth failed: %v", authErr)
			if attempt == 0 {
				j.Status.EmitStatusError("auth: " + authErr.Error())
				return
			}
			if !j.waitBeforeRetry(attempt) {
				return
			}
			attempt++
			continue
		}
		j.logFn("wbstream-joiner: server=%s", serverURL)

		sess := wbstream.NewSession(wbstream.SessionConfig{
			RoomToken:      roomToken,
			ServerURL:      serverURL,
			DisplayName:    params.DisplayName,
			TunnelMode:     params.TunnelMode,
			Obfuscator:     obf,
			LogFn:          j.logFn,
			SettingEngine:  settingEngine,
			NetDialContext: j.makeDialContext(),
			ResolveICEHost: j.ResolveFn,
			VP8FPS:         params.VP8FPS,
			VP8Batch:       params.VP8Batch,
		})
		sess.OnConnected = func(tun tunnel.DataTunnel) {
			j.logFn("wbstream-joiner: === TUNNEL CONNECTED ===")
			j.Status.EmitStatus(common.StatusTunnelConnected)
			if j.OnConnected != nil {
				j.OnConnected(tun)
			}
		}

		j.mu.Lock()
		if j.closed {
			j.mu.Unlock()
			sess.Close()
			return
		}
		j.session = sess
		j.mu.Unlock()

		startErr := sess.Start()
		if startErr != nil {
			j.logFn("wbstream-joiner: session start failed: %v", startErr)
			j.clearSession(sess)
			if attempt == 0 {
				j.Status.EmitStatusError("session: " + startErr.Error())
				return
			}
			if !j.waitBeforeRetry(attempt) {
				return
			}
			attempt++
			continue
		}

		<-sess.Done()
		sess.Close()
		j.clearSession(sess)
		if j.isClosed() {
			j.logFn("wbstream-joiner: stopped")
			return
		}
		j.logFn("wbstream-joiner: session ended, reconnecting")
		j.Status.EmitStatus(common.StatusTunnelLost)
		if !j.waitBeforeRetry(attempt) {
			return
		}
		attempt++
	}
}

func (j *WBStreamHeadlessJoiner) waitBeforeRetry(attempt int) bool {
	delay := reconnectInitialDelay << attempt
	if delay > reconnectMaxDelay || delay <= 0 {
		delay = reconnectMaxDelay
	}
	j.logFn("wbstream-joiner: waiting %s before reconnect", delay)
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-timer.C:
		return !j.isClosed()
	case <-j.stopCh:
		return false
	}
}

func (j *WBStreamHeadlessJoiner) clearSession(sess *wbstream.Session) {
	j.mu.Lock()
	if j.session == sess {
		j.session = nil
	}
	j.mu.Unlock()
}

func (j *WBStreamHeadlessJoiner) isClosed() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.closed
}

func (j *WBStreamHeadlessJoiner) Close() {
	j.stopOnce.Do(func() { close(j.stopCh) })
	j.mu.Lock()
	j.closed = true
	sess := j.session
	j.session = nil
	j.mu.Unlock()
	if sess != nil {
		sess.Close()
	}
}

func (j *WBStreamHeadlessJoiner) makeDialContext() func(ctx context.Context, network, addr string) (net.Conn, error) {
	if j.ResolveFn == nil {
		return nil
	}
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, _ := net.SplitHostPort(addr)
		resolvedIP, err := j.ResolveFn(host)
		if err != nil {
			return nil, err
		}
		return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, resolvedIP+":"+port)
	}
}

func (j *WBStreamHeadlessJoiner) makeHTTPClient() *http.Client {
	transport := &http.Transport{DialContext: j.makeDialContext()}
	return &http.Client{Timeout: 60 * time.Second, Transport: transport}
}
