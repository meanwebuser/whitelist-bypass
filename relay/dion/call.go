package dion

import (
	"context"
	"fmt"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"

	"whitelist-bypass/relay/tunnel"
)

const (
	sendVideoMidIndex   = 12
	defaultRecvVideoMid = "0"
)

// CallConfig configures a Call lifecycle. Auth must already have a valid
// access token (caller did LoadCookiesFromFile + EnsureValidToken). Event
// must be a usable EventInfo (CreateRoom or GetEventBySlug result).
type CallConfig struct {
	Auth        *Session
	Event       *EventInfo
	Obfuscator  *tunnel.TunnelObfuscator
	DisplayName string
	LogFn       func(string, ...any)
	VP8FPS      int
	VP8Batch    int
	RecvMid     string

	// SettingEngine, NetDialContext, and ResolveICEHost are forwarded to Pion
	// and the WebSocket dialer. All three are no-ops if nil (desktop default).
	// They exist so the Android relay can plug in AndroidNet plus stdin-based
	// DNS resolution before the VPN starts intercepting traffic.
	SettingEngine  *webrtc.SettingEngine
	NetDialContext func(ctx context.Context, network, addr string) (net.Conn, error)
	ResolveICEHost func(host string) (string, error)
}

// PeerEntry tracks one remote peer's signaling state.
type PeerEntry struct {
	SessionID string
	UserID    string
	Name      string
	CamState  bool
}

// Call drives one full DION room session: signaling, Pion peer, VP8 send
// track on mid=12, OnTrack reader on the bound recv mid, plus discovery and
// subscription to other peers via conf_speakers_state and get_video_from_user.
//
// Lifecycle: NewCall(cfg) -> Start() -> wait OnConnected(tunnel.DataTunnel) ->
// use tunnel via RelayBridge -> wait Done() (read-loop or ICE death) -> Close().
type Call struct {
	cfg         CallConfig
	signaling   *SignalingClient
	peer        *PionPeer
	sendTrack   *webrtc.TrackLocalStaticSample
	vp8tun      *tunnel.VP8DataTunnel
	mySessionID string

	peersMu    sync.Mutex
	peersByID  map[string]*PeerEntry
	subscribed map[string]bool

	onConnectedFired atomic.Bool

	OnConnected   func(tunnel.DataTunnel)
	OnPeerRestart func()

	done     chan struct{}
	closeOnce sync.Once
}

func NewCall(cfg CallConfig) *Call {
	if cfg.LogFn == nil {
		cfg.LogFn = log.Printf
	}
	if cfg.RecvMid == "" {
		cfg.RecvMid = defaultRecvVideoMid
	}
	return &Call{
		cfg:        cfg,
		peersByID:  make(map[string]*PeerEntry),
		subscribed: make(map[string]bool),
		done:       make(chan struct{}),
	}
}

func (c *Call) Done() <-chan struct{} { return c.done }

func (c *Call) SessionID() string { return c.mySessionID }

func (c *Call) Close() {
	c.closeOnce.Do(func() {
		if c.vp8tun != nil {
			c.vp8tun.Stop()
		}
		if c.signaling != nil {
			c.signaling.Close()
		}
		if c.peer != nil {
			c.peer.Close()
		}
	})
}

