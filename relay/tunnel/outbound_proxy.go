package tunnel

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

func dialTCPOutbound(addr string, timeout time.Duration) (net.Conn, error) {
	proxyRaw := strings.TrimSpace(os.Getenv("WBSTREAM_OUTBOUND_HTTP_PROXY"))
	if proxyRaw == "" {
		proxyRaw = strings.TrimSpace(os.Getenv("WHITETRANSPORT_OUTBOUND_HTTP_PROXY"))
	}
	if proxyRaw == "" {
		return net.DialTimeout("tcp", addr, timeout)
	}
	return dialTCPViaHTTPProxy(addr, proxyRaw, timeout)
}

func dialTCPViaHTTPProxy(addr string, proxyRaw string, timeout time.Duration) (net.Conn, error) {
	if !strings.Contains(proxyRaw, "://") {
		proxyRaw = "http://" + proxyRaw
	}
	proxyURL, err := url.Parse(proxyRaw)
	if err != nil {
		return nil, fmt.Errorf("invalid WBSTREAM_OUTBOUND_HTTP_PROXY: %w", err)
	}
	if proxyURL.Scheme != "http" {
		return nil, fmt.Errorf("unsupported WBSTREAM_OUTBOUND_HTTP_PROXY scheme %q; only http CONNECT is supported", proxyURL.Scheme)
	}
	proxyAddr := proxyURL.Host
	if proxyAddr == "" {
		return nil, fmt.Errorf("invalid WBSTREAM_OUTBOUND_HTTP_PROXY: empty host")
	}
	if _, _, err := net.SplitHostPort(proxyAddr); err != nil {
		proxyAddr = net.JoinHostPort(proxyAddr, "3128")
	}
	conn, err := net.DialTimeout("tcp", proxyAddr, timeout)
	if err != nil {
		return nil, fmt.Errorf("proxy dial %s failed: %w", proxyAddr, err)
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	req := &http.Request{Method: "CONNECT", URL: &url.URL{Opaque: addr}, Host: addr, Header: make(http.Header)}
	req.Header.Set("Host", addr)
	req.Header.Set("Proxy-Connection", "Keep-Alive")
	if proxyURL.User != nil {
		password, _ := proxyURL.User.Password()
		auth := proxyURL.User.Username() + ":" + password
		req.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(auth)))
	}
	if err := req.Write(conn); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("proxy CONNECT write failed: %w", err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("proxy CONNECT response failed: %w", err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_ = conn.Close()
		return nil, fmt.Errorf("proxy CONNECT %s returned %s", addr, resp.Status)
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}
