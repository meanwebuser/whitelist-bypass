package tunnel

import "testing"

func TestHandleFrameWithoutObfuscatorDeliversRawPayload(t *testing.T) {
	tunnel := NewVP8DataTunnelWithQueue(nil, nil, func(string, ...any) {}, sendQueueDepth)
	payload := []byte("raw-vp8-tunnel-payload")
	var got []byte
	tunnel.SetOnData(func(data []byte) { got = append([]byte(nil), data...) })

	tunnel.HandleFrame(payload)

	if string(got) != string(payload) {
		t.Fatalf("delivered payload = %q, want %q", got, payload)
	}
}
