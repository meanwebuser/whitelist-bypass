package tunnel

import (
	"testing"

	"github.com/pion/rtp/codecs"
)

func TestKCPSegmentMTUFitsSingleVP8RTPPacket(t *testing.T) {
	obfuscator, err := NewTunnelObfuscator([]byte("deterministic-kcp-mtu-fixture"))
	if err != nil {
		t.Fatalf("NewTunnelObfuscator: %v", err)
	}

	// KCP can emit one segment up to its configured MTU. The carrier prepends
	// one channel byte before the obfuscator adds its authenticated envelope.
	segment := make([]byte, kcpSegmentMTU+1)
	encoded := obfuscator.EncodeData(segment)
	maxEncodedSample := kcpRTPPacketMTU - kcpRTPBaseHeaderLen - kcpRTPExtensionBudget - kcpVP8DescriptorLen
	if len(encoded) > maxEncodedSample {
		t.Fatalf("encoded KCP carrier sample = %d bytes, one-packet budget = %d", len(encoded), maxEncodedSample)
	}
	fragments := (&codecs.VP8Payloader{}).Payload(kcpRTPPacketMTU-kcpRTPBaseHeaderLen, encoded)
	if len(fragments) != 1 {
		t.Fatalf(
			"encoded KCP carrier sample split into %d RTP payloads: kcp_mtu=%d encoded=%d max_vp8_sample=%d",
			len(fragments), kcpSegmentMTU, len(encoded), kcpRTPPacketMTU-kcpRTPBaseHeaderLen-kcpVP8DescriptorLen,
		)
	}
}
