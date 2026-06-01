import CryptoKit
import Foundation

enum WtBusCrypto {
    private static let b64url = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
    private static let custom64 = Array("АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдежзийклмнопрстуфхцчшщъыьэюя")

    static func decryptEnvelope(prefix: String, kid: String, encoded: String) -> [String: Any]? {
        guard !WtBusSecrets.keyB64.isEmpty,
              let keyData = decodeBase64URL(WtBusSecrets.keyB64),
              keyData.count == 32,
              let blob = decodeCustom(encoded),
              blob.count >= 12 + 16 else { return nil }
        let nonceData = blob.prefix(12)
        let cipherAndTag = blob.dropFirst(12)
        guard cipherAndTag.count >= 16 else { return nil }
        let ciphertext = cipherAndTag.dropLast(16)
        let tag = cipherAndTag.suffix(16)
        do {
            let key = SymmetricKey(data: keyData)
            let nonce = try AES.GCM.Nonce(data: nonceData)
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            let aad = Data("\(prefix).\(kid)".utf8)
            let plain = try AES.GCM.open(box, using: key, authenticating: aad)
            return try JSONSerialization.jsonObject(with: plain) as? [String: Any]
        } catch {
            return nil
        }
    }

    private static func decodeCustom(_ text: String) -> Data? {
        var ascii = ""
        for ch in text {
            guard let idx = custom64.firstIndex(of: ch) else { return nil }
            ascii.append(b64url[idx])
        }
        return decodeBase64URL(ascii)
    }

    private static func decodeBase64URL(_ text: String) -> Data? {
        var value = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let pad = value.count % 4
        if pad > 0 { value += String(repeating: "=", count: 4 - pad) }
        return Data(base64Encoded: value)
    }
}
