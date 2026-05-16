import { RendererTabManager } from './tab-manager';
import {
  renderTabs,
  renderContent,
  renderBotButton,
  loadURL,
  startHookLogPoller,
  showError,
  closeError,
  clearLog,
  saveLogs,
  exportCookies,
  copyToClipboard,
  renameTab,
  attachLoginWebview,
  detachLoginWebview,
} from './dom';
import { VK_IM_URL, TELEMOST_URL } from '../constants';
import { Platform, Bridge, BotTabData, LogPanel, TunnelMode, HeadlessMode } from '../types';

declare const window: Window & { bridge: Bridge };

const tm = new RendererTabManager(() => {
  renderTabs(tm);
  renderContent(tm);
});

function bindTabBarEvents(): void {
  document.getElementById('tabBar')!.addEventListener('click', (event) => {
    const target = event.target as HTMLElement;
    const action = target.dataset.action;
    const tabEl = target.closest('[data-tab-id]') as HTMLElement | null;
    const tabId = tabEl?.dataset.tabId;

    if (action === 'add-tab') {
      tm.createTab();
      return;
    }
    if (action === 'close' && tabId) {
      event.stopPropagation();
      tm.closeTab(tabId);
      return;
    }
    if (action === 'rename' && tabId) {
      event.stopPropagation();
      renameTab(tm, tabId, tabEl!);
      return;
    }
    if (tabId) {
      tm.selectTab(tabId);
    }
  });
}

function bindToolbarEvents(): void {
  document.getElementById('btnVk')!.addEventListener('click', () => loadURL(tm, VK_IM_URL));
  document.getElementById('btnTelemost')!.addEventListener('click', () => loadURL(tm, TELEMOST_URL));
  document.getElementById('btnHeadlessVK')!.addEventListener('click', () => tm.switchToHeadless(Platform.VK));
  document.getElementById('btnHeadlessTM')!.addEventListener('click', () => tm.switchToHeadless(Platform.Telemost));
  document.getElementById('btnHeadlessWB')!.addEventListener('click', () => tm.switchToHeadless(Platform.WBStream));
  document.getElementById('btnHeadlessDion')!.addEventListener('click', () => tm.switchToHeadless(Platform.Dion));
  document.getElementById('modeSelect')!.addEventListener('change', (event) => {
    tm.setTunnelMode((event.target as HTMLSelectElement).value);
  });
  document.getElementById('btnSaveLogs')!.addEventListener('click', saveLogs);
}

function bindActionBarEvents(): void {
  document.getElementById('btnVkCookies')!.addEventListener('click', () => {
    exportCookies('.vk.com', 'vk-cookies.json', 'No VK cookies found.\nPlease log into VK first.');
  });
  document.getElementById('btnYandexCookies')!.addEventListener('click', () => {
    exportCookies(
      'yandex',
      'cookies-yandex.json',
      'No Yandex cookies found.\nPlease log into Yandex (telemost.yandex.ru) first.',
    );
  });
  document.getElementById('btnDionCookies')!.addEventListener('click', () => {
    exportCookies(
      'dion',
      'cookies-dion.json',
      'No DION cookies found.\nPlease log into DION (dion.vc) first.',
    );
  });
  document.getElementById('btnWBStreamCookies')!.addEventListener('click', () => {
    exportCookies(
      'wbstream',
      'cookies-wbstream.json',
      'No WB Stream cookies found.\nPlease log into stream.wb.ru first.',
    );
  });
  document.getElementById('btnSettings')!.addEventListener('click', openSettings);
  document.getElementById('tabBot')!.addEventListener('click', () => {
    if (!tm.botSettings.token || !tm.botSettings.groupId) {
      openSettings();
      return;
    }
    tm.toggleBot();
  });
}

function bindSettingsEvents(): void {
  document.getElementById('btnSettingsCancel')!.addEventListener('click', closeSettings);
  document.getElementById('btnSettingsSave')!.addEventListener('click', () => {
    tm.botSettings.token = (document.getElementById('vkToken') as HTMLInputElement).value.trim();
    tm.botSettings.groupId = (document.getElementById('vkGroupId') as HTMLInputElement).value.trim();
    tm.botSettings.userId = (document.getElementById('vkUserId') as HTMLInputElement).value.trim();
    tm.saveBotSettings();
    closeSettings();
  });
}

function bindErrorPopup(): void {
  document.getElementById('errorPopup')!.addEventListener('click', closeError);
  document.querySelector('#errorPopup .popup')!.addEventListener('click', (event) => event.stopPropagation());
  document.getElementById('btnErrorClose')!.addEventListener('click', closeError);
}

function bindLogEvents(): void {
  document.getElementById('btnClearRelay')!.addEventListener('click', () => clearLog(LogPanel.Relay));
  document.getElementById('btnClearHook')!.addEventListener('click', () => clearLog(LogPanel.Hook));
  document.getElementById('btnSaveLogsHeadless')!.addEventListener('click', saveLogs);
}

