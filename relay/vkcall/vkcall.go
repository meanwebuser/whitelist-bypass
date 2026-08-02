// Package vkcall implements the HTTP authentication, creation, and join flow
// used by VK Calls. It deliberately neither logs nor stores cookies or tokens.
package vkcall

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"whitelist-bypass/relay/common"
)

const (
	defaultAppVersion      = "1.1"
	defaultProtocolVersion = "5"
)

// Endpoints allows callers and tests to replace every initial VK HTTP endpoint.
// The call service endpoint is returned by VK in the call-token response.
type Endpoints struct {
	WebTokenURL     string
	CallSettingsURL string
	CallTokenURL    string
	CallStartURL    string
}

// DefaultEndpoints returns the production endpoint set.
func DefaultEndpoints() Endpoints {
	return Endpoints{
		WebTokenURL:     "https://login.vk.ru/?act=web_token",
		CallSettingsURL: "https://api.vk.ru/method/calls.getSettings",
		CallTokenURL:    "https://api.vk.ru/method/messages.getCallToken",
		CallStartURL:    "https://api.vk.ru/method/calls.start",
	}
}

// Config is the non-secret configuration for a Client. HTTPClient and
// Endpoints can be injected for tests or private routing.
type Config struct {
	AppID           string
	APIVersion      string
	AppVersion      string
	ProtocolVersion string
	HTTPClient      *http.Client
	Endpoints       Endpoints
}

// Client executes VK Call HTTP requests. It does not retain request secrets.
type Client struct {
	config Config
}

// New validates and normalizes configuration.
func New(config Config) (*Client, error) {
	config.AppID = strings.TrimSpace(config.AppID)
	config.APIVersion = strings.TrimSpace(config.APIVersion)
	if config.AppID == "" || config.APIVersion == "" {
		return nil, fmt.Errorf("vkcall: app ID and API version are required")
	}
	if config.AppVersion == "" {
		config.AppVersion = defaultAppVersion
	}
	if config.ProtocolVersion == "" {
		config.ProtocolVersion = defaultProtocolVersion
	}
	config.Endpoints = mergeEndpoints(config.Endpoints)
	return &Client{config: config}, nil
}

// CallInfo contains the call and authenticated-session values returned by VK.
// It contains credentials; callers must keep it out of logs and diagnostics.
type CallInfo struct {
	CallID     string
	JoinLink   string
	ShortLink  string
	OKJoinLink string

	SessionKey      string
	ApplicationKey  string
	APIBaseURL      string
	AnonymToken     string
	AppVersion      string
	ProtocolVersion string

	TurnServer TurnServer
	StunServer StunServer
	WSEndpoint string
	WtEndpoint string
}

// JoinerAuthParams is the direct field mapping for
// joiner.VKHeadlessAuthParams. Keeping this independent avoids importing the
// Pion joiner from a small HTTP-only package.
type JoinerAuthParams struct {
	SessionKey      string `json:"sessionKey"`
	ApplicationKey  string `json:"applicationKey"`
	APIBaseURL      string `json:"apiBaseURL"`
	JoinLink        string `json:"joinLink"`
	AnonymToken     string `json:"anonymToken"`
	AppVersion      string `json:"appVersion"`
	ProtocolVersion string `json:"protocolVersion"`
}

// JoinerAuth returns values that map one-to-one to the required fields of
// joiner.VKHeadlessAuthParams. The joiner-specific tunnel tuning fields remain
// the caller's responsibility.
func (info CallInfo) JoinerAuth() JoinerAuthParams {
	return JoinerAuthParams{
		SessionKey:      info.SessionKey,
		ApplicationKey:  info.ApplicationKey,
		APIBaseURL:      info.APIBaseURL,
		JoinLink:        info.OKJoinLink,
		AnonymToken:     info.AnonymToken,
		AppVersion:      info.AppVersion,
		ProtocolVersion: info.ProtocolVersion,
	}
}

// TurnServer is the TURN configuration returned by VK.
type TurnServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

// StunServer is the STUN configuration returned by VK.
type StunServer struct {
	URLs []string `json:"urls"`
}

// JoinExisting joins a supplied VK call link. cookieHeader is sent only to the
// web-token endpoint and is never stored by Client.
func (c *Client) JoinExisting(ctx context.Context, cookieHeader, vkLink string) (*CallInfo, error) {
	joinToken := extractJoinToken(vkLink)
	if joinToken == "" {
		return nil, fmt.Errorf("vkcall: join link has no token")
	}
	info, err := c.authenticateAndJoin(ctx, cookieHeader, joinToken)
	if err != nil {
		return nil, err
	}
	info.JoinLink = strings.TrimSpace(vkLink)
	return info, nil
}

