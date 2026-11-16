/*
 * TV Time Shield - background worker (Raspberry Pi / Chromium).
 *
 *  - Redirect guard: the page may only navigate to sites on your start page (Streamex, YouTube,
 *    your own tiles). Anything else - typically an ad hijacking a click - is sent to
 *    blocked.html, which takes you straight back.
 *  - Pop-up killer: any tab opened by another tab is closed immediately.
 *  - Big block list: the StevenBlack hosts list is downloaded weekly and added on top of the
 *    built-in rules/ads.json.
 *  - Remote shortcuts sent by tvremote.py: Alt+Shift+H = start page, Alt+Shift+B = back.
 */
'use strict';

const START = chrome.runtime.getURL('start/index.html');
const DEFAULT_KEYS = ['streamex', 'youtube', 'youtu', 'youtube-nocookie', 'google'];
const GUARD_REDIRECT_ID = 1;
const GUARD_ALLOW_ID = 2;
const LIST_BASE_ID = 1000;
const LIST_URL = 'https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts';
const WEEK = 7 * 24 * 60 * 60 * 1000;

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function allowedKeys() {
  const { customKeys = [] } = await chrome.storage.local.get('customKeys');
  return [...new Set([...DEFAULT_KEYS, ...customKeys])].filter((k) => /^[a-z0-9-]+$/.test(k));
}

async function updateGuard() {
  const keys = await allowedKeys();
  const allowRe = '^https?://([^/:?#]+\\.)?(' + keys.map(escapeRe).join('|') + ')\\.[^/:?#]+(:[0-9]+)?([/?#]|$)';
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: [GUARD_REDIRECT_ID, GUARD_ALLOW_ID],
    addRules: [
      {
        id: GUARD_REDIRECT_ID,
        priority: 1,
        action: { type: 'redirect', redirect: { regexSubstitution: chrome.runtime.getURL('blocked.html') + '#\\1' } },
        condition: { regexFilter: '^(https?://[^/?#]+).*', resourceTypes: ['main_frame'] },
      },
      {
        id: GUARD_ALLOW_ID,
        priority: 2,
        action: { type: 'allow' },
        condition: { regexFilter: allowRe, isUrlFilterCaseSensitive: false, resourceTypes: ['main_frame'] },
      },
    ],
  });
}

async function refreshBlockList(force) {
  const { listUpdatedAt = 0 } = await chrome.storage.local.get('listUpdatedAt');
  if (!force && Date.now() - listUpdatedAt < WEEK) return;
  let text;
  try {
    const res = await fetch(LIST_URL, { cache: 'no-store' });
    if (!res.ok) return;
    text = await res.text();
  } catch (e) {
    return; // offline: keep whatever we had
  }
  const keep = new Set(DEFAULT_KEYS);
  const domains = new Set();
  for (const raw of text.split('\n')) {
    const line = raw.replace(/#.*/, '').trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    const host = (parts.length > 1 ? parts[1] : parts[0]).toLowerCase();
    if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(host)) continue;
    if (host === 'localhost' || host.endsWith('.local')) continue;
    const labels = host.split('.');
    if (keep.has(labels[labels.length - 2])) continue;
    domains.add(host);
  }
  const list = [...domains];
  const rules = [];
  for (let i = 0; i < list.length; i += 1000) {
    rules.push({
      id: LIST_BASE_ID + rules.length,
      priority: 1,
      action: { type: 'block' },
      condition: { requestDomains: list.slice(i, i + 1000), excludedResourceTypes: ['main_frame'] },
    });
  }
  const old = (await chrome.declarativeNetRequest.getDynamicRules())
    .filter((r) => r.id >= LIST_BASE_ID)
    .map((r) => r.id);
  try {
    await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds: old, addRules: rules });
    await chrome.storage.local.set({ listUpdatedAt: Date.now() });
  } catch (e) {
    console.warn('TV Time: could not install block list', e);
  }
}

async function setup() {
  await updateGuard();
  chrome.alarms.create('blocklist', { periodInMinutes: 24 * 60 });
  refreshBlockList(false);
}

chrome.runtime.onInstalled.addListener(setup);
chrome.runtime.onStartup.addListener(setup);
chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === 'blocklist') refreshBlockList(false);
});

// The start page tells us which sites you added, so the redirect guard lets you visit them.
chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === 'tvtime-sites' && Array.isArray(msg.keys)) {
    chrome.storage.local.set({ customKeys: msg.keys.map(String) }).then(updateGuard);
  }
});

// Pop-ups / pop-unders: a tab that another tab opened is never wanted on a TV.
chrome.tabs.onCreated.addListener((tab) => {
  if (tab.openerTabId !== undefined && tab.openerTabId !== null) {
    chrome.tabs.remove(tab.id).catch(() => {});
  }
});

async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  return tab;
}

chrome.commands.onCommand.addListener(async (command, tab) => {
  tab = tab || (await activeTab());
  if (!tab) return;
  if (command === 'go-home') {
    chrome.tabs.update(tab.id, { url: START });
  } else if (command === 'go-back') {
    try {
      const [res] = await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: () => {
          if (document.fullscreenElement) {
            document.exitFullscreen();
            return 'fullscreen';
          }
          return 'back';
        },
      });
      if (res && res.result === 'fullscreen') return;
    } catch (e) {
      // start page or a page we can't script: just go back
    }
    chrome.tabs.goBack(tab.id).catch(() => {});
  }
});
