package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"runtime/debug"
	"strings"
	"syscall"

	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/tunnel"
	"whitelist-bypass/relay/wbstream"
)

func main() {
	roomFlag := flag.String("room", "", "WB Stream room id or wbstream://<id> (empty = create new)")
	displayName := flag.String("name", "Headless", "display name in the room")
	resources := flag.String("resources", "default", "resource mode: default, moderate, unlimited, custom")
	customReadBuf := flag.Int("read-buf", 0, "DC read buffer size in bytes, used with -resources custom")
	customMemLimit := flag.Int64("mem-limit", 0, "memory limit in bytes, used with -resources custom")
	writeFile := flag.String("write-file", "", "path to file where active room id is appended")
	flag.Parse()

	var readBuf int
	var memLimit int64
	switch *resources {
	case "moderate":
		readBuf = 16384
		memLimit = 64 << 20
	case "default":
		readBuf = common.DCBufSize
		memLimit = 128 << 20
	case "unlimited":
		readBuf = common.RTPBufSize
		memLimit = 256 << 20
	case "custom":
		readBuf = *customReadBuf
		if readBuf == 0 {
			readBuf = common.RTPBufSize
		}
		memLimit = *customMemLimit
		if memLimit == 0 {
			memLimit = 256 << 20
		}
	default:
		log.Fatalf("[config] unknown resources mode: %s (use moderate, default, unlimited, custom)", *resources)
	}
	if memLimit > 0 {
		debug.SetMemoryLimit(memLimit)
	}
	common.MaskingEnabled = true
	log.Printf("[config] resources=%s read-buf=%d mem-limit=%d", *resources, readBuf, memLimit)

	requestedRoom := strings.TrimPrefix(strings.TrimSpace(*roomFlag), "wbstream://")
	roomID, roomToken, accessToken, err := wbstream.AuthAndGetToken(nil, requestedRoom, *displayName)
	if err != nil {
		log.Fatalf("[auth] %v", err)
	}
	log.Printf("[auth] room=%s", roomID)

	if *writeFile != "" {
		f, err := os.OpenFile(*writeFile, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			log.Fatalf("Failed to open write-file: %v", err)
		}
		fmt.Fprintln(f, "wbstream://"+roomID)
		f.Close()
		log.Printf("[config] Wrote join link to %s", *writeFile)
	}

	obf, err := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(roomID))
	if err != nil {
		log.Fatalf("[obf] init failed: %v", err)
	}
	log.Printf("[obf] key-source=%q localEpoch=0x%08x", roomID, obf.LocalEpoch())

	sess := wbstream.NewSession(wbstream.SessionConfig{
		RoomToken:   roomToken,
		DisplayName: *displayName,
		Obfuscator:  obf,
		LogFn:       log.Printf,
		RoomID:      roomID,
		AccessToken: accessToken,
		ReadBuf:     readBuf,
	})
	var activeBridge *tunnel.RelayBridge
	sess.OnConnected = func(tun tunnel.DataTunnel) {
		if activeBridge != nil {
			activeBridge.Reset()
		}
		bridgeReadBuf := common.VP8BufSize
		mode := "video"
		if _, ok := tun.(*tunnel.DCTunnel); ok {
			bridgeReadBuf = readBuf
			mode = "dc"
		}
		activeBridge = tunnel.NewRelayBridge(tun, "creator", bridgeReadBuf, log.Printf)
		fmt.Printf("\n  TUNNEL CONNECTED (mode=%s)\n", mode)
	}
	sess.OnPeerRestart = func() {
		if activeBridge != nil {
			log.Printf("[creator] new peer detected, resetting relay bridge")
			activeBridge.Reset()
		}
	}

	fmt.Println("")
	fmt.Println("  CALL CREATED")
	fmt.Println("  join_link: wbstream://" + roomID)
	fmt.Println("")

	if err := sess.Start(); err != nil {
		log.Fatalf("[session] %v", err)
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	log.Printf("[main] shutting down")
	sess.Close()
}
