// Web "apps": pages that can be shown in an iframe. Edit web/apps.json to change the shelf.

export const SETUP_KEY = 'vhs/setup';
export const SETUP = { label: 'Setup', key: SETUP_KEY, url: null };

export async function loadApps() {
  const res = await fetch('apps.json', { cache: 'no-cache' });
  const list = await res.json();
  return list.map((a) => ({
    label: a.label,
    key: a.key ?? a.label.toLowerCase().replace(/[^a-z0-9]+/g, '-'),
    url: a.url.replace('{host}', location.hostname || 'localhost'),
  }));
}

/** All apps in the user's order: saved order first, anything new after it in file order. */
export function ordered(all, prefs) {
  const rank = new Map(prefs.order.map((k, i) => [k, i]));
  const known = all.filter((a) => rank.has(a.key)).sort((a, b) => rank.get(a.key) - rank.get(b.key));
  return [...known, ...all.filter((a) => !rank.has(a.key))];
}

/** What goes on the shelf: visible apps in order, then the setup tape. */
export function shelf(all, prefs) {
  const hidden = new Set(prefs.hidden);
  return [...ordered(all, prefs).filter((a) => !hidden.has(a.key)), SETUP];
}