// CreateAndJoin creates a call for peerID, then joins it with the same flow as
// JoinExisting.
func (c *Client) CreateAndJoin(ctx context.Context, cookieHeader, peerID string) (*CallInfo, error) {
	peerID = strings.TrimSpace(peerID)
	if peerID == "" {
		return nil, fmt.Errorf("vkcall: peer ID is required")
	}
	vkToken, err := c.webToken(ctx, cookieHeader)
	if err != nil {
		return nil, err
	}

	var response struct {
		Response struct {
			CallID           string `json:"call_id"`
			JoinLink         string `json:"join_link"`
			OKJoinLink       string `json:"ok_join_link"`
			ShortCredentials struct {
				LinkWithPassword string `json:"link_with_password"`
			} `json:"short_credentials"`
		} `json:"response"`
	}
	if err := c.postJSON(ctx, c.config.Endpoints.CallStartURL, url.Values{
		"v":       {c.config.APIVersion},
		"peer_id": {peerID},
	}, bearerHeader(vkToken), "calls.start", &response); err != nil {
		return nil, err
	}
	if response.Response.CallID == "" || response.Response.OKJoinLink == "" {
		return nil, fmt.Errorf("vkcall: calls.start response is incomplete")
	}

	info, err := c.authenticateAndJoin(ctx, cookieHeader, response.Response.OKJoinLink)
	if err != nil {
		return nil, err
	}
	info.CallID = response.Response.CallID
	info.JoinLink = response.Response.JoinLink
	info.ShortLink = response.Response.ShortCredentials.LinkWithPassword
	return info, nil
}

func (c *Client) authenticateAndJoin(ctx context.Context, cookieHeader, joinToken string) (*CallInfo, error) {
	vkToken, err := c.webToken(ctx, cookieHeader)
	if err != nil {
		return nil, err
	}

	var settings struct {
		Response struct {
			Settings struct {
				PublicKey string `json:"public_key"`
			} `json:"settings"`
		} `json:"response"`
	}
	if err := c.postJSON(ctx, c.config.Endpoints.CallSettingsURL, url.Values{
		"v": {c.config.APIVersion},
	}, bearerHeader(vkToken), "calls.getSettings", &settings); err != nil {
		return nil, err
	}
	appKey := settings.Response.Settings.PublicKey
	if appKey == "" {
		return nil, fmt.Errorf("vkcall: calls.getSettings response is incomplete")
	}

	var callToken struct {
		Response struct {
			Token      string `json:"token"`
			APIBaseURL string `json:"api_base_url"`
		} `json:"response"`
	}
	if err := c.postJSON(ctx, c.config.Endpoints.CallTokenURL, url.Values{
		"v":   {c.config.APIVersion},
		"env": {"production"},
	}, bearerHeader(vkToken), "messages.getCallToken", &callToken); err != nil {
		return nil, err
	}
	if callToken.Response.Token == "" || callToken.Response.APIBaseURL == "" {
		return nil, fmt.Errorf("vkcall: messages.getCallToken response is incomplete")
	}
	apiBaseURL := normalizeAPIBaseURL(callToken.Response.APIBaseURL)

	sessionData, err := json.Marshal(map[string]any{
		"device_id":      "headless-go-1",
		"client_version": c.config.AppVersion,
		"client_type":    "SDK_JS",
		"auth_token":     callToken.Response.Token,
		"version":        3,
	})
	if err != nil {
		return nil, fmt.Errorf("vkcall: encode anonymous session: %w", err)
	}
	var anonymous struct {
		SessionKey string `json:"session_key"`
	}
	if err := c.postJSON(ctx, apiBaseURL, url.Values{
		"method":          {"auth.anonymLogin"},
		"application_key": {appKey},
		"format":          {"json"},
		"session_data":    {string(sessionData)},
	}, nil, "auth.anonymLogin", &anonymous); err != nil {
		return nil, err
	}
	if anonymous.SessionKey == "" {
		return nil, fmt.Errorf("vkcall: auth.anonymLogin response is incomplete")
	}

	mediaSettings, err := json.Marshal(map[string]bool{
		"isAudioEnabled":         false,
		"isVideoEnabled":         true,
		"isScreenSharingEnabled": false,
	})
	if err != nil {
		return nil, fmt.Errorf("vkcall: encode media settings: %w", err)
	}
	var joined struct {
		Endpoint   string     `json:"endpoint"`
		WtEndpoint string     `json:"wt_endpoint"`
		TurnServer TurnServer `json:"turn_server"`
		StunServer StunServer `json:"stun_server"`
	}
	if err := c.postJSON(ctx, apiBaseURL, url.Values{
		"method":          {"vchat.joinConversationByLink"},
		"session_key":     {anonymous.SessionKey},
		"application_key": {appKey},
		"format":          {"json"},
		"joinLink":        {joinToken},
		"isVideo":         {"true"},
		"isAudio":         {"false"},
		"mediaSettings":   {string(mediaSettings)},
	}, nil, "vchat.joinConversationByLink", &joined); err != nil {
		return nil, err
	}
	if joined.Endpoint == "" {
		return nil, fmt.Errorf("vkcall: vchat.joinConversationByLink response is incomplete")
	}

	return &CallInfo{
		OKJoinLink:      joinToken,
		SessionKey:      anonymous.SessionKey,
		ApplicationKey:  appKey,
		APIBaseURL:      apiBaseURL,
		AnonymToken:     callToken.Response.Token,
		AppVersion:      c.config.AppVersion,
		ProtocolVersion: c.config.ProtocolVersion,
		TurnServer:      joined.TurnServer,
		StunServer:      joined.StunServer,
		WSEndpoint:      joined.Endpoint,
		WtEndpoint:      joined.WtEndpoint,
	}, nil
}

