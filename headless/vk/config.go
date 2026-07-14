package main

import "log"

type VKConfig struct {
	AppID           string
	APIVersion      string
	SDKVersion      string
	AppVersion      string
	ProtocolVersion string
}

func fetchConfig() (VKConfig, error) {
	cfg := VKConfig{
		AppID:           "6287487",
		APIVersion:      "5.282",
		SDKVersion:      "2.8.11-beta.4",
		AppVersion:      "1.1",
		ProtocolVersion: "5",
	}
	log.Printf("[config] app_id=%s api=%s sdk=%s app=%s proto=%s",
		cfg.AppID, cfg.APIVersion, cfg.SDKVersion, cfg.AppVersion, cfg.ProtocolVersion)
	return cfg, nil
}
