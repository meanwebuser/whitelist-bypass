package vkcall

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestJoinExisting(t *testing.T) {
	t.Parallel()

	var mu sync.Mutex
	var requests []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Fatalf("parse form: %v", err)
		}
		mu.Lock()
		requests = append(requests, r.URL.Path+"?"+r.URL.RawQuery+":"+r.Form.Get("method"))
		mu.Unlock()
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if got := r.Header.Get("Content-Type"); got != "application/x-www-form-urlencoded" {
			t.Errorf("Content-Type = %q", got)
		}

		switch r.URL.Path {
		case "/web-token":
			if r.Form.Get("version") != "1" || r.Form.Get("app_id") != "app-id" {
				t.Errorf("web token form = %v", r.Form)
			}
			if r.Header.Get("Cookie") != "sid=secret" {
				t.Errorf("cookie was not forwarded")
			}
			writeJSON(t, w, `{"data":{"access_token":"vk-token"}}`)
		case "/settings":
			assertBearer(t, r, "vk-token")
			writeJSON(t, w, `{"response":{"settings":{"public_key":"app-key"}}}`)
		case "/call-token":
			assertBearer(t, r, "vk-token")
			if r.Form.Get("env") != "production" {
				t.Errorf("env = %q", r.Form.Get("env"))
			}
			writeJSON(t, w, `{"response":{"token":"anon-token","api_base_url":"`+serverURL(r)+`/call"}}`)
		case "/call/fb.do":
			switch r.Form.Get("method") {
			case "auth.anonymLogin":
				var data map[string]any
				if err := json.Unmarshal([]byte(r.Form.Get("session_data")), &data); err != nil {
					t.Fatalf("session data: %v", err)
				}
				if data["auth_token"] != "anon-token" || data["client_version"] != "1.1" {
					t.Errorf("session data = %#v", data)
				}
				writeJSON(t, w, `{"session_key":"session-key"}`)
			case "vchat.joinConversationByLink":
				if r.Form.Get("joinLink") != "ok-join" || r.Form.Get("session_key") != "session-key" {
					t.Errorf("join form = %v", r.Form)
				}
				writeJSON(t, w, `{"endpoint":"wss://ws.example","wt_endpoint":"wss://wt.example","turn_server":{"urls":["turn:example"],"username":"turn-user","credential":"turn-secret"},"stun_server":{"urls":["stun:example"]}}`)
			default:
				t.Errorf("unexpected call method %q", r.Form.Get("method"))
			}
		default:
			t.Errorf("unexpected path %q", r.URL.Path)
		}
	}))
	defer server.Close()

	client, err := New(Config{
		AppID:           "app-id",
		APIVersion:      "5.282",
		AppVersion:      "1.1",
		ProtocolVersion: "5",
		HTTPClient:      server.Client(),
		Endpoints: Endpoints{
			WebTokenURL:     server.URL + "/web-token",
			CallSettingsURL: server.URL + "/settings",
			CallTokenURL:    server.URL + "/call-token",
			CallStartURL:    server.URL + "/calls/start",
		},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	info, err := client.JoinExisting(context.Background(), "sid=secret", "https://vk.ru/call/ok-join")
	if err != nil {
		t.Fatalf("JoinExisting: %v", err)
	}
	if info.OKJoinLink != "ok-join" || info.SessionKey != "session-key" || info.ApplicationKey != "app-key" || info.APIBaseURL != server.URL+"/call/fb.do" {
		t.Fatalf("unexpected CallInfo: %#v", info)
	}
	if info.JoinerAuth().AnonymToken != "anon-token" || info.JoinerAuth().AppVersion != "1.1" {
		t.Fatalf("unexpected joiner auth: %#v", info.JoinerAuth())
	}

	mu.Lock()
	defer mu.Unlock()
	want := []string{"/web-token?:", "/settings?:", "/call-token?:", "/call/fb.do?:auth.anonymLogin", "/call/fb.do?:vchat.joinConversationByLink"}
	if strings.Join(requests, ",") != strings.Join(want, ",") {
		t.Fatalf("requests = %v, want %v", requests, want)
	}
}

func TestCreateAndJoin(t *testing.T) {
	t.Parallel()

	var mu sync.Mutex
	var requests []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			t.Fatalf("parse form: %v", err)
		}
		mu.Lock()
		requests = append(requests, r.URL.Path+":"+r.Form.Get("method"))
		mu.Unlock()
		switch r.URL.Path {
		case "/web-token":
			writeJSON(t, w, `{"data":{"access_token":"vk-token"}}`)
		case "/calls/start":
			assertBearer(t, r, "vk-token")
			if r.Form.Get("peer_id") != "42" || r.Form.Get("v") != "5.282" {
				t.Errorf("start form = %v", r.Form)
			}
			writeJSON(t, w, `{"response":{"call_id":"call-id","join_link":"https://vk.ru/call/join","ok_join_link":"ok-join","short_credentials":{"link_with_password":"https://vk.ru/s/short"}}}`)
		case "/settings":
			writeJSON(t, w, `{"response":{"settings":{"public_key":"app-key"}}}`)
		case "/call-token":
			writeJSON(t, w, `{"response":{"token":"anon-token","api_base_url":"`+serverURL(r)+`/call/fb.do"}}`)
		case "/call/fb.do":
			if r.Form.Get("method") == "auth.anonymLogin" {
				writeJSON(t, w, `{"session_key":"session-key"}`)
			} else if r.Form.Get("method") == "vchat.joinConversationByLink" {
				writeJSON(t, w, `{"endpoint":"wss://ws.example","wt_endpoint":"wss://wt.example"}`)
			} else {
				t.Errorf("unexpected call method %q", r.Form.Get("method"))
			}
		default:
			t.Errorf("unexpected path %q", r.URL.Path)
		}
	}))
	defer server.Close()

	client, err := New(testConfig(server))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	info, err := client.CreateAndJoin(context.Background(), "sid=secret", "42")
	if err != nil {
		t.Fatalf("CreateAndJoin: %v", err)
	}
	if info.CallID != "call-id" || info.JoinLink != "https://vk.ru/call/join" || info.ShortLink != "https://vk.ru/s/short" || info.OKJoinLink != "ok-join" {
		t.Fatalf("unexpected CallInfo: %#v", info)
	}

	mu.Lock()
	defer mu.Unlock()
	want := []string{"/web-token:", "/calls/start:", "/web-token:", "/settings:", "/call-token:", "/call/fb.do:auth.anonymLogin", "/call/fb.do:vchat.joinConversationByLink"}
	if strings.Join(requests, ",") != strings.Join(want, ",") {
		t.Fatalf("requests = %v, want %v", requests, want)
	}
}

