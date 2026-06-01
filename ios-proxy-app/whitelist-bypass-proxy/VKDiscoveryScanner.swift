import Foundation

struct DiscoveryRoom: Identifiable {
    let id: String
    let status: String
    let room: String
    let creator: String
    let createdAt: Int64
    let expiresAt: Int64
    let seq: Int64

    var order: Int64 { seq > 0 ? seq : createdAt }
    var isFree: Bool {
        status == "free" && !room.isEmpty && expiresAt > Int64(Date().timeIntervalSince1970)
    }
}

final class VKDiscoveryScanner {
    private let groupId = "237416141"
    private let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"
    private var urls: [URL] {
        [
            "https://m.vk.ru/club\(groupId)",
            "https://m.vk.com/club\(groupId)",
            "https://vk.ru/club\(groupId)",
            "https://vk.com/club\(groupId)",
        ].compactMap(URL.init(string:))
    }

    func scan(completion: @escaping (_ rooms: [DiscoveryRoom], _ source: String?) -> Void) {
        scan(urls: urls, firstSource: nil, completion: completion)
    }

    private func scan(urls: [URL], firstSource: String?, completion: @escaping ([DiscoveryRoom], String?) -> Void) {
        guard let url = urls.first else {
            completion([], firstSource)
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 10
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
            guard let self = self else { return }
            let source = firstSource ?? url.absoluteString
            if let data = data, let html = String(data: data, encoding: .utf8) {
                let rooms = self.parse(html: html)
                if !rooms.isEmpty {
                    completion(rooms, source)
                    return
                }
            }
            self.scan(urls: Array(urls.dropFirst()), firstSource: source, completion: completion)
        }.resume()
    }

    private func parse(html: String) -> [DiscoveryRoom] {
        let text = normalize(html)
        var rooms: [DiscoveryRoom] = []
        rooms.append(contentsOf: parsePayloads(text))
        rooms.append(contentsOf: parseLegacyRooms(text))
        var unique: [String: DiscoveryRoom] = [:]
        for room in rooms { unique[room.id] = room }
        return unique.values.sorted { $0.order > $1.order }
    }

    private func parsePayloads(_ text: String) -> [DiscoveryRoom] {
        guard let regex = try? NSRegularExpression(pattern: "wt1\\.([A-Za-z0-9_-]{24,})") else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { match in
            guard match.numberOfRanges >= 2 else { return nil }
            let encoded = ns.substring(with: match.range(at: 1))
            guard let data = base64URLDecode(encoded),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            let status = (json["status"] as? String) ?? "free"
            let room = (json["room"] as? String) ?? ""
            let creator = (json["creator"] as? String) ?? ""
            let createdAt = int64(json["created_at"]) ?? int64(json["createdAt"]) ?? 0
            let expiresAt = int64(json["expires_at"]) ?? int64(json["expiresAt"]) ?? Int64.max
            let seq = int64(json["seq"]) ?? 0
            let streamId = (json["stream_id"] as? String) ?? (json["streamId"] as? String) ?? room
            return DiscoveryRoom(id: streamId.isEmpty ? room : streamId, status: status, room: room, creator: creator, createdAt: createdAt, expiresAt: expiresAt, seq: seq)
        }
    }

    private func parseLegacyRooms(_ text: String) -> [DiscoveryRoom] {
        guard let regex = try? NSRegularExpression(pattern: "wbstream://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+") else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { match in
            let room = ns.substring(with: match.range)
            return DiscoveryRoom(id: room, status: "free", room: room, creator: "legacy", createdAt: 0, expiresAt: Int64.max, seq: 0)
        }
    }

    private func normalize(_ raw: String) -> String {
        raw
            .replacingOccurrences(of: "\\u002F", with: "/")
            .replacingOccurrences(of: "\\/", with: "/")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#34;", with: "\"")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    private func int64(_ value: Any?) -> Int64? {
        if let n = value as? Int64 { return n }
        if let n = value as? Int { return Int64(n) }
        if let n = value as? Double { return Int64(n) }
        if let s = value as? String { return Int64(s) }
        return nil
    }

    private func base64URLDecode(_ text: String) -> Data? {
        var s = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let pad = s.count % 4
        if pad > 0 { s += String(repeating: "=", count: 4 - pad) }
        return Data(base64Encoded: s)
    }
}
