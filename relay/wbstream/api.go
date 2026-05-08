package wbstream

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"whitelist-bypass/relay/common"
)

const (
	APIBase = "https://stream.wb.ru"
	WSURL   = "wss://wbstream01-el.wb.ru:7880"
	Origin  = "https://stream.wb.ru"
)

type guestRegisterRequest struct {
	DisplayName string         `json:"displayName"`
	Device      guestDeviceCfg `json:"device"`
}

type guestDeviceCfg struct {
	DeviceName string `json:"deviceName"`
	DeviceType string `json:"deviceType"`
}

type guestRegisterResponse struct {
	AccessToken string `json:"accessToken"`
}

type createRoomRequest struct {
	RoomType    string `json:"roomType"`
	RoomPrivacy string `json:"roomPrivacy"`
}

type createRoomResponse struct {
	RoomID string `json:"roomId"`
}

type tokenResponse struct {
	RoomToken string `json:"roomToken"`
}

func httpDo(client *http.Client, req *http.Request) (*http.Response, error) {
	req.Header.Set("User-Agent", common.UserAgent)
	if client == nil {
		client = http.DefaultClient
	}
	return client.Do(req)
}

func RegisterGuest(client *http.Client, displayName string) (string, error) {
	body, _ := json.Marshal(guestRegisterRequest{
		DisplayName: displayName,
		Device: guestDeviceCfg{
			DeviceName: "Linux",
			DeviceType: "PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP",
		},
	})
	req, err := http.NewRequest(http.MethodPost, APIBase+"/auth/api/v1/auth/user/guest-register", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := httpDo(client, req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("guest-register: status %d: %s", resp.StatusCode, string(raw))
	}

	var r guestRegisterResponse
	if err := json.Unmarshal(raw, &r); err != nil {
		return "", fmt.Errorf("guest-register decode: %w", err)
	}
	return r.AccessToken, nil
}

func CreateRoom(client *http.Client, accessToken string) (string, error) {
	body, _ := json.Marshal(createRoomRequest{
		RoomType:    "ROOM_TYPE_ALL_ON_SCREEN",
		RoomPrivacy: "ROOM_PRIVACY_FREE",
	})
	req, err := http.NewRequest(http.MethodPost, APIBase+"/api-room/api/v2/room", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := httpDo(client, req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return "", fmt.Errorf("create-room: status %d: %s", resp.StatusCode, string(raw))
	}

	var r createRoomResponse
	if err := json.Unmarshal(raw, &r); err != nil {
		return "", fmt.Errorf("create-room decode: %w", err)
	}
	return r.RoomID, nil
}

func JoinRoom(client *http.Client, accessToken, roomID string) error {
	url := fmt.Sprintf("%s/api-room/api/v1/room/%s/join", APIBase, roomID)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader([]byte("{}")))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := httpDo(client, req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		raw, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("join-room: status %d: %s", resp.StatusCode, string(raw))
	}
	return nil
}

func GetRoomToken(client *http.Client, accessToken, roomID, displayName string) (string, error) {
	url := fmt.Sprintf("%s/api-room-manager/api/v1/room/%s/token?deviceType=PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP&displayName=%s",
		APIBase, roomID, displayName)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := httpDo(client, req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("get-token: status %d: %s", resp.StatusCode, string(raw))
	}

	var r tokenResponse
	if err := json.Unmarshal(raw, &r); err != nil {
		return "", fmt.Errorf("get-token decode: %w", err)
	}
	return r.RoomToken, nil
}

func AuthAndGetToken(client *http.Client, roomID, displayName string) (string, string, string, error) {
	accessToken, err := RegisterGuest(client, displayName)
	if err != nil {
		return "", "", "", fmt.Errorf("register guest: %w", err)
	}
	if roomID == "" {
		roomID, err = CreateRoom(client, accessToken)
		if err != nil {
			return "", "", "", fmt.Errorf("create room: %w", err)
		}
	}
	if err := JoinRoom(client, accessToken, roomID); err != nil {
		return "", "", "", fmt.Errorf("join room: %w", err)
	}
	roomToken, err := GetRoomToken(client, accessToken, roomID, displayName)
	if err != nil {
		return "", "", "", fmt.Errorf("get token: %w", err)
	}
	return roomID, roomToken, accessToken, nil
}

func KickParticipant(client *http.Client, accessToken, roomID, participantID string) error {
	if client == nil {
		client = http.DefaultClient
	}
	url := fmt.Sprintf("%s/api-room-manager/api/v1/room/%s/participant/%s/kick", APIBase, roomID, participantID)
	req, err := http.NewRequest("DELETE", url, strings.NewReader("{}"))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("User-Agent", common.UserAgent)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return fmt.Errorf("kick %s -> %d %s", participantID, resp.StatusCode, string(body))
	}
	return nil
}
