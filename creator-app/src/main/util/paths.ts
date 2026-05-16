import { app } from 'electron';
import * as path from 'path';

export function resolveResourcePath(devRelative: string, packedName: string): string {
  if (app.isPackaged) {
    return path.join(process.resourcesPath!, packedName);
  }
  return path.join(__dirname, '..', '..', '..', '..', devRelative);
}

export function binaryName(base: string): string {
  return process.platform === 'win32' ? base + '.exe' : base;
}