// Start runs the full lifecycle to the point where the VP8 tunnel is up and
// OnConnected has fired. It returns nil on success; from that point the call
// continues in the background until ICE death or signaling read-loop end, at
// which point Done() is closed.
func (c *Call) Start() error {
	sessionID := uuid.New().String()
	c.mySessionID = sessionID
	c.cfg.LogFn("[call] my session_id=%s", sessionID)

	wss, err := c.cfg.Auth.ConnectWSS(sessionID)
	if err != nil {
		return fmt.Errorf("ConnectWSS: %w", err)
	}

	signaling, err := DialSignaling(wss.URL, SignalingDialOptions{
		UserAgent:      c.cfg.Auth.Device.UserAgent,
		LogFn:          c.cfg.LogFn,
		NetDialContext: c.cfg.NetDialContext,
	})
	if err != nil {
		return fmt.Errorf("DialSignaling: %w", err)
	}
	c.signaling = signaling
	if err := signaling.WaitConnected(15 * time.Second); err != nil {
		return fmt.Errorf("WaitConnected: %w", err)
	}

	youJoinedChan := make(chan YouJoinedParams, 1)
	sdpAnswerChan := make(chan SDPAnswerParams, 4)
	var onceYouJoined sync.Once

	signaling.OnYouJoined = func(params YouJoinedParams) {
		onceYouJoined.Do(func() { youJoinedChan <- params })
	}
	signaling.OnSDPAnswer = func(answerSDP string, transceivers []TransceiverDesc) {
		select {
		case sdpAnswerChan <- SDPAnswerParams{Answer: answerSDP, Transceivers: transceivers}:
		default:
		}
	}
	signaling.OnSpeakerJoined = c.handleSpeakerJoined
	signaling.OnSpeakerDisconnected = c.handleSpeakerDisconnected
	signaling.OnSpeakerCamStateChanged = c.handleSpeakerCamStateChanged
	signaling.OnConfSpeakersState = c.handleConfSpeakersState

	readLoopDone := make(chan error, 1)
	go func() { readLoopDone <- signaling.ReadLoop() }()

	if err := signaling.Subscribe(c.cfg.Event.ID, sessionID); err != nil {
		return fmt.Errorf("Subscribe: %w", err)
	}

	var youJoined YouJoinedParams
	select {
	case youJoined = <-youJoinedChan:
	case err := <-readLoopDone:
		return fmt.Errorf("read loop ended before you_joined: %v", err)
	case <-time.After(15 * time.Second):
		return fmt.Errorf("timeout waiting for you_joined")
	}
	c.cfg.LogFn("[call] you_joined ice_servers=%d", len(youJoined.IceServers))

	pionAPI := NewPionAPI(c.cfg.SettingEngine)
	iceServers := ResolveICEServerHosts(youJoined.IceServers, c.cfg.ResolveICEHost, c.cfg.LogFn)
	peer, err := BuildPionPeer(pionAPI, iceServers)
	if err != nil {
		return fmt.Errorf("BuildPionPeer: %w", err)
	}
	c.peer = peer

	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
		"video", "dion-tunnel-"+sessionID,
	)
	if err != nil {
		return fmt.Errorf("NewTrackLocalStaticSample: %w", err)
	}
	c.sendTrack = track
	if len(peer.Transceivers) <= sendVideoMidIndex {
		return fmt.Errorf("transceiver layout short, have %d", len(peer.Transceivers))
	}
	sender := peer.Transceivers[sendVideoMidIndex].Sender()
	if sender == nil {
		return fmt.Errorf("mid=%d sender nil", sendVideoMidIndex)
	}
	if err := sender.ReplaceTrack(track); err != nil {
		return fmt.Errorf("ReplaceTrack: %w", err)
	}

	peer.PC.OnTrack(func(remoteTrack *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		c.cfg.LogFn("[call] OnTrack id=%q kind=%s codec=%s ssrc=%d",
			remoteTrack.ID(), remoteTrack.Kind().String(), remoteTrack.Codec().MimeType, remoteTrack.SSRC())
		if remoteTrack.Codec().MimeType != webrtc.MimeTypeVP8 {
			go drainTrack(remoteTrack)
			return
		}
		go c.readVP8Track(remoteTrack)
	})

	var pendingMu sync.Mutex
	pendingCandidates := make([]webrtc.ICECandidateInit, 0, 32)
	remoteSet := false
	sendCandidate := func(cand webrtc.ICECandidateInit) {
		entry := ICECandidateJSON{Candidate: cand.Candidate}
		if cand.SDPMid != nil {
			m := *cand.SDPMid
			entry.SDPMid = &m
		}
		if cand.SDPMLineIndex != nil {
			i := *cand.SDPMLineIndex
			entry.SDPMLineIndex = &i
		}
		if cand.UsernameFragment != nil {
			entry.UsernameFragment = *cand.UsernameFragment
		}
		if err := signaling.SendICECandidates([]ICECandidateJSON{entry}); err != nil {
			c.cfg.LogFn("[ice] SendICECandidates: %v", err)
		}
	}
	flushPending := func() {
		pendingMu.Lock()
		toFlush := pendingCandidates
		pendingCandidates = nil
		pendingMu.Unlock()
		for _, cand := range toFlush {
			sendCandidate(cand)
		}
	}
	peer.PC.OnICECandidate(func(cand *webrtc.ICECandidate) {
		if cand == nil {
			return
		}
		init := cand.ToJSON()
		pendingMu.Lock()
		alreadyRemote := remoteSet
		if !alreadyRemote {
			pendingCandidates = append(pendingCandidates, init)
		}
		pendingMu.Unlock()
		if alreadyRemote {
			sendCandidate(init)
		}
	})

	iceConnected := make(chan struct{}, 1)
	iceDead := make(chan webrtc.ICEConnectionState, 1)
	peer.PC.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		c.cfg.LogFn("[ice] state=%s", state.String())
		switch state {
		case webrtc.ICEConnectionStateConnected, webrtc.ICEConnectionStateCompleted:
			select {
			case iceConnected <- struct{}{}:
			default:
			}
		case webrtc.ICEConnectionStateFailed, webrtc.ICEConnectionStateClosed:
			select {
			case iceDead <- state:
			default:
			}
		}
	})

	envelope, _, err := peer.CreateAndSetOffer()
	if err != nil {
		return fmt.Errorf("CreateAndSetOffer: %w", err)
	}
	offerParams := SDPOfferParams{
		MicState:              false,
		CamState:              false,
		NoiseSuppressionState: true,
		ScreenSharingQuality:  "default",
		Datachannels:          peer.DatachannelDescs,
		Transceivers:          peer.TransceiverDescs,
		Offer:                 envelope,
	}
	if err := signaling.SendSDPOffer(offerParams); err != nil {
		return fmt.Errorf("SendSDPOffer: %w", err)
	}

	var answer SDPAnswerParams
	select {
	case answer = <-sdpAnswerChan:
	case err := <-readLoopDone:
		return fmt.Errorf("read loop ended before sdp_answer: %v", err)
	case <-time.After(20 * time.Second):
		return fmt.Errorf("timeout waiting for sdp_answer")
	}
	if err := peer.PC.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeAnswer,
		SDP:  answer.Answer,
	}); err != nil {
		return fmt.Errorf("SetRemoteDescription: %w", err)
	}
	pendingMu.Lock()
	remoteSet = true
	pendingMu.Unlock()
	flushPending()

	select {
	case <-iceConnected:
	case state := <-iceDead:
		return fmt.Errorf("ICE died before connected: %s", state.String())
	case err := <-readLoopDone:
		return fmt.Errorf("read loop ended before ICE connected: %v", err)
	case <-time.After(30 * time.Second):
		return fmt.Errorf("timeout waiting for ICE connected; state=%s", peer.PC.ICEConnectionState().String())
	}
	c.cfg.LogFn("[ice] connected")

	c.vp8tun = tunnel.NewVP8DataTunnel(c.sendTrack, c.cfg.Obfuscator, c.cfg.LogFn)
	c.vp8tun.Start(c.cfg.VP8FPS, c.cfg.VP8Batch)
	c.fireOnConnected(c.vp8tun)

	if err := signaling.SendCamStateChange(true); err != nil {
		c.cfg.LogFn("[call] SendCamStateChange: %v", err)
	} else {
		c.cfg.LogFn("[call] sent cam_state_change=true")
	}

	go c.discoverPeersAndSubscribe()

	go func() {
		defer close(c.done)
		select {
		case state := <-iceDead:
			c.cfg.LogFn("[call] ICE went to %s", state.String())
		case err := <-readLoopDone:
			c.cfg.LogFn("[call] read loop ended: %v", err)
		}
	}()

	return nil
}

