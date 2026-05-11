package joiner

import (
	"strings"

	"github.com/pion/logging"
)

// QuietPionLoggerFactory returns a Pion LoggerFactory that silences the
// "turnc" scope. Pion's TURN client logs CreatePermission refresh
// failures at Error level every few minutes during a normal call when
// the SFU advertises peer addresses the TURN server rejects on
// refresh (often IPv6). The call itself is unaffected.
func QuietPionLoggerFactory() *logging.DefaultLoggerFactory {
	factory := logging.NewDefaultLoggerFactory()
	factory.ScopeLevels["turnc"] = logging.LogLevelDisabled
	return factory
}

func TmParseMids(sdp string) (audioMid, videoMid string) {
	var media string
	for _, line := range strings.Split(sdp, "\r\n") {
		if strings.HasPrefix(line, "m=audio") {
			media = "audio"
		} else if strings.HasPrefix(line, "m=video") {
			media = "video"
		}
		if strings.HasPrefix(line, "a=mid:") {
			mid := strings.TrimPrefix(line, "a=mid:")
			if media == "audio" && audioMid == "" {
				audioMid = mid
			} else if media == "video" && videoMid == "" {
				videoMid = mid
			}
		}
	}
	return
}
