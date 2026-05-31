import Foundation
import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var isReadingPackets = false

    override func startTunnel(options: [String : NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        NSLog("[PacketTunnel] startTunnel options=\(options ?? [:])")

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "10.64.0.1")
        settings.mtu = NSNumber(value: 1500)

        let ipv4 = NEIPv4Settings(addresses: ["10.64.0.2"], subnetMasks: ["255.255.255.0"])
        // Safety MVP: do not install a default route until packetFlow -> tun2socks is wired.
        // Switching this to [NEIPv4Route.default()] without forwarding packets will blackhole traffic.
        ipv4.includedRoutes = []
        settings.ipv4Settings = ipv4

        let dns = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])
        dns.matchDomains = []
        settings.dnsSettings = dns

        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error = error {
                NSLog("[PacketTunnel] setTunnelNetworkSettings error: \(error)")
                completionHandler(error)
                return
            }
            self?.isReadingPackets = true
            self?.readPacketsLoop()
            NSLog("[PacketTunnel] started scaffold VPN; packet forwarding is not enabled yet")
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        NSLog("[PacketTunnel] stopTunnel reason=\(reason.rawValue)")
        isReadingPackets = false
        completionHandler()
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        let text = String(data: messageData, encoding: .utf8) ?? ""
        NSLog("[PacketTunnel] app message: \(text)")
        completionHandler?("ok".data(using: .utf8))
    }

    private func readPacketsLoop() {
        guard isReadingPackets else { return }
        packetFlow.readPackets { [weak self] packets, protocols in
            guard let self else { return }
            if !packets.isEmpty {
                NSLog("[PacketTunnel] received \(packets.count) packets; drop in scaffold mode")
            }
            self.readPacketsLoop()
        }
    }
}
