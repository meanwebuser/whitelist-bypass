package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"

	"whitelist-bypass/relay/common"
	"whitelist-bypass/relay/dion"
	"whitelist-bypass/relay/tunnel"
)

func main() {
	common.MaybePrintVersion()
	roomFlag := flag.String("room", "", "event slug or https://dion.vc/event/<slug> (required)")
	displayName := flag.String("name", "Joiner", "display name in the room")
	socksHost := flag.String("socks-host", common.SocksLocalhostIP, "SOCKS5 listen address (use 0.0.0.0 to expose on LAN)")
	socksPort := flag.Int("socks-port", 1080, "SOCKS5 listen port")
	socksUser := flag.String("socks-user", "", "SOCKS5 username (optional)")
	socksPass := flag.String("socks-pass", "", "SOCKS5 password (optional)")
	resources := flag.String("resources", "default", "resource mode: moderate, default, unlimited")
	flag.Parse()

	var memLimit int64
	switch *resources {
	case "moderate":
		memLimit = 64 << 20
	case "default":
		memLimit = 128 << 20
	case "unlimited":
		memLimit = 256 << 20
	default:
		log.Fatalf("[config] unknown resources mode: %s", *resources)
	}
	if memLimit > 0 {
		debug.SetMemoryLimit(memLimit)
	}
	log.Printf("[config] resources=%s name=%q", *resources, *displayName)

	requestedRoom := dion.ParseRoom(*roomFlag)
	if requestedRoom == "" {
		log.Fatal("--room is required (room id or https://dion.vc/event/<id>)")
	}

	auth, event, err := dion.JoinAsGuest(nil, requestedRoom, *displayName)
	if err != nil {
		log.Fatalf("[FATAL] JoinAsGuest: %v", err)
	}
	log.Printf("[auth] anonymous guest authenticated for room=%s event_id=%s", event.Slug, event.ID)

	obf, err := tunnel.NewTunnelObfuscator(tunnel.DeriveSecretFromJoinLink(event.Slug))
	if err != nil {
		log.Fatalf("[FATAL] tunnel obfuscator: %v", err)
	}
	log.Printf("[obf] key-source=%q localEpoch=0x%08x", event.Slug, obf.LocalEpoch())

	shutdownChan := make(chan os.Signal, 1)
	signal.Notify(shutdownChan, os.Interrupt, syscall.SIGTERM)

	var activeBridge *tunnel.RelayBridge
	for {
		call := dion.NewCall(dion.CallConfig{
			Auth:        auth,
			Event:       event,
			Obfuscator:  obf,
			DisplayName: *displayName,
			LogFn:       log.Printf,
			Role:        dion.RoleJoiner,
		})
		call.OnConnected = func(tun tunnel.DataTunnel) {
			if activeBridge != nil {
				activeBridge.Reset()
			}
			activeBridge = tunnel.NewRelayBridgeWithAuth(tun, "joiner", common.VP8BufSize, log.Printf, *socksUser, *socksPass)
			activeBridge.MarkReady()
			addr := fmt.Sprintf("%s:%d", *socksHost, *socksPort)
			go func() {
				if err := activeBridge.ListenSOCKS(addr); err != nil {
					log.Printf("socks listen: %v", err)
				}
			}()
			fmt.Println("")
			fmt.Printf("  TUNNEL CONNECTED\n  socks5 -> %s\n", addr)
			fmt.Println("")
		}

		runDone := make(chan error, 1)
		go func() {
			if err := call.Start(); err != nil {
				runDone <- err
				return
			}
			<-call.Done()
			runDone <- nil
		}()

		select {
		case err := <-runDone:
			if err != nil {
				log.Printf("[call] start failed: %v", err)
			} else {
				log.Printf("[call] ended")
			}
		case <-shutdownChan:
			log.Printf("[shutdown] signal received, exiting")
			call.Close()
			if activeBridge != nil {
				activeBridge.Reset()
			}
			return
		}

		call.Close()
		if activeBridge != nil {
			activeBridge.Reset()
		}

		select {
		case <-time.After(3 * time.Second):
		case <-shutdownChan:
			log.Printf("[shutdown] signal received during cooldown, exiting")
			return
		}
	}
}

