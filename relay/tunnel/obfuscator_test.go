package tunnel

import "testing"

func TestNilObfuscatorKeepaliveIsSafe(t *testing.T) {
	var obfuscator *TunnelObfuscator
	if got := obfuscator.EncodeKeepalive(1); got != nil {
		t.Fatalf("nil obfuscator keepalive = %x, want nil", got)
	}
}
