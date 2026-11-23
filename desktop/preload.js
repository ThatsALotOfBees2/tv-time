// Lets the start page know it runs in the PC app (it shows keyboard/mouse hints).
const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('tvtimeDesktop', { platform: process.platform });
// The PC app opens in the Sleek look unless you picked another one (Add / edit sites > Look).
contextBridge.exposeInMainWorld('TVTIME_THEME', 'sleek');
