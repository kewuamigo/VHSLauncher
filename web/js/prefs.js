// Launcher settings in localStorage. Falls back to memory when storage is blocked.

const KEY = 'vhs-launcher-prefs';
const DEFAULTS = {
  order: [], hidden: [], sound: true, volume: 7, weather: true, fahrenheit: false,
  clock24: true, scanlines: true, wobble: true, cityName: null, cityLat: 0, cityLon: 0,
};

function read() {
  try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) || '{}') }; } catch { return { ...DEFAULTS }; }
}

export function createPrefs() {
  const data = read();
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(data)); } catch { /* storage blocked */ } };
  return new Proxy(data, {
    set(target, prop, value) { target[prop] = value; save(); return true; },
  });
}
