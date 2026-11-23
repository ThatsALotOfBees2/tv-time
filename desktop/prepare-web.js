// Copies the shared start page and page helper into desktop/web so the PC app uses the
// exact same files as the Android app and the Raspberry Pi.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const out = path.join(__dirname, 'web');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.cpSync(path.join(root, 'web', 'start'), path.join(out, 'start'), { recursive: true });
fs.copyFileSync(path.join(root, 'app', 'src', 'main', 'assets', 'inject.js'), path.join(out, 'inject.js'));
console.log('Copied the start page into', out);