func TestMalformedInput(t *testing.T) {
	t.Parallel()

	if _, err := New(Config{AppID: "app"}); err == nil {
		t.Fatal("New accepted incomplete config")
	}
	client, err := New(Config{AppID: "app", APIVersion: "5.282", Endpoints: Endpoints{WebTokenURL: "://bad", CallSettingsURL: "https://example.test/settings", CallTokenURL: "https://example.test/token", CallStartURL: "https://example.test/start"}})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := client.JoinExisting(context.Background(), "sid=secret", "https://vk.ru/"); err == nil {
		t.Fatal("JoinExisting accepted a link without a join token")
	}
	if _, err := client.CreateAndJoin(context.Background(), "sid=secret", " "); err == nil {
		t.Fatal("CreateAndJoin accepted an empty peer id")
	}
}

func testConfig(server *httptest.Server) Config {
	return Config{
		AppID:           "app-id",
		APIVersion:      "5.282",
		AppVersion:      "1.1",
		ProtocolVersion: "5",
		HTTPClient:      server.Client(),
		Endpoints: Endpoints{
			WebTokenURL:     server.URL + "/web-token",
			CallSettingsURL: server.URL + "/settings",
			CallTokenURL:    server.URL + "/call-token",
			CallStartURL:    server.URL + "/calls/start",
		},
	}
}

func assertBearer(t *testing.T, r *http.Request, want string) {
	t.Helper()
	if got := r.Header.Get("Authorization"); got != "Bearer "+want {
		t.Errorf("Authorization = %q, want bearer token", got)
	}
}

func writeJSON(t *testing.T, w http.ResponseWriter, body string) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if _, err := w.Write([]byte(body)); err != nil {
		t.Fatalf("write response: %v", err)
	}
}

func serverURL(r *http.Request) string {
	return "http://" + r.Host
}
