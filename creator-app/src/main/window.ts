import { app, BrowserWindow, session, Session } from 'electron';
import * as path from 'path';
import { TabManager } from './tab-manager';
import { VkAutoclick } from '../autoclick/vk';
import { TelemostAutoclick } from '../autoclick/telemost';
import { SESSION_PARTITION, USER_AGENT, WINDOW_WIDTH, WINDOW_HEIGHT } from '../constants';
import { Platform, CallStatus } from '../types';

function stripCSP(ses: Session): void {
  ses.webRequest.onHeadersReceived((details, callback) => {
    const headers = { ...details.responseHeaders };
    delete headers['content-security-policy'];
    delete headers['Content-Security-Policy'];
    delete headers['content-security-policy-report-only'];
    delete headers['Content-Security-Policy-Report-Only'];
    callback({ responseHeaders: headers });
  });
}

function parseCallStatus(msg: string): { tabId: string; status: CallStatus } | null {
  const prefix = '[CALL_STATUS] ';
  const idx = msg.indexOf(prefix);
  if (idx === -1) return null;
  const parts = msg.substring(idx + prefix.length);
  const colonIdx = parts.indexOf(':');
  if (colonIdx === -1) return null;
  const status = parts.substring(colonIdx + 1);
  return {
    tabId: parts.substring(0, colonIdx),
    status: status === CallStatus.Active ? CallStatus.Active : CallStatus.Inactive,
  };
}

function extractTaggedCallLink(msg: string, platform: Platform): { tabId: string; link: string } | null {
  const tag = platform === Platform.Telemost ? 'Telemost' : 'VKCalls';
  const re = new RegExp('\\[BOT\\] ' + tag + '\\[([^\\]]*)\\]: call link:\\s*(.+)$');
  const match = msg.match(re);
  if (!match) return null;
  return { tabId: match[1].trim(), link: match[2].trim() };
}

export function createWindow(tabManager: TabManager): BrowserWindow {
  const ses = session.fromPartition(SESSION_PARTITION);
  stripCSP(ses);
  ses.setPermissionRequestHandler((_wc, _perm, cb) => cb(true));
  ses.setPermissionCheckHandler(() => true);
  ses.setUserAgent(USER_AGENT);

  app.on('session-created', stripCSP);

  const win = new BrowserWindow({
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
    icon: path.join(__dirname, '..', '..', 'resources', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'index.js'),
      nodeIntegration: true,
      contextIsolation: false,
      webviewTag: true,
    },
  });

  win.loadFile('index.html');
  win.on('closed', () => {
    tabManager.mainWindow = null;
  });

  const autoclickers = new Map<number, { telemost: TelemostAutoclick; vk: VkAutoclick }>();

  const wbDeviceIdHook = `(function(){
    try {
      var key = 'wb_auth_api_device_id';
      var existing = localStorage.getItem(key);
      if (existing) console.log('[WB_DEVICE_ID]', existing);
      var orig = Storage.prototype.setItem;
      Storage.prototype.setItem = function(k, v) {
        if (k === key) console.log('[WB_DEVICE_ID]', v);
        return orig.apply(this, arguments);
      };
    } catch (e) {}
  })();`;

  win.webContents.on('did-attach-webview', (_e, wvContents) => {
    wvContents.on('before-input-event', (_e, input) => {
      if (input.key === 'F12') wvContents.openDevTools();
    });

    wvContents.on('will-navigate', (event, url) => {
      if (!url.startsWith('http://') && !url.startsWith('https://')) event.preventDefault();
    });
    wvContents.setWindowOpenHandler(({ url }) => {
      if (!url.startsWith('http://') && !url.startsWith('https://')) return { action: 'deny' };
      return { action: 'allow' };
    });

    wvContents.on('dom-ready', () => {
      const url = wvContents.getURL();
      if (url.includes('stream.wb.ru')) {
        wvContents.executeJavaScript(wbDeviceIdHook, true).catch(() => {});
      }
    });

    wvContents.on('did-navigate', (_e, url) => {
      const wcId = wvContents.id;
      if (!autoclickers.has(wcId)) {
        autoclickers.set(wcId, {
          telemost: new TelemostAutoclick(),
          vk: new VkAutoclick(),
        });
      }
      const ac = autoclickers.get(wcId)!;
      if (url.includes('telemost.yandex')) {
        ac.vk.stop();
        ac.telemost.attach(wvContents);
      } else if (url.includes('vk.com')) {
        ac.telemost.stop();
        ac.vk.attach(wvContents);
      } else {
        ac.telemost.stop();
        ac.vk.stop();
      }
    });

    wvContents.on('console-message', (_e, _level, msg) => {
      if (msg.includes('state: disconnected') || msg.includes('state: failed')) {
        const ac = autoclickers.get(wvContents.id);
        if (ac) ac.vk.kickDisconnected();
      }

      handleBotCallLink(tabManager, msg, Platform.VK);
      handleBotCallLink(tabManager, msg, Platform.Telemost);

      const deviceIdMatch = msg.match(/\[WB_DEVICE_ID\]\s+(\S+)/);
      if (deviceIdMatch) {
        tabManager.setWBStreamDeviceId(deviceIdMatch[1]).catch(() => {});
      }

      const callStatus = parseCallStatus(msg);
      if (callStatus) {
        console.log('[MAIN] Cached status for', callStatus.tabId, ':', callStatus.status);
        tabManager.setCallStatus(callStatus.tabId, callStatus.status);
      }
    });

    wvContents.on('destroyed', () => {
      const ac = autoclickers.get(wvContents.id);
      if (ac) {
        ac.telemost.stop();
        ac.vk.stop();
        autoclickers.delete(wvContents.id);
      }
    });
  });

  return win;
}

function handleBotCallLink(tabManager: TabManager, msg: string, platform: Platform): void {
  const tagged = extractTaggedCallLink(msg, platform);
  if (!tagged) return;
  const tab = tabManager.getTab(tagged.tabId);
  if (!tab || tab.peerId == null) {
    console.log(`[MAIN] ${platform} call link captured but no peer for tab ${tagged.tabId}`);
    return;
  }
  console.log(`[MAIN] Sending ${platform} link to peer ${tab.peerId} (tab ${tagged.tabId}):`, tagged.link);
  if (tabManager.botManager) {
    tabManager.botManager.sendMessage(tab.peerId, tagged.link);
  }
}
