module headless-vk-bot

go 1.26.1

require whitelist-bypass/relay v0.0.0

require github.com/pion/transport/v4 v4.0.1 // indirect

replace whitelist-bypass/relay => ../../relay
