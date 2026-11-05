// The SETUP tape's on-screen menu, in the style of a VCR's blue OSD. Ported from SetupMenu.kt.

import { ordered } from './apps.js';

const ITEMS = ['APPS', 'SOUND', 'VOLUME', 'WEATHER', 'LOCATION', 'UNITS', 'CLOCK', 'SCANLINES', 'WOBBLE', 'FULLSCREEN', 'EXIT'];
const VISIBLE = 10;

export class SetupMenu {
  /** host: { askCity(), toggleFullscreen(), isFullscreen(), weatherSettingsChanged(), click() } */
  constructor(prefs, host) {
    this.prefs = prefs; this.host = host;
    this.version = 0; this._city = null;
    this.page = 'main'; this.cursor = 0; this.first = 0; this.grabbed = false;
    this.defaults = []; this.apps = []; this.hidden = new Set();
  }

  get detectedCity() { return this._city; }
  set detectedCity(v) { this._city = v; this.changed(); }

  open(all) {
    this.defaults = all;
    this.apps = ordered(all, this.prefs);
    this.hidden = new Set(this.prefs.hidden);
    this.page = 'main'; this.cursor = 0; this.first = 0; this.grabbed = false;
    this.changed();
  }

  refresh() { this.changed(); }
  changed() { this.version++; }

  up() { this.moveCursor(-1); }
  down() { this.moveCursor(1); }
  left() { this.change(-1); }
  right() { this.change(1); }

  /** Returns true when the menu should close. */
  ok() {
    this.host.click();
    if (this.page === 'apps') {
      if (this.cursor === 0) { this.prefs.order = []; this.apps = [...this.defaults]; } else this.grabbed = !this.grabbed;
      this.changed();
      return false;
    }
    switch (ITEMS[this.cursor]) {
      case 'APPS': this.page = 'apps'; this.cursor = 0; this.first = 0; break;
      case 'LOCATION': this.host.askCity(); break;
      case 'FULLSCREEN': this.host.toggleFullscreen(); break;
      case 'EXIT': return true;
      default: this.change(1); return false;
    }
    this.changed();
    return false;
  }

  /** Returns true when the menu should close. */
  back() {
    this.host.click();
    if (this.page === 'apps') {
      this.grabbed = false; this.page = 'main'; this.cursor = 0; this.first = 0;
      this.changed();
      return false;
    }
    return true;
  }

  moveCursor(d) {
    const count = this.page === 'main' ? ITEMS.length : this.apps.length + 1;
    const next = Math.min(count - 1, Math.max(0, this.cursor + d));
    if (next === this.cursor) return;
    if (this.page === 'apps' && this.grabbed) {
      if (next < 1) return;
      const a = this.cursor - 1, b = next - 1;
      [this.apps[a], this.apps[b]] = [this.apps[b], this.apps[a]];
      this.prefs.order = this.apps.map((x) => x.key);
    }
    this.cursor = next;
    this.host.click();
    this.changed();
  }

  change(d) {
    const p = this.prefs;
    if (this.page === 'apps') {
      if (this.cursor >= 1) {
        const key = this.apps[this.cursor - 1].key;
        if (this.hidden.has(key)) this.hidden.delete(key); else this.hidden.add(key);
        p.hidden = [...this.hidden];
        this.host.click(); this.changed();
      }
      return false;
    }
    switch (ITEMS[this.cursor]) {
      case 'SOUND': p.sound = !p.sound; break;
      case 'VOLUME': p.volume = Math.min(10, Math.max(0, p.volume + d)); break;
      case 'WEATHER': p.weather = !p.weather; this.host.weatherSettingsChanged(); break;
      case 'LOCATION': if (p.cityName) { p.cityName = null; this.host.weatherSettingsChanged(); } break;
      case 'UNITS': p.fahrenheit = !p.fahrenheit; this.host.weatherSettingsChanged(); break;
      case 'CLOCK': p.clock24 = !p.clock24; break;
      case 'SCANLINES': p.scanlines = !p.scanlines; break;
      case 'WOBBLE': p.wobble = !p.wobble; break;
      case 'FULLSCREEN': this.host.toggleFullscreen(); break;
      default: return false;
    }
    this.host.click();
    this.changed();
    return false;
  }

  view() {
    const p = this.prefs;
    if (this.page === 'apps') {
      const rows = [{ label: 'RESET ORDER (A-Z)' }];
      this.apps.forEach((a, i) => {
        const hide = this.hidden.has(a.key);
        rows.push({ label: `${String(i + 1).padStart(2, '0')}  ${a.label.toUpperCase()}`, value: hide ? 'HIDE' : 'SHOW', grabbed: this.grabbed && this.cursor === i + 1, dim: hide });
      });
      if (this.cursor < this.first) this.first = this.cursor;
      if (this.cursor >= this.first + VISIBLE) this.first = this.cursor - VISIBLE + 1;
      return {
        title: 'APP ORDER', rows: rows.slice(this.first, this.first + VISIBLE), cursor: this.cursor - this.first,
        hint: this.grabbed ? 'UP/DOWN MOVE   OK DROP' : 'OK PICK UP   LEFT/RIGHT SHOW/HIDE   BACK DONE',
      };
    }
    const onOff = (b) => (b ? 'ON' : 'OFF');
    const location = p.cityName?.toUpperCase() ?? (this._city ? `AUTO · ${this._city.toUpperCase()}` : 'AUTO');
    const rows = ITEMS.map((it) => ({
      APPS: { label: 'APP ORDER', value: '>' },
      SOUND: { label: 'SOUND', value: onOff(p.sound) },
      VOLUME: { label: 'VOLUME', bar: p.volume },
      WEATHER: { label: 'WEATHER', value: onOff(p.weather) },
      LOCATION: { label: 'LOCATION', value: location, dim: !p.weather },
      UNITS: { label: 'UNITS', value: p.fahrenheit ? '°F' : '°C', dim: !p.weather },
      CLOCK: { label: 'CLOCK', value: p.clock24 ? '24H' : '12H' },
      SCANLINES: { label: 'SCANLINES', value: onOff(p.scanlines) },
      WOBBLE: { label: 'TAPE WOBBLE', value: onOff(p.wobble) },
      FULLSCREEN: { label: 'FULLSCREEN', value: onOff(this.host.isFullscreen()) },
      EXIT: { label: 'EXIT' },
    })[it]);
    return { title: 'VHS SETUP', rows, cursor: this.cursor, hint: 'UP/DOWN SELECT   LEFT/RIGHT CHANGE   OK ENTER   BACK EXIT' };
  }
}
