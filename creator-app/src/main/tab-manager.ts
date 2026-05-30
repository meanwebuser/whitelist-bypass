import { app } from 'electron';
import { spawn, ChildProcess } from 'child_process';
import { BrowserWindow, session } from 'electron';
import * as net from 'net';
import * as path from 'path';
import * as fs from 'fs/promises';
import {
  TabState,
  PortPair,
  TabListEntry,
  Platform,
  TunnelMode,
  RelayMode,
  CallStatus,
  HeadlessStartArgs,
  HeadlessMode,
  UpstreamProxy,
} from '../types';
import {
  INITIAL_PORT_BASE,
  IPC,
  RELAY_RESTART_DELAY_MS,
  SESSION_PARTITION,
  VK_COOKIE_DOMAINS,
  YANDEX_COOKIE_DOMAINS,
  DION_COOKIE_DOMAINS,
  WBSTREAM_COOKIE_DOMAINS,
  VK_LOGIN_URL,
  YANDEX_LOGIN_URL,
  DION_LOGIN_URL,
  WBSTREAM_LOGIN_URL,
  VK_AUTH_COOKIE,
  YANDEX_AUTH_COOKIE,
  DION_AUTH_COOKIE,
  WBSTREAM_AUTH_COOKIE,
  LOG_CAPTURE_SNIPPET,
} from '../constants';
import { BotManager } from '../bot/bot-manager';
import { buildStoredZip } from './util/zip';
import { resolveResourcePath, binaryName } from './util/paths';

export class TabManager {
  private tabs = new Map<string, TabState>();
  private callStatusCache = new Map<string, CallStatus>();
  private botTabIds = new Set<string>();
  private nextPortBase = INITIAL_PORT_BASE;
  private _mainWindow: BrowserWindow | null = null;
  private _botManager: BotManager | null = null;
  private relayPath: string;
  private headlessVKPath: string;
  private headlessTelemostPath: string;
  private headlessWBStreamPath: string;
  private headlessDionPath: string;
  private hooksDir: string;
  private upstreamProxy: UpstreamProxy = { socks: '', user: '', pass: '' };

  constructor() {
    this.relayPath = resolveResourcePath(
      path.join('relay', binaryName('relay')),
      binaryName('relay'),
    );
    this.headlessVKPath = resolveResourcePath(
      path.join('headless', 'vk', binaryName('headless-vk-creator')),
      binaryName('headless-vk-creator'),
    );
    this.headlessTelemostPath = resolveResourcePath(
      path.join('headless', 'telemost', binaryName('headless-telemost-creator')),
      binaryName('headless-telemost-creator'),
    );
    this.headlessWBStreamPath = resolveResourcePath(
      path.join('headless', 'wbstream', binaryName('headless-wbstream-creator')),
      binaryName('headless-wbstream-creator'),
    );
    this.headlessDionPath = resolveResourcePath(
      path.join('headless', 'dion', binaryName('headless-dion-creator')),
      binaryName('headless-dion-creator'),
    );
    this.hooksDir = app.isPackaged
      ? path.join(process.resourcesPath!, 'hooks')
      : path.join(__dirname, '..', '..', '..', 'hooks');
  }

  get mainWindow(): BrowserWindow | null {
    return this._mainWindow;
  }

  set mainWindow(w: BrowserWindow | null) {
    this._mainWindow = w;
  }

  get botManager(): BotManager | null {
    return this._botManager;
  }

  set botManager(bm: BotManager | null) {
    this._botManager = bm;
  }

  private isPortFree(port: number): Promise<boolean> {
    return new Promise((resolve) => {
      const server = net.createServer();
      server.once('error', () => resolve(false));
      server.once('listening', () => {
        server.close(() => resolve(true));
      });
      server.listen(port, '127.0.0.1');
    });
  }

  async allocPorts(): Promise<PortPair> {
    while (true) {
      const dc = this.nextPortBase;
      const pion = this.nextPortBase + 1;
      this.nextPortBase += 2;
      if (await this.isPortFree(dc) && await this.isPortFree(pion)) {
        return { dc, pion };
      }
    }
  }

