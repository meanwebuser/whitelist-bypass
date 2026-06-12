package telemost

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"

	"whitelist-bypass/relay/common"
)

type RuntimeConfig struct {
	AppVersion string
	SDKVersion string
}

func FetchRuntimeConfig() (RuntimeConfig, error) {
	var cfg RuntimeConfig

	page, err := common.HttpGet("https://telemost.yandex.ru/")
	if err != nil {
		return cfg, fmt.Errorf("failed to fetch telemost.yandex.ru: %w", err)
	}

	stateRe := regexp.MustCompile(`<script[^>]*id="preloaded-state"[^>]*>([\s\S]*?)</script>`)
	stateMatch := stateRe.FindSubmatch(page)
	if stateMatch == nil {
		return cfg, fmt.Errorf("preloaded-state not found in page")
	}
	var state struct {
		Config struct {
			AppVersion string `json:"appVersion"`
		} `json:"config"`
		AppVersion string `json:"appVersion"`
	}
	if err := json.Unmarshal(stateMatch[1], &state); err != nil {
		return cfg, fmt.Errorf("failed to parse preloaded-state: %w", err)
	}
	cfg.AppVersion = state.Config.AppVersion
	if cfg.AppVersion == "" {
		cfg.AppVersion = state.AppVersion
	}
	if cfg.AppVersion == "" {
		return cfg, fmt.Errorf("appVersion not found in preloaded-state")
	}

	bundleRe := regexp.MustCompile(`https://telemost\.yastatic\.net/s3/telemost/_/main\.\w+\.[a-f0-9]+\.js`)
	bundleURL := bundleRe.FindString(string(page))
	if bundleURL == "" {
		return cfg, fmt.Errorf("main bundle URL not found in page")
	}
	bundle, err := common.HttpGet(bundleURL)
	if err != nil {
		return cfg, fmt.Errorf("failed to fetch bundle: %w", err)
	}
	sdkVerRe := regexp.MustCompile(`goloom-sdk\.(\d+\.\d+\.\d+)\.js`)
	if m := sdkVerRe.FindSubmatch(bundle); m != nil {
		cfg.SDKVersion = string(m[1])
	} else {
		return cfg, fmt.Errorf("goloom SDK version not found in bundle")
	}
	return cfg, nil
}

func CreateConference(cookieStr string) (string, error) {
	rc, err := FetchRuntimeConfig()
	if err != nil {
		return "", err
	}
	client := Client{Cookie: cookieStr, AppVersion: rc.AppVersion}
	body, status, err := client.Do(http.MethodPost, "/conferences?next_gen_media_platform_allowed=true", struct{}{})
	if err != nil {
		return "", err
	}
	if status != http.StatusOK && status != http.StatusCreated {
		return "", fmt.Errorf("create conference: status %d: %s", status, string(body))
	}
	var conf struct {
		URI string `json:"uri"`
	}
	if err := json.Unmarshal(body, &conf); err != nil {
		return "", fmt.Errorf("create conference decode: %w", err)
	}
	if strings.TrimSpace(conf.URI) == "" {
		return "", fmt.Errorf("empty conference URI: %s", string(body))
	}
	return conf.URI, nil
}