func (c *Call) fireOnConnected(tun tunnel.DataTunnel) {
	if !c.onConnectedFired.CompareAndSwap(false, true) {
		return
	}
	if c.OnConnected != nil {
		c.OnConnected(tun)
	}
}

func (c *Call) handleSpeakerJoined(params SpeakerJoinedParams) {
	if params.SessionID == c.mySessionID {
		return
	}
	c.peersMu.Lock()
	c.peersByID[params.SessionID] = &PeerEntry{
		SessionID: params.SessionID,
		UserID:    params.UserID,
		Name:      params.Name,
		CamState:  params.CamState,
	}
	c.peersMu.Unlock()
	c.cfg.LogFn("[call] speaker_joined session_id=%s name=%q cam=%v", params.SessionID, params.Name, params.CamState)
	if params.CamState {
		c.subscribeIfNeeded(params.SessionID)
	}
}

func (c *Call) handleSpeakerDisconnected(params SpeakerDisconnectedParams) {
	c.peersMu.Lock()
	delete(c.peersByID, params.SessionID)
	delete(c.subscribed, params.SessionID)
	c.peersMu.Unlock()
	c.cfg.LogFn("[call] speaker_disconnected session_id=%s", params.SessionID)
}

func (c *Call) handleSpeakerCamStateChanged(params SpeakerCamStateChangedParams) {
	if params.SessionID == c.mySessionID {
		return
	}
	c.peersMu.Lock()
	if entry, ok := c.peersByID[params.SessionID]; ok {
		entry.CamState = params.CamState
	} else {
		c.peersByID[params.SessionID] = &PeerEntry{SessionID: params.SessionID, CamState: params.CamState}
	}
	c.peersMu.Unlock()
	c.cfg.LogFn("[call] speaker_cam_state_changed session_id=%s cam=%v", params.SessionID, params.CamState)
	if params.CamState {
		c.subscribeIfNeeded(params.SessionID)
	}
}