  async getOrCreateTab(tabId: string): Promise<TabState> {
    if (!this.tabs.has(tabId)) {
      const ports = await this.allocPorts();
      this.tabs.set(tabId, {
        relay: null,
        tunnelMode: TunnelMode.DC,
        platform: Platform.VK,
        dcPort: ports.dc,
        pionPort: ports.pion,
      });
    }
    return this.tabs.get(tabId)!;
  }

  getTab(tabId: string): TabState | undefined {
    return this.tabs.get(tabId);
  }

  deleteTab(tabId: string): void {
    const tab = this.tabs.get(tabId);
    if (tab) {
      this.killRelay(tabId, tab);
      this.tabs.delete(tabId);
    }
    this.botTabIds.delete(tabId);
    this.callStatusCache.delete(tabId);
  }

  addBotTab(tabId: string): void {
    this.botTabIds.add(tabId);
  }

  removeBotTab(tabId: string): void {
    this.botTabIds.delete(tabId);
  }

  isBotTab(tabId: string): boolean {
    return this.botTabIds.has(tabId);
  }

  setCallStatus(tabId: string, status: CallStatus): void {
    this.callStatusCache.set(tabId, status);
  }

  getCallStatus(tabId: string): CallStatus {
    return this.callStatusCache.get(tabId) || CallStatus.Inactive;
  }

  getTabList(): TabListEntry[] {
    const result: TabListEntry[] = [];
    this.tabs.forEach((tab, tabId) => {
      result.push({
        id: tabId,
        platform: tab.platform,
        mode: tab.tunnelMode,
        isBot: tab.isBot === true,
        callStatus: this.getCallStatus(tabId),
      });
    });
    return result;
  }

  private sendLog(tabId: string, msg: string): void {
    if (this._mainWindow && !this._mainWindow.isDestroyed()) {
      this._mainWindow.webContents.send(IPC.RELAY_LOG, { tabId, msg });
    }
  }

  private attachProcessOutput(
    proc: ChildProcess,
    tabId: string,
    inspect?: (msg: string) => void,
  ): void {
    const onData = (data: Buffer) => {
      data
        .toString()
        .trim()
        .split('\n')
        .forEach((msg) => {
          if (!msg) return;
          console.log(`[relay:${tabId}]`, msg);
          this.sendLog(tabId, msg);
          if (inspect) inspect(msg);
        });
    };
    proc.stdout?.on('data', onData);
    proc.stderr?.on('data', onData);
  }

  sendBotCallLink(tabId: string, link: string): void {
    if (!this.botTabIds.has(tabId) || !this._botManager) return;
    const tab = this.tabs.get(tabId);
    if (!tab || tab.peerId == null) return;
    console.log(`[MAIN] Headless call link for bot tab ${tabId}:`, link);
    this._botManager.sendMessage(tab.peerId, link);
  }

  setUpstreamProxy(proxy: UpstreamProxy): void {
    this.upstreamProxy = {
      socks: (proxy?.socks || '').trim(),
      user: (proxy?.user || '').trim(),
      pass: (proxy?.pass || '').trim(),
    };
  }

  private appendUpstreamArgs(args: string[]): void {
    if (!this.upstreamProxy.socks) return;
    args.push('--upstream-socks', this.upstreamProxy.socks);
    if (this.upstreamProxy.user) args.push('--upstream-user', this.upstreamProxy.user);
    if (this.upstreamProxy.pass) args.push('--upstream-pass', this.upstreamProxy.pass);
  }