func (c *Client) webToken(ctx context.Context, cookieHeader string) (string, error) {
	var response struct {
		Data struct {
			AccessToken string `json:"access_token"`
		} `json:"data"`
	}
	if err := c.getJSON(ctx, c.config.Endpoints.WebTokenURL, url.Values{
		"version": {"1"},
		"app_id":  {c.config.AppID},
	}, map[string]string{"Cookie": cookieHeader}, "web_token", &response); err != nil {
		return "", err
	}
	if response.Data.AccessToken == "" {
		return "", fmt.Errorf("vkcall: web_token response is incomplete")
	}
	return response.Data.AccessToken, nil
}

func (c *Client) getJSON(ctx context.Context, endpoint string, query url.Values, headers map[string]string, operation string, result any) error {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return fmt.Errorf("vkcall: %s request: %w", operation, err)
	}
	values := parsed.Query()
	for key, entries := range query {
		values[key] = append([]string(nil), entries...)
	}
	parsed.RawQuery = values.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, parsed.String(), nil)
	if err != nil {
		return fmt.Errorf("vkcall: %s request: %w", operation, err)
	}
	req.Header.Set("User-Agent", common.UserAgent)
	req.Header.Set("Origin", "https://vk.ru")
	req.Header.Set("Referer", "https://vk.ru/")
	for key, value := range headers {
		req.Header.Set(key, value)
	}

	client := c.config.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("vkcall: %s: %w", operation, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusMultipleChoices {
		return fmt.Errorf("vkcall: %s: unexpected HTTP status %d", operation, resp.StatusCode)
	}
	decoder := json.NewDecoder(io.LimitReader(resp.Body, 4<<20))
	if err := decoder.Decode(result); err != nil {
		return fmt.Errorf("vkcall: %s decode: %w", operation, err)
	}
	return nil
}

func (c *Client) postJSON(ctx context.Context, endpoint string, form url.Values, headers map[string]string, operation string, result any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("vkcall: %s request: %w", operation, err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", common.UserAgent)
	req.Header.Set("Origin", "https://vk.ru")
	req.Header.Set("Referer", "https://vk.ru/")
	for key, value := range headers {
		req.Header.Set(key, value)
	}

	client := c.config.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("vkcall: %s: %w", operation, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusMultipleChoices {
		return fmt.Errorf("vkcall: %s: unexpected HTTP status %d", operation, resp.StatusCode)
	}
	decoder := json.NewDecoder(io.LimitReader(resp.Body, 4<<20))
	if err := decoder.Decode(result); err != nil {
		return fmt.Errorf("vkcall: %s decode: %w", operation, err)
	}
	return nil
}

func mergeEndpoints(endpoints Endpoints) Endpoints {
	defaults := DefaultEndpoints()
	if endpoints.WebTokenURL == "" {
		endpoints.WebTokenURL = defaults.WebTokenURL
	}
	if endpoints.CallSettingsURL == "" {
		endpoints.CallSettingsURL = defaults.CallSettingsURL
	}
	if endpoints.CallTokenURL == "" {
		endpoints.CallTokenURL = defaults.CallTokenURL
	}
	if endpoints.CallStartURL == "" {
		endpoints.CallStartURL = defaults.CallStartURL
	}
	return endpoints
}

func bearerHeader(token string) map[string]string {
	return map[string]string{"Authorization": "Bearer " + token}
}

func normalizeAPIBaseURL(apiBaseURL string) string {
	apiBaseURL = strings.TrimRight(strings.TrimSpace(apiBaseURL), "/")
	if !strings.HasSuffix(apiBaseURL, "/fb.do") {
		apiBaseURL += "/fb.do"
	}
	return apiBaseURL
}

func extractJoinToken(link string) string {
	link = strings.TrimSpace(link)
	if link == "" {
		return ""
	}
	if parsed, err := url.Parse(link); err == nil && parsed.Scheme != "" {
		path := strings.Trim(parsed.Path, "/")
		if path != "" {
			parts := strings.Split(path, "/")
			return parts[len(parts)-1]
		}
	}
	if !strings.ContainsAny(link, "/?&=") {
		return link
	}
	parts := strings.Split(strings.TrimRight(link, "/"), "/")
	return parts[len(parts)-1]
}
