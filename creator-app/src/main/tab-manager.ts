import { app } from 'electron';
import { spawn, ChildProcess } from 'child_process';
import { BrowserWindow, session } from 'electron';
import * as net from 'net';
import * as path from 'path';
import * as fs from 'fs/promises';
import { TabState, PortPair, TabListEntry, Platform, TunnelMode, RelayMode, CallStatus } from '../types';
import {
  INITIAL_PORT_BASE,
  IPC,
  RELAY_RESTART_DELAY_MS,
  SESSION_PARTITION,
  VK_COOKIE_DOMAINS,
  YANDEX_COOKIE_DOMAINS,
  VK_LOGIN_URL,
  YANDEX_LOGIN_URL,
  VK_AUTH_COOKIE,
  YANDEX_AUTH_COOKIE,
  LOG_CAPTURE_SNIPPET,
} from '../constants';
import { BotManager } from '../bot/bot-manager';

function resolveResourcePath(devRelative: string, packedName: string): string {
  if (app.isPackaged) {
    return path.join(process.resourcesPath!, packedName);
  }
  return path.join(__dirname, '..', '..', '..', devRelative);
}

function binaryName(base: string): string {
  return process.platform === 'win32' ? base + '.exe' : base;
}

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
  private hooksDir: string;

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
    this._botManager.sendMessage(tab.peerId, `Call created!\n${link}`);
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
    const proc = spawn(this.relayPath, ['--mode', relayMode, '--ws-port', String(port)], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    tab.relay = proc;
    this.attachProcessOutput(proc, tabId);
    proc.on('close', (code) => {
      this.sendLog(tabId, `Relay exited with code ${code}`);
    });
  }

  async startHeadless(tabId: string, platform: Platform): Promise<void> {
    const tab = await this.getOrCreateTab(tabId);
    tab.platform = platform;

    if (platform === Platform.WBStream) {
      tab.tunnelMode = TunnelMode.HeadlessWBStream;
      this.killRelay(tabId, tab);
      const proc = spawn(this.headlessWBStreamPath, ['--resources', 'default'], {
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      tab.relay = proc;
      this.attachProcessOutput(proc, tabId);
      proc.on('close', (code) => {
        this.sendLog(tabId, `Headless exited with code ${code}`);
      });
      return;
    }

    const isTelemost = platform === Platform.Telemost;
    tab.tunnelMode = isTelemost ? TunnelMode.HeadlessTelemost : TunnelMode.HeadlessVK;
    const authCookie = isTelemost ? YANDEX_AUTH_COOKIE : VK_AUTH_COOKIE;
    const loginUrl = isTelemost ? YANDEX_LOGIN_URL : VK_LOGIN_URL;
    const cookieDomains = isTelemost ? YANDEX_COOKIE_DOMAINS : VK_COOKIE_DOMAINS;
    const platformName = isTelemost ? 'Yandex' : 'VK';
    let cookies = isTelemost ? await this.getYandexCookies() : await this.getVKCookies();
    if (!cookies.some((c) => c.name === authCookie)) {
      this.sendLog(tabId, `No ${platformName} session found, opening login.`);
      if (this._mainWindow && !this._mainWindow.isDestroyed()) {
        this._mainWindow.webContents.send(IPC.LOGIN_REQUIRED, { tabId, url: loginUrl });
      }
      await this.waitForLogin(cookieDomains, authCookie);
      if (this._mainWindow && !this._mainWindow.isDestroyed()) {
        this._mainWindow.webContents.send(IPC.LOGIN_DONE, { tabId });
      }
      this.sendLog(tabId, `${platformName} login captured.`);
      cookies = isTelemost ? await this.getYandexCookies() : await this.getVKCookies();
    }
    this.sendLog(tabId, `${platformName} cookies (${cookies.length}): ${cookies.map((c) => c.name).join(', ')}`);
    this.killRelay(tabId, tab);
    const cookiesPath = path.join(app.getPath('userData'), `cookies-${platform}.json`);
    await fs.writeFile(cookiesPath, JSON.stringify(cookies));
    const binaryPath = isTelemost ? this.headlessTelemostPath : this.headlessVKPath;
    const proc = spawn(binaryPath, ['--resources', 'default', '--cookies', cookiesPath], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    tab.relay = proc;
    let sawAuthFailure = false;
    this.attachProcessOutput(proc, tabId, (msg) => {
      if (msg.includes('status 401') || msg.includes('"UnauthorizedError"')) {
        sawAuthFailure = true;
      }
    });
    proc.on('close', async (code) => {
      this.sendLog(tabId, `Headless exited with code ${code}`);
      if (sawAuthFailure) {
        this.sendLog(tabId, `${platformName} session rejected (401), clearing and re-prompting login.`);
        await this.clearAuthCookies(cookieDomains, authCookie);
        if (this.tabs.get(tabId) === tab) {
          this.startHeadless(tabId, platform);
        }
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
    const isTelemost = url.includes('telemost.yandex');
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
    if (mode === TunnelMode.HeadlessVK || mode === TunnelMode.HeadlessTelemost) return;
    this.killRelay(tabId, tab);
    setTimeout(() => this.startRelay(tabId, tab), RELAY_RESTART_DELAY_MS);
  }

  async getVKCookies(): Promise<{ name: string; value: string }[]> {
    const ses = session.fromPartition(SESSION_PARTITION);
    const all = await ses.cookies.get({});
    return all
      .filter((c) => c.domain != null && VK_COOKIE_DOMAINS.some((d) => c.domain!.includes(d)))
      .map((c) => ({ name: c.name, value: c.value }));
  }

  async getYandexCookies(): Promise<{ name: string; value: string }[]> {
    const ses = session.fromPartition(SESSION_PARTITION);
    const all = await ses.cookies.get({});
    return all
      .filter((c) => c.domain != null && YANDEX_COOKIE_DOMAINS.some((d) => c.domain!.includes(d)))
      .map((c) => ({ name: c.name, value: c.value }));
  }

}