  startRelay(tabId: string, tab: TabState): void {
    this.killRelay(tabId, tab);
    const port = tab.tunnelMode === TunnelMode.PionVideo ? tab.pionPort : tab.dcPort;
    let relayMode: RelayMode = RelayMode.DCCreator;
    if (tab.tunnelMode === TunnelMode.PionVideo) {
      relayMode = tab.platform === Platform.Telemost
        ? RelayMode.TelemostVideoCreator
        : RelayMode.VKVideoCreator;
    }
    const relayArgs = ['--mode', relayMode, '--ws-port', String(port)];
    this.appendUpstreamArgs(relayArgs);
    const proc = spawn(this.relayPath, relayArgs, {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    tab.relay = proc;
    this.attachProcessOutput(proc, tabId);
    proc.on('close', (code) => {
      this.sendLog(tabId, `Relay exited with code ${code}`);
    });
  }

  private headlessConfig(platform: Platform): {
    tunnelMode: TunnelMode;
    authCookie: string;
    loginUrl: string;
    cookieDomains: string[];
    platformName: string;
    binaryPath: string;
  } | null {
    switch (platform) {
      case Platform.Telemost:
        return {
          tunnelMode: TunnelMode.HeadlessTelemost,
          authCookie: YANDEX_AUTH_COOKIE,
          loginUrl: YANDEX_LOGIN_URL,
          cookieDomains: YANDEX_COOKIE_DOMAINS,
          platformName: 'Yandex',
          binaryPath: this.headlessTelemostPath,
        };
      case Platform.Dion:
        return {
          tunnelMode: TunnelMode.HeadlessDion,
          authCookie: DION_AUTH_COOKIE,
          loginUrl: DION_LOGIN_URL,
          cookieDomains: DION_COOKIE_DOMAINS,
          platformName: 'DION',
          binaryPath: this.headlessDionPath,
        };
      case Platform.VK:
        return {
          tunnelMode: TunnelMode.HeadlessVK,
          authCookie: VK_AUTH_COOKIE,
          loginUrl: VK_LOGIN_URL,
          cookieDomains: VK_COOKIE_DOMAINS,
          platformName: 'VK',
          binaryPath: this.headlessVKPath,
        };
      case Platform.WBStream:
        return {
          tunnelMode: TunnelMode.HeadlessWBStream,
          authCookie: WBSTREAM_AUTH_COOKIE,
          loginUrl: WBSTREAM_LOGIN_URL,
          cookieDomains: WBSTREAM_COOKIE_DOMAINS,
          platformName: 'WB Stream',
          binaryPath: this.headlessWBStreamPath,
        };
      default:
        return null;
    }
  }

  private joinFlagFor(platform: Platform): string | null {
    switch (platform) {
      case Platform.VK:
        return '--vk-link';
      case Platform.Telemost:
        return '--tm-link';
      case Platform.WBStream:
      case Platform.Dion:
        return '--room';
      default:
        return null;
    }
  }

  async startHeadless(tabId: string, platform: Platform, args: HeadlessStartArgs): Promise<void> {
    const tab = await this.getOrCreateTab(tabId);
    tab.platform = platform;
    const joinTarget = args.mode === HeadlessMode.Join ? (args.target || '').trim() : '';
    if (args.mode === HeadlessMode.Join && !joinTarget) {
      this.sendLog(tabId, 'Join requested but no target link/room provided.');
      return;
    }

    const config = this.headlessConfig(platform);
    if (!config) {
      this.sendLog(tabId, `Unsupported headless platform: ${platform}`);
      return;
    }
    tab.tunnelMode = config.tunnelMode;
    let cookies = await this.getCookiesForDomains(config.cookieDomains);
    const refreshCookie = platform === Platform.WBStream ? 'wbx-refresh' : config.authCookie;
    const needsLogin = !cookies.some((c) => c.name === refreshCookie);
    if (needsLogin) {
      if (tab.isBot) {
        const reply = `Please log into ${config.platformName} in the creator app first, then try again.`;
        this.sendLog(tabId, reply);
        if (this._botManager && tab.peerId != null) {
          await this._botManager.sendMessage(tab.peerId, reply);
        }
        return;
      }
      this.sendLog(tabId, `No ${config.platformName} session found, opening login.`);
      if (this._mainWindow && !this._mainWindow.isDestroyed()) {
        this._mainWindow.webContents.send(IPC.LOGIN_REQUIRED, { tabId, url: config.loginUrl });
      }
      if (platform === Platform.WBStream) {
        this.sendLog(tabId, 'Waiting for x_wbaas_token, wbx-refresh, wbx-validation-key...');
        await this.waitForCookies(['x_wbaas_token', 'wbx-refresh', 'wbx-validation-key']);
      } else if (platform === Platform.Dion) {
        this.sendLog(tabId, 'Waiting for vc-refresh-token, vc-access-token...');
        await this.waitForCookies(['vc-refresh-token', 'vc-access-token']);
      } else {
        await this.waitForLogin(config.cookieDomains, config.authCookie);
      }
      if (this._mainWindow && !this._mainWindow.isDestroyed()) {
        this._mainWindow.webContents.send(IPC.LOGIN_DONE, { tabId });
      }
      this.sendLog(tabId, `${config.platformName} login captured.`);
      cookies = await this.getCookiesForDomains(config.cookieDomains);
    }
    this.sendLog(tabId, `${config.platformName} cookies (${cookies.length}): ${cookies.map((c) => c.name).join(', ')}`);
    const cookiesPath = path.join(app.getPath('userData'), `cookies-${platform}.json`);
    await fs.writeFile(cookiesPath, JSON.stringify(cookies));
    const spawnArgs = ['--resources', 'default', '--cookies', cookiesPath];
    this.killRelay(tabId, tab);
    if (joinTarget) {
      const flag = this.joinFlagFor(platform);
      if (flag) spawnArgs.push(flag, joinTarget);
    }
    this.appendUpstreamArgs(spawnArgs);
    const proc = spawn(config.binaryPath, spawnArgs, {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    tab.relay = proc;
    let sawAuthFailure = false;
    this.attachProcessOutput(proc, tabId, (msg) => {
      if (
        msg.includes('status 401') ||
        msg.includes('"UnauthorizedError"') ||
        msg.includes('"error":"unauthorized') ||
        msg.includes('empty access_token')
      ) {
        sawAuthFailure = true;
      }
    });
    proc.on('close', async (code) => {
      this.sendLog(tabId, `Headless exited with code ${code}`);
      if (sawAuthFailure) {
        await this.clearAuthCookies(config.cookieDomains, config.authCookie);
        if (this.tabs.get(tabId) === tab) this.startHeadless(tabId, platform, args);
      }
    });
  }

  private async clearAuthCookies(cookieDomains: string[], authCookieName: string): Promise<void> {
    const ses = session.fromPartition(SESSION_PARTITION);
    const matches = await ses.cookies.get({ name: authCookieName });
    for (const cookie of matches) {
      if (!cookie.domain || !cookieDomains.some((d) => cookie.domain!.includes(d))) continue;
      const host = cookie.domain.startsWith('.') ? cookie.domain.slice(1) : cookie.domain;
      const url = `https://${host}${cookie.path || '/'}`;
      try {
        await ses.cookies.remove(url, cookie.name);
      } catch (err) {
        console.log(`[COOKIES] failed to remove ${cookie.name} on ${url}:`, err);
      }
    }
  }

  private waitForLogin(cookieDomains: string[], authCookieName: string): Promise<void> {
    return new Promise((resolve) => {
      const ses = session.fromPartition(SESSION_PARTITION);
      const finish = () => {
        ses.cookies.removeListener('changed', onChanged);
        resolve();
      };
      const onChanged = (
        _e: Electron.Event,
        cookie: Electron.Cookie,
        _cause: string,
        removed: boolean,
      ) => {
        if (removed) return;
        if (cookie.name !== authCookieName) return;
        if (!cookie.domain || !cookieDomains.some((d) => cookie.domain!.includes(d))) return;
        finish();
      };
      ses.cookies.on('changed', onChanged);
      ses.cookies.get({ name: authCookieName }).then((found) => {
        if (found.some((c) => c.domain && cookieDomains.some((d) => c.domain!.includes(d)))) {
          finish();
        }
      });
    });
  }

  killRelay(tabId: string, tab: TabState): void {
    if (tab.relay) {
      console.log(`[${tabId}] killing process pid=${tab.relay.pid}`);
      tab.relay.kill();
      tab.relay = null;
    }
  }

  killAllRelays(): void {
    this.tabs.forEach((tab, tabId) => this.killRelay(tabId, tab));
  }

  async loadHook(tabId: string, url: string, tab: TabState): Promise<string> {
    const isDion = url.includes('dion.vc');
    const isTelemost = url.includes('telemost.yandex');
    if (isDion) {
      tab.platform = Platform.Dion;
      return LOG_CAPTURE_SNIPPET;
    }
    tab.platform = isTelemost ? Platform.Telemost : Platform.VK;

    if (isTelemost || tab.tunnelMode === TunnelMode.PionVideo) {
      const hookFile = isTelemost ? 'video-telemost.js' : 'video-vk.js';
      const hook = await fs.readFile(path.join(this.hooksDir, hookFile), 'utf8');
      return LOG_CAPTURE_SNIPPET + `window.PION_PORT=${tab.pionPort};window.IS_CREATOR=true;` + hook;
    }

    const hook = await fs.readFile(path.join(this.hooksDir, 'dc-creator-vk.js'), 'utf8');
    return LOG_CAPTURE_SNIPPET + `window.WS_PORT=${tab.dcPort};` + hook;
  }

  async setTunnelMode(tabId: string, mode: TunnelMode, platform?: Platform): Promise<void> {
    const tab = await this.getOrCreateTab(tabId);
    tab.tunnelMode = mode;
    if (platform) tab.platform = platform;
    if (
      mode === TunnelMode.HeadlessVK ||
      mode === TunnelMode.HeadlessTelemost ||
      mode === TunnelMode.HeadlessWBStream ||
      mode === TunnelMode.HeadlessDion
    ) return;
    this.killRelay(tabId, tab);
    setTimeout(() => this.startRelay(tabId, tab), RELAY_RESTART_DELAY_MS);
  }

  private async getCookiesForDomains(domains: string[]): Promise<{ name: string; value: string }[]> {
    const ses = session.fromPartition(SESSION_PARTITION);
    const all = await ses.cookies.get({});
    return all
      .filter((c) => c.domain != null && domains.some((d) => c.domain!.includes(d)))
      .map((c) => ({ name: c.name, value: c.value }));
  }

  private waitForCookies(names: string[]): Promise<void> {
    return new Promise((resolve) => {
      const ses = session.fromPartition(SESSION_PARTITION);
      const remaining = new Set(names);
      const finish = () => {
        ses.cookies.removeListener('changed', onChanged);
        resolve();
      };
      const onChanged = (
        _e: Electron.Event,
        cookie: Electron.Cookie,
        _cause: string,
        removed: boolean,
      ) => {
        if (removed) return;
        if (!remaining.has(cookie.name)) return;
        remaining.delete(cookie.name);
        if (remaining.size === 0) finish();
      };
      ses.cookies.on('changed', onChanged);
      Promise.all(names.map((name) => ses.cookies.get({ name }))).then((results) => {
        results.forEach((found, i) => {
          if (found.length > 0) remaining.delete(names[i]);
        });
        if (remaining.size === 0) finish();
      });
    });
  }

  async buildCookiesZip(): Promise<Buffer> {
    const platforms: { filename: string; domains: string[] }[] = [
      { filename: 'cookies-vk.json', domains: VK_COOKIE_DOMAINS },
      { filename: 'cookies-yandex.json', domains: YANDEX_COOKIE_DOMAINS },
      { filename: 'cookies-dion.json', domains: DION_COOKIE_DOMAINS },
      { filename: 'cookies-wbstream.json', domains: WBSTREAM_COOKIE_DOMAINS },
    ];
    const cookies = await Promise.all(platforms.map((p) => this.getCookiesForDomains(p.domains)));
    const entries = platforms.map((p, i) => ({
      name: p.filename,
      data: Buffer.from(JSON.stringify(cookies[i], null, 2), 'utf8'),
    }));
    return buildStoredZip(entries);
  }

  async setWBStreamDeviceId(id: string): Promise<void> {
    if (!id) return;
    const ses = session.fromPartition(SESSION_PARTITION);
    const existing = await ses.cookies.get({ url: 'https://stream.wb.ru/', name: '__wb_device_id' });
    if (existing.length > 0 && existing[0].value === id) return;
    try {
      await ses.cookies.set({
        url: 'https://stream.wb.ru/',
        name: '__wb_device_id',
        value: id,
        domain: 'stream.wb.ru',
        path: '/',
        secure: true,
        httpOnly: false,
        expirationDate: Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 365 * 5,
      });
      console.log(`[wb-device-id] persisted ${id}`);
    } catch (err) {
      console.log(`[wb-device-id] failed to persist:`, err);
    }
  }

}
