import SwiftUI

struct ContentView: View {
    @EnvironmentObject var proxyManager: ProxyManager
    @State private var showSettings = false

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 14) {
                    HeaderCard()

                    LinkCard()
                        .environmentObject(proxyManager)

                    if let captchaURL = proxyManager.captchaURL, let url = URL(string: captchaURL) {
                        CaptchaWebView(url: url)
                            .frame(minHeight: 320)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .padding(.horizontal)
                    }

                    if proxyManager.status == .tunnelConnected {
                        ProxyCard()
                            .environmentObject(proxyManager)
                    }

                    VPNCard()
                        .environmentObject(proxyManager)

                    if proxyManager.showLogs && proxyManager.captchaURL == nil {
                        LogsCard(logs: proxyManager.logs)
                    }
                }
                .padding(.vertical, 16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    StatusChip(status: proxyManager.status, errorMessage: proxyManager.errorMessage, statusText: proxyManager.statusText, tunnelMode: proxyManager.tunnelMode)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showSettings = true }) {
                        Image(systemName: "slider.horizontal.3")
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
                    .environmentObject(proxyManager)
            }
            .overlay(alignment: .bottom) {
                if let toast = proxyManager.toastMessage {
                    ToastView(text: toast)
                        .padding(.bottom, 28)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .animation(.easeInOut(duration: 0.25), value: proxyManager.toastMessage)
                }
            }
        }
        .onTapGesture {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        .onAppear { proxyManager.refreshSystemVPNStatus() }
    }
}

struct HeaderCard: View {
    var body: some View {
        CardView {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(LinearGradient(colors: [.purple, .blue], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 48, height: 48)
                    Image(systemName: "point.3.connected.trianglepath.dotted")
                        .foregroundColor(.white)
                        .font(.title3)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Whitelist Bypass")
                        .font(.title3)
                        .fontWeight(.semibold)
                    Text("SOCKS proxy over call transport")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }
        }
    }
}

struct LinkCard: View {
    @EnvironmentObject var proxyManager: ProxyManager

    var body: some View {
        CardView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Call room")
                    .font(.headline)
                ZStack(alignment: .trailing) {
                    TextField(NSLocalizedString("hint_call_link", comment: ""), text: $proxyManager.callUrl)
                        .textFieldStyle(.plain)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        .keyboardType(.URL)
                        .padding(12)
                        .padding(.trailing, proxyManager.callUrl.isEmpty ? 0 : 28)
                        .background(Color(.secondarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                    if !proxyManager.callUrl.isEmpty {
                        Button(action: { proxyManager.callUrl = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .padding(.trailing, 10)
                    }
                }
                Text("WB room links are converted to wbstream:// automatically.")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button(action: {
                    if proxyManager.isRunning {
                        proxyManager.resetAll()
                    } else {
                        proxyManager.connect()
                    }
                }) {
                    Label(proxyManager.isRunning ? NSLocalizedString("btn_stop", comment: "") : NSLocalizedString("btn_go", comment: ""), systemImage: proxyManager.isRunning ? "stop.fill" : "play.fill")
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(proxyManager.isRunning ? .red : .green)
            }
        }
    }
}

struct ProxyCard: View {
    @EnvironmentObject var proxyManager: ProxyManager

    var body: some View {
        CardView {
            VStack(alignment: .leading, spacing: 12) {
                Label("Proxy ready", systemImage: "checkmark.seal.fill")
                    .font(.headline)
                    .foregroundColor(.green)
                ProxyInfoView(proxyUrl: proxyManager.socksUrl, onCopy: proxyManager.copyProxyUrl)
                HStack {
                    Button(action: { proxyManager.openHappProxy() }) {
                        Label(NSLocalizedString("btn_add_to_happ", comment: ""), systemImage: "plus.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.purple)

                    Button(action: { proxyManager.openStreisandProxy() }) {
                        Label(NSLocalizedString("btn_add_to_streisand", comment: ""), systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(.orange)
                }

                Button(action: { proxyManager.openTelegramProxy() }) {
                    Label("Telegram SOCKS", systemImage: "paperplane.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.blue)
            }
        }
    }
}

struct VPNCard: View {
    @EnvironmentObject var proxyManager: ProxyManager

    var body: some View {
        CardView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label(proxyManager.vpnAvailable ? "VPN extension" : "Proxy-only build", systemImage: proxyManager.vpnAvailable ? "shield.lefthalf.filled" : "bolt.horizontal.circle")
                        .font(.headline)
                    Spacer()
                    Text(proxyManager.vpnAvailable ? "optional" : "AltStore friendly")
                        .font(.caption2)
                        .fontWeight(.medium)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color(.tertiarySystemGroupedBackground))
                        .clipShape(Capsule())
                }
                Text(proxyManager.vpnAvailable ? proxyManager.vpnStatusText : "This build does not include PacketTunnel, so iOS VPN settings will not appear.")
                    .font(.caption)
                    .foregroundColor(.secondary)

                if proxyManager.vpnAvailable {
                    HStack {
                        Button("Install") { proxyManager.installSystemVPNProfile() }
                            .buttonStyle(.bordered)
                        Button("Start") { proxyManager.startSystemVPN() }
                            .buttonStyle(.borderedProminent)
                        Button("Stop") { proxyManager.stopSystemVPN() }
                            .buttonStyle(.bordered)
                            .tint(.red)
                    }
                }
            }
        }
    }
}

struct StatusChip: View {
    let status: ProxyStatus
    let errorMessage: String
    let statusText: String?
    let tunnelMode: TunnelMode

    var statusColor: Color {
        if statusText != nil { return .yellow }
        switch status {
        case .idle, .ready: return .gray
        case .connecting, .reconnecting: return .yellow
        case .tunnelConnected: return .green
        case .tunnelLost: return .orange
        case .error: return .red
        }
    }

    var displayText: String {
        let statusLabel: String
        if let text = statusText { statusLabel = text }
        else if !errorMessage.isEmpty { statusLabel = errorMessage }
        else { statusLabel = status.displayLabel }
        return "\(tunnelMode.label) · \(statusLabel)"
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
            Text(displayText)
                .font(.subheadline)
                .fontWeight(.medium)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(Capsule())
    }
}

struct ProxyInfoView: View {
    let proxyUrl: String
    let onCopy: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(proxyUrl)
                .font(.system(.caption, design: .monospaced))
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            Button(action: onCopy) {
                Image(systemName: "doc.on.doc")
            }
        }
        .padding(10)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct LogsCard: View {
    let logs: [String]

    var body: some View {
        CardView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Logs")
                    .font(.headline)
                LogView(logs: logs)
                    .frame(minHeight: 140, maxHeight: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
    }
}

struct CardView<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: Color.black.opacity(0.05), radius: 10, x: 0, y: 4)
            .padding(.horizontal)
    }
}

struct ToastView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .fontWeight(.medium)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
    }
}

struct LogView: View {
    let logs: [String]
    @State private var userScrolledUp = false

