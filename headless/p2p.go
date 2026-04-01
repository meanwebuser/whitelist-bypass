package main

import (
	"encoding/json"
	"fmt"
	"log"

	"github.com/gorilla/websocket"
)

type P2PHandler struct {
	bridge            *Bridge
	remotePeerId      *int64
	pendingOffer      json.RawMessage
	pendingCandidates []json.RawMessage
	connected         bool
}

func NewP2PHandler(bridge *Bridge) *P2PHandler {
	return &P2PHandler{bridge: bridge}
}

// Init creates the initial Pion PC and offer.
func (p *P2PHandler) Init() {
	p.bridge.pionSend("ice-servers", p.bridge.iceServers)
	offerRaw, err := p.bridge.pionRequest("create-offer", map[string]interface{}{})
	if err != nil {
		log.Fatalf("[p2p] Create offer failed: %v", err)
	}
	p.pendingOffer = offerRaw
	log.Printf("[p2p] Offer ready, length: %d", len(offerRaw))
}

// Reset tears down the current Pion PC and creates a fresh one with a new offer.
func (p *P2PHandler) Reset() {
	log.Println("[p2p] Resetting Pion PC...")
	p.connected = false
	p.bridge.pionRequest("reset", map[string]interface{}{})
	p.bridge.pionSend("ice-servers", p.bridge.iceServers)

	offerRaw, err := p.bridge.pionRequest("create-offer", map[string]interface{}{})
	if err != nil {
		log.Printf("[p2p] Reset create-offer failed: %v", err)
		return
	}
	p.pendingOffer = offerRaw
	p.pendingCandidates = nil
	log.Printf("[p2p] New offer ready after reset, length: %d", len(offerRaw))
}

// OnRegisteredPeer handles the registered-peer notification.
func (p *P2PHandler) OnRegisteredPeer(participantId int64) {
	oldPeer := p.remotePeerId
	p.remotePeerId = &participantId

	log.Printf("[p2p] Peer registered: %d (connected=%v)", participantId, p.connected)

	if oldPeer != nil && (p.pendingOffer == nil) {
		if *oldPeer != participantId {
			log.Printf("[p2p] New peer %d replacing old peer %d, resetting", participantId, *oldPeer)
		} else {
			log.Printf("[p2p] Same peer %d re-registered, no pending offer, resetting", participantId)
		}
		p.Reset()
	}

	p.sendOfferToPeer(participantId)
}

// OnTransmittedData handles SDP and ICE candidates from the remote peer.
func (p *P2PHandler) OnTransmittedData(data map[string]interface{}) {
	if cand, ok := data["candidate"]; ok {
		log.Println("[p2p] Remote ICE candidate")
		candJSON, _ := json.Marshal(cand)
		p.bridge.pionSend("remote-ice-candidate", json.RawMessage(candJSON))
	}
	if sdp, ok := data["sdp"].(map[string]interface{}); ok {
		sdpType, _ := sdp["type"].(string)
		log.Printf("[p2p] Remote SDP: %s", sdpType)
		sdpJSON, _ := json.Marshal(sdp)
		if sdpType == "answer" {
			p.bridge.pionRequest("set-remote-description", json.RawMessage(sdpJSON))
		} else if sdpType == "offer" {
			p.bridge.pionRequest("set-remote-description", json.RawMessage(sdpJSON))
			answerRaw, err := p.bridge.pionRequest("create-answer", map[string]interface{}{})
			if err == nil && p.remotePeerId != nil {
				var answer interface{}
				json.Unmarshal(answerRaw, &answer)
				p.bridge.vkSend("transmit-data", map[string]interface{}{
					"participantId": *p.remotePeerId,
					"data":          map[string]interface{}{"sdp": answer},
				})
			}
		}
	}
}

// OnPionICECandidate handles an ICE candidate from the local Pion PC.
func (p *P2PHandler) OnPionICECandidate(data json.RawMessage) {
	if p.remotePeerId != nil {
		var cand interface{}
		json.Unmarshal(data, &cand)
		p.bridge.vkSend("transmit-data", map[string]interface{}{
			"participantId": *p.remotePeerId,
			"data":          map[string]interface{}{"candidate": cand},
		})
	} else {
		p.pendingCandidates = append(p.pendingCandidates, data)
	}
}

// OnConnectionState updates P2P connection state.
func (p *P2PHandler) OnConnectionState(state string) {
	switch state {
	case "connected":
		p.connected = true
		log.Println("\n  TUNNEL CONNECTED\n")
	case "disconnected":
		p.connected = false
		log.Println("[p2p] Connection disconnected, waiting for peer to rejoin")
	case "failed":
		p.connected = false
		log.Println("[p2p] Connection failed, removing stale peer")
		if p.remotePeerId != nil {
			p.bridge.vkSend("remove-participant", map[string]interface{}{
				"participantId": *p.remotePeerId,
				"ban":           false,
			})
		}
	case "closed":
		p.connected = false
		log.Println("[p2p] Connection closed")
	}
}

func (p *P2PHandler) sendOfferToPeer(participantId int64) {
	offer := p.pendingOffer
	candidates := p.pendingCandidates
	p.pendingOffer = nil
	p.pendingCandidates = nil

	if offer != nil {
		log.Printf("[p2p] Sending offer to peer %d", participantId)
		var offerObj struct {
			Type string `json:"type"`
			SDP  string `json:"sdp"`
		}
		json.Unmarshal(offer, &offerObj)
		sdpStr, _ := json.Marshal(offerObj.SDP)
		p.bridge.mu.Lock()
		p.bridge.vkSeq++
		seq := p.bridge.vkSeq
		raw := fmt.Sprintf(`{"command":"transmit-data","sequence":%d,"participantId":%d,"data":{"sdp":{"type":%q,"sdp":%s}}}`,
			seq, participantId, offerObj.Type, sdpStr)
		if p.bridge.vkWs != nil {
			p.bridge.vkWs.WriteMessage(websocket.TextMessage, []byte(raw))
		}
		p.bridge.mu.Unlock()
		log.Printf("[vk-ws] -> transmit-data (offer)")
	}

	for _, cand := range candidates {
		var c interface{}
		json.Unmarshal(cand, &c)
		p.bridge.vkSend("transmit-data", map[string]interface{}{
			"participantId": participantId,
			"data":          map[string]interface{}{"candidate": c},
		})
	}
	if len(candidates) > 0 {
		log.Printf("[p2p] Flushed %d ICE candidates", len(candidates))
	}
}