function bindHeadlessEvents(): void {
  document.getElementById('headlessJoinLink')!.addEventListener('click', (event) => {
    copyToClipboard((event.target as HTMLElement).textContent || '');
  });
  document.getElementById('headlessInfo')!.addEventListener('click', (event) => {
    const target = event.target as HTMLElement;
    const copyTarget = target.dataset.copy;
    if (copyTarget) {
      const sourceEl = document.getElementById(copyTarget);
      if (sourceEl) copyToClipboard(sourceEl.textContent || '');
    }
  });
  document.getElementById('btnHeadlessCreate')!.addEventListener('click', () => {
    clearHeadlessJoinError();
    tm.startHeadlessCall({ mode: HeadlessMode.Create });
  });
  document.getElementById('btnHeadlessJoin')!.addEventListener('click', () => {
    const targetInput = document.getElementById('headlessStartTarget') as HTMLInputElement;
    const target = targetInput.value.trim();
    if (!target) {
      showHeadlessJoinError('Enter a room or link to join.');
      targetInput.focus();
      return;
    }
    clearHeadlessJoinError();
    tm.startHeadlessCall({ mode: HeadlessMode.Join, target });
  });
  document.getElementById('headlessStartTarget')!.addEventListener('input', (event) => {
    const tab = tm.getActiveTab();
    if (tab) tab.headlessStartTarget = (event.target as HTMLInputElement).value;
    clearHeadlessJoinError();
  });
}

function showHeadlessJoinError(msg: string): void {
  const errEl = document.getElementById('headlessStartError')!;
  const input = document.getElementById('headlessStartTarget') as HTMLInputElement;
  errEl.textContent = msg;
  input.classList.add('invalid');
}

function clearHeadlessJoinError(): void {
  const errEl = document.getElementById('headlessStartError')!;
  const input = document.getElementById('headlessStartTarget') as HTMLInputElement;
  errEl.textContent = '';
  input.classList.remove('invalid');
}

function openSettings(): void {
  document.getElementById('settingsPopup')!.classList.add('visible');
  (document.getElementById('vkToken') as HTMLInputElement).value = tm.botSettings.token;
  (document.getElementById('vkGroupId') as HTMLInputElement).value = tm.botSettings.groupId;
  (document.getElementById('vkUserId') as HTMLInputElement).value = tm.botSettings.userId;
}

function closeSettings(): void {
  document.getElementById('settingsPopup')!.classList.remove('visible');
}

function init(): void {
  bindTabBarEvents();
  bindToolbarEvents();
  bindActionBarEvents();
  bindSettingsEvents();
  bindErrorPopup();
  bindLogEvents();
  bindHeadlessEvents();

  window.bridge.onRelayLog((tabId: string, msg: string) => {
    tm.appendRelayLog(tabId, msg);
  });

  window.bridge.onBotError((msg: string) => {
    showError(msg);
    tm.botRunning = false;
    localStorage.setItem('botEnabled', 'false');
    renderBotButton(tm);
  });

  window.bridge.onCreateBotTab((data: BotTabData) => {
    tm.createBotTab(data);
    (document.getElementById('modeSelect') as HTMLSelectElement).value = data.mode;
    if (data.mode === TunnelMode.HeadlessVK) {
      tm.switchToHeadless(Platform.VK);
    } else if (data.mode === TunnelMode.HeadlessTelemost) {
      tm.switchToHeadless(Platform.Telemost);
    } else if (data.mode === TunnelMode.HeadlessWBStream) {
      tm.switchToHeadless(Platform.WBStream);
    } else if (data.mode === TunnelMode.HeadlessDion) {
      tm.switchToHeadless(Platform.Dion);
    } else {
      const url = data.platform === Platform.Telemost ? TELEMOST_URL : VK_IM_URL;
      loadURL(tm, url);
    }
  });

  window.bridge.onCloseBotTab((data: { tabId: string }) => {
    tm.closeTab(data.tabId);
  });

  window.bridge.onLoginRequired((tabId: string, url: string) => {
    const tab = tm.tabs[tabId];
    if (!tab) return;
    tab.headlessStatus = 'Waiting for login...';
    attachLoginWebview(tm, tabId, url);
    if (tabId === tm.activeTabId) {
      renderTabs(tm);
      renderContent(tm);
    }
  });

  window.bridge.onLoginDone((tabId: string) => {
    const tab = tm.tabs[tabId];
    if (!tab) return;
    detachLoginWebview(tm, tabId);
    tab.headlessStatus = 'Starting...';
    if (tabId === tm.activeTabId) renderContent(tm);
  });

  startHookLogPoller(tm);
  tm.autoStartBot();
  renderBotButton(tm);
}

init();
