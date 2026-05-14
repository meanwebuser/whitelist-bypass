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
	"whitelist-bypass/relay/dion"
	"whitelist-bypass/relay/tunnel"
)

type DionHeadlessJoiner struct {
	logFn       func(string, ...any)
	OnConnected func(tunnel.DataTunnel)
	ResolveFn   ResolveFunc
	Status      StatusEmitter
	PCConfig    PeerConnectionConfigurer

	mu     sync.Mutex
	call   *dion.Call
	closed bool
}

func NewDionHeadlessJoiner(logFn func(string, ...any), resolveFn ResolveFunc, status StatusEmitter, pcConfig PeerConnectionConfigurer) *DionHeadlessJoiner {
	return &DionHeadlessJoiner{
		logFn:     logFn,
		ResolveFn: resolveFn,
		Status:    status,
		PCConfig:  pcConfig,
	}
}

func (j *DionHeadlessJoiner) RunWithParams(jsonParams string) {
	var params struct {
		RoomID      string `json:"roomId"`
		DisplayName string `json:"displayName"`
	}
	if err := json.Unmarshal([]byte(jsonParams), &params); err != nil {
		j.logFn("dion-joiner: failed to parse params: %v", err)
		j.Status.EmitStatusError("bad params: " + err.Error())
		return
	}
	slug := normalizeDionSlug(params.RoomID)
	if slug == "" {
		j.logFn("dion-joiner: missing roomId")
		j.Status.EmitStatusError("missing roomId")
		return
	}
	if params.DisplayName == "" {
		params.DisplayName = "Joiner"
	}

	httpClient := j.makeHTTPClient()
	j.logFn("dion-joiner: room=%s name=%s", slug, params.DisplayName)
	j.Status.EmitStatus(common.StatusConnecting)

	auth, event, err := dion.JoinAsGuest(httpClient, slug, params.DisplayName)
	if err != nil {
		j.logFn("dion-joiner: JoinAsGuest failed: %v", err)
		j.Status.EmitStatusError("auth: " + err.Error())
		return
	}

	obf, err := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(event.Slug))
	if err != nil {
		j.logFn("dion-joiner: obfuscator init failed: %v", err)
		j.Status.EmitStatusError("obfuscator init: " + err.Error())
		return
	}
	j.logFn("dion-joiner: obf key-source=%q localEpoch=0x%08x", event.Slug, obf.LocalEpoch())

	var settingEngine *webrtc.SettingEngine
	if j.PCConfig != nil {
		se := webrtc.SettingEngine{}
		j.PCConfig.ConfigureSettingEngine(&se)
		settingEngine = &se
	}

	call := dion.NewCall(dion.CallConfig{
		Auth:           auth,
		Event:          event,
		Obfuscator:     obf,
		DisplayName:    params.DisplayName,
		LogFn:          j.logFn,
		SettingEngine:  settingEngine,
		NetDialContext: j.makeDialContext(),
		ResolveICEHost: j.ResolveFn,
		Role:           dion.RoleJoiner,
	})
	call.OnConnected = func(tun tunnel.DataTunnel) {
		j.logFn("dion-joiner: === TUNNEL CONNECTED ===")
		j.Status.EmitStatus(common.StatusTunnelConnected)
		if j.OnConnected != nil {
			j.OnConnected(tun)
		}
	}

	j.mu.Lock()
	j.call = call
	closed := j.closed
	j.mu.Unlock()
	if closed {
		call.Close()
		return
	}

	if err := call.Start(); err != nil {
		j.logFn("dion-joiner: call start failed: %v", err)
		j.Status.EmitStatusError("call: " + err.Error())
		return
	}
	<-call.Done()
	j.logFn("dion-joiner: call ended")
	j.Status.EmitStatus(common.StatusTunnelLost)
}

func (j *DionHeadlessJoiner) Close() {
	j.mu.Lock()
	j.closed = true
	call := j.call
	j.call = nil
	j.mu.Unlock()
	if call != nil {
		call.Close()
	}
}

func (j *DionHeadlessJoiner) makeDialContext() func(ctx context.Context, network, addr string) (net.Conn, error) {
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

func (j *DionHeadlessJoiner) makeHTTPClient() *http.Client {
	transport := &http.Transport{DialContext: j.makeDialContext()}
	return &http.Client{Timeout: 60 * time.Second, Transport: transport}
}

// normalizeDionSlug accepts a bare slug, a dion:// URI, or a full
// https://dion.vc/event/<slug> URL and returns the slug portion.
func normalizeDionSlug(input string) string {
	value := input
	for _, prefix := range []string{"dion://", "https://", "http://"} {
		if len(value) > len(prefix) && value[:len(prefix)] == prefix {
			value = value[len(prefix):]
		}
	}
	if idx := indexOf(value, "?"); idx >= 0 {
		value = value[:idx]
	}
	value = trimPrefix(value, "dion.vc/")
	value = trimPrefix(value, "event/")
	if idx := indexOf(value, "/"); idx >= 0 {
		value = value[:idx]
	}
	return value
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func trimPrefix(s, prefix string) string {
	if len(s) >= len(prefix) && s[:len(prefix)] == prefix {
		return s[len(prefix):]
	}
	return s
}
