package main

import "encoding/json"

type kbAction struct {
	Type    string `json:"type"`
	Label   string `json:"label"`
	Payload string `json:"payload"`
}

type kbButton struct {
	Action kbAction `json:"action"`
}

type keyboard struct {
	OneTime bool         `json:"one_time"`
	Buttons [][]kbButton `json:"buttons"`
}

func mainMenuKeyboard() string {
	kb := keyboard{
		OneTime: false,
		Buttons: [][]kbButton{
			{
				{Action: kbAction{Type: "text", Label: "👻 VK", Payload: `{"cmd":"vk"}`}},
				{Action: kbAction{Type: "text", Label: "👻 Telemost", Payload: `{"cmd":"tm"}`}},
				{Action: kbAction{Type: "text", Label: "👻 WB Stream", Payload: `{"cmd":"wb"}`}},
			},
			{
				{Action: kbAction{Type: "text", Label: "📋 Active sessions", Payload: `{"cmd":"list"}`}},
			},
		},
	}
	data, _ := json.Marshal(kb)
	return string(data)
}

func sessionsKeyboard(sessions []*session) string {
	rows := make([][]kbButton, 0, len(sessions)+1)
	for _, s := range sessions {
		payload, _ := json.Marshal(map[string]string{"cmd": "close", "id": s.id})
		label := "👻 " + s.platform + " " + s.id + " 🟢"
		rows = append(rows, []kbButton{
			{Action: kbAction{Type: "text", Label: label, Payload: string(payload)}},
		})
	}
	rows = append(rows, []kbButton{
		{Action: kbAction{Type: "text", Label: "◀️ Back", Payload: `{"cmd":"menu"}`}},
	})
	kb := keyboard{OneTime: false, Buttons: rows}
	data, _ := json.Marshal(kb)
	return string(data)
}
