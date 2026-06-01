import Foundation

// Build scripts overwrite this file from WTBUS_KEY_B64 / VK_BOT_TOKEN env values.
// Empty values keep the app buildable and make private/encrypted discovery a no-op.
enum WtBusSecrets {
    static let keyB64 = ""
    static let keyID = "k1"
    static let vkBotToken = ""
    static let vkBotPeerID = "46887791"
}