    var body: some View {
        ScrollViewReader { scrollProxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(logs.indices, id: \.self) { index in
                        Text(logs[index])
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundColor(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(index)
                    }
                }
                .padding(8)
            }
            .background(Color(.secondarySystemGroupedBackground))
            .simultaneousGesture(DragGesture().onChanged { _ in
                userScrolledUp = true
            })
            .onChange(of: logs.count) { _ in
                if !userScrolledUp, let last = logs.indices.last {
                    scrollProxy.scrollTo(last, anchor: .bottom)
                }
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var proxyManager: ProxyManager
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section(NSLocalizedString("settings_tunnel", comment: "")) {
                    Picker(NSLocalizedString("settings_tunnel_mode", comment: ""), selection: $proxyManager.tunnelMode) {
                        Text(NSLocalizedString("settings_tunnel_dc", comment: "")).tag(TunnelMode.dc)
                        Text(NSLocalizedString("settings_tunnel_video", comment: "")).tag(TunnelMode.video)
                    }
                }

                Section(NSLocalizedString("settings_proxy", comment: "")) {
                    Picker(NSLocalizedString("settings_auth_mode", comment: ""), selection: $proxyManager.socksAuthMode) {
                        Text(NSLocalizedString("settings_auth_auto", comment: "")).tag(SocksAuthMode.auto)
                        Text(NSLocalizedString("settings_auth_manual", comment: "")).tag(SocksAuthMode.manual)
                    }

                    if proxyManager.socksAuthMode == .manual {
                        TextField(NSLocalizedString("hint_username", comment: ""), text: $proxyManager.manualSocksUser)
                            .autocapitalization(.none)
                        SecureField(NSLocalizedString("hint_password", comment: ""), text: $proxyManager.manualSocksPass)
                    }
                }

                Section(NSLocalizedString("settings_display", comment: "")) {
                    TextField(NSLocalizedString("hint_display_name", comment: ""), text: $proxyManager.displayName)
                    Toggle(NSLocalizedString("settings_show_logs", comment: ""), isOn: $proxyManager.showLogs)
                }

                Section(NSLocalizedString("settings_vp8_pacing", comment: "")) {
                    Stepper("\(NSLocalizedString("settings_vp8_fps", comment: "")): \(proxyManager.vp8Fps)", value: $proxyManager.vp8Fps, in: 1...30)
                    Stepper("\(NSLocalizedString("settings_vp8_batch", comment: "")): \(proxyManager.vp8Batch)", value: $proxyManager.vp8Batch, in: 1...16)
                    Toggle(isOn: $proxyManager.dualTrack) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(NSLocalizedString("vp8_dual_track_title", comment: ""))
                            Text(NSLocalizedString("vp8_dual_track_sub", comment: ""))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(NSLocalizedString("settings_title", comment: ""))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("btn_done", comment: "")) { dismiss() }
                }
            }
        }
    }
}