func (c *Call) handleConfSpeakersState(response ConfSpeakersStateResponse) {
	for _, entry := range response.Speakers {
		if entry.SessionID == c.mySessionID || entry.SessionID == "" {
			continue
		}
		c.peersMu.Lock()
		c.peersByID[entry.SessionID] = &PeerEntry{
			SessionID: entry.SessionID,
			UserID:    entry.UserID,
			Name:      entry.Name,
			CamState:  entry.CamState,
		}
		c.peersMu.Unlock()
		if entry.CamState {
			c.subscribeIfNeeded(entry.SessionID)
		}
	}
}

func (c *Call) discoverPeersAndSubscribe() {
	time.Sleep(500 * time.Millisecond)
	if err := c.signaling.SendConfSpeakersState(DefaultConfSpeakersStateRequest()); err != nil {
		c.cfg.LogFn("[call] SendConfSpeakersState: %v", err)
	}
}

func (c *Call) subscribeIfNeeded(peerSessionID string) {
	c.peersMu.Lock()
	if c.subscribed[peerSessionID] {
		c.peersMu.Unlock()
		return
	}
	entry := c.peersByID[peerSessionID]
	if entry == nil {
		c.peersMu.Unlock()
		return
	}
	c.subscribed[peerSessionID] = true
	request := GetVideoFromUserRequest{
		SessionID:     entry.SessionID,
		TransceiverID: c.cfg.RecvMid,
		UserID:        entry.UserID,
		Username:      entry.Name,
	}
	c.peersMu.Unlock()
	if err := c.signaling.SendGetVideoFromUser(request); err != nil {
		c.cfg.LogFn("[call] SendGetVideoFromUser session_id=%s: %v", peerSessionID, err)
		c.peersMu.Lock()
		delete(c.subscribed, peerSessionID)
		c.peersMu.Unlock()
		return
	}
	c.cfg.LogFn("[call] subscribed to %s on mid=%s", peerSessionID, c.cfg.RecvMid)
	if c.OnPeerRestart != nil {
		c.OnPeerRestart()
	}
}

func (c *Call) readVP8Track(track *webrtc.TrackRemote) {
	var vp8Pkt codecs.VP8Packet
	var frameBuf []byte
	var lastSeq uint16
	var haveLastSeq bool
	frameValid := false
	for {
		pkt, _, err := track.ReadRTP()
		if err != nil {
			return
		}
		if pkt == nil {
			continue
		}
		if haveLastSeq && pkt.SequenceNumber != lastSeq+1 {
			frameValid = false
			frameBuf = frameBuf[:0]
		}
		lastSeq = pkt.SequenceNumber
		haveLastSeq = true
		vp8Payload, err := vp8Pkt.Unmarshal(pkt.Payload)
		if err != nil {
			frameValid = false
			frameBuf = frameBuf[:0]
			continue
		}
		if vp8Pkt.S == 1 {
			frameBuf = frameBuf[:0]
			frameValid = true
		}
		if !frameValid {
			continue
		}
		frameBuf = append(frameBuf, vp8Payload...)
		if !pkt.Marker {
			continue
		}
		if c.vp8tun != nil {
			c.vp8tun.HandleFrame(frameBuf)
		}
		frameBuf = frameBuf[:0]
		frameValid = false
	}
}

func drainTrack(track *webrtc.TrackRemote) {
	buf := make([]byte, 1500)
	for {
		if _, _, err := track.Read(buf); err != nil {
			return
		}
	}
}
