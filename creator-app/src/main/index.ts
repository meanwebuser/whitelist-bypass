import { app, protocol } from 'electron';
import { TabManager } from './tab-manager';
import { createWindow } from './window';
import { registerIpcHandlers } from './ipc';

const tabManager = new TabManager();

registerIpcHandlers(tabManager);

protocol.registerSchemesAsPrivileged([
  { scheme: 'wbstream', privileges: { standard: true, secure: true, supportFetchAPI: true } },
]);

app.whenReady().then(() => {
  protocol.handle('wbstream', () => new Response(null, { status: 204 }));
  const win = createWindow(tabManager);
  tabManager.mainWindow = win;
});

app.on('window-all-closed', () => {
  tabManager.killAllRelays();
  app.quit();
});

app.on('before-quit', () => tabManager.killAllRelays());
process.on('exit', () => tabManager.killAllRelays());
process.on('SIGINT', () => {
  tabManager.killAllRelays();
  process.exit();
});
process.on('SIGTERM', () => {
  tabManager.killAllRelays();
  process.exit();
});
