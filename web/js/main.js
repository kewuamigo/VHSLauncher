// Browser preview of the VHS Launcher: wires the scene to the keyboard, mouse, touch, the
// iframe "player" and the setup menu.

import { Scene } from './scene.js';
import { SetupMenu } from './menu.js';
import { createPrefs } from './prefs.js';
import { Sfx } from './sfx.js';
import { loadApps } from './apps.js';
import { fetchWeather, geocode } from './weather.js';

const $ = (id) => document.getElementById(id);
const WEATHER_INTERVAL_MS = 30 * 60_000;

const prefs = createPrefs();
const sfx = new Sfx(prefs);
const player = $('player'), frame = $('player-frame');
const cityForm = $('city'), cityInput = $('city-input');
let scene;
let weatherTimer = 0;

const menuHost = {
  askCity() {
    cityInput.value = prefs.cityName ?? '';
    cityForm.hidden = false;
    cityInput.focus();
  },
  toggleFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen?.();
    else document.documentElement.requestFullscreen?.().catch(() => {});
    setTimeout(() => scene.settingsChanged(), 300);
  },
  isFullscreen: () => !!document.fullscreenElement,
  weatherSettingsChanged() { refreshWeather(); scene.settingsChanged(); },
  click: () => sfx.play('switch', true),
};

const sceneHost = {
  /** The tape finished loading: show the app in the iframe. */
  launch(item) {
    frame.title = item.label;
    frame.src = item.url;
    player.hidden = false;
    $('player-back').focus();
  },
  /** Called with null when the tape is ejected. */
  playing(item) {
    if (item) return;
    player.hidden = true;
    frame.src = 'about:blank';
    $('scene').focus();
  },
};

async function boot() {
  try {
    await Promise.all(['VHS-VT323', 'VHS-Brush', '500 1px VHS-Barlow', '700 1px VHS-Barlow']
      .map((f) => document.fonts.load(f.includes('px') ? f : `16px ${f}`)));
    const canvas = $('scene');
    canvas.tabIndex = 0;
    scene = new Scene(canvas, { prefs, sfx, menu: new SetupMenu(prefs, menuHost), host: sceneHost });
    scene.setApps(await loadApps());
    new ResizeObserver(() => scene.resize()).observe(canvas);
    requestAnimationFrame(function loop(t) { scene.frame(t); requestAnimationFrame(loop); });
    refreshWeather();
    bindInput(canvas);
    canvas.focus();
  } catch (e) {
    const err = $('error');
    err.textContent = `NO SIGNAL — ${e.message}`;
    err.hidden = false;
    console.error(e);
  }
}

function refreshWeather() {
  clearTimeout(weatherTimer);
  if (!prefs.weather) { scene.setWeather(null); return; }
  fetchWeather(prefs).then((w) => scene.setWeather(w));
  weatherTimer = setTimeout(refreshWeather, WEATHER_INTERVAL_MS);
}

function dismissHint() { $('hint').classList.add('gone'); }

function bindInput(canvas) {
  window.addEventListener('keydown', (e) => {
    if (!cityForm.hidden) return;
    if (!player.hidden) {
      if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); scene.stop(); }
      return;
    }
    const k = e.key;
    const actions = {
      ArrowLeft: () => scene.move(-1), ArrowRight: () => scene.move(1),
      ArrowUp: () => scene.vertical(-1), ArrowDown: () => scene.vertical(1),
      PageUp: () => scene.move(-5), PageDown: () => scene.move(5),
      Home: () => scene.move(-999), End: () => scene.move(999),
      Enter: () => scene.play(), ' ': () => scene.play(),
      Escape: () => scene.stop(), Backspace: () => scene.stop(),
    };
    if (actions[k]) { e.preventDefault(); actions[k](); dismissHint(); }
  });

  canvas.addEventListener('click', (e) => {
    dismissHint();
    if (scene.inMenu) { scene.play(); return; }
    const a = scene.pick(...scene.toDesign(e.clientX, e.clientY));
    if (a === 'play') scene.play();
    else if (a === 'prev') scene.move(-1);
    else if (a === 'next') scene.move(1);
    else if (a === 'stop') scene.stop();
    else if (a?.shelf) scene.move(a.shelf);
  });
  canvas.addEventListener('contextmenu', (e) => { if (scene.inMenu) { e.preventDefault(); scene.stop(); } });

  let wheelAt = 0;
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const now = performance.now();
    if (now - wheelAt < 120) return;
    wheelAt = now;
    const d = Math.sign(Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY);
    if (scene.inMenu) scene.vertical(d); else scene.move(d);
    dismissHint();
  }, { passive: false });

  // Swipe between tapes on touch screens.
  let touch = null;
  canvas.addEventListener('touchstart', (e) => { touch = e.touches[0] && { x: e.touches[0].clientX, y: e.touches[0].clientY }; }, { passive: true });
  canvas.addEventListener('touchend', (e) => {
    const t = e.changedTouches[0];
    if (!touch || !t) return;
    const dx = t.clientX - touch.x, dy = t.clientY - touch.y;
    touch = null;
    if (Math.abs(dx) < 40 && Math.abs(dy) < 40) return;
    e.preventDefault();
    if (scene.inMenu) scene.vertical(Math.abs(dy) > Math.abs(dx) ? Math.sign(dy) : Math.sign(dx));
    else if (Math.abs(dx) > Math.abs(dy)) scene.move(dx < 0 ? 1 : -1);
    dismissHint();
  });

  $('player-back').addEventListener('click', () => scene.stop());

  cityForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = cityInput.value.trim();
    if (!name) {
      prefs.cityName = null;
    } else {
      const place = await geocode(name);
      if (!place) { cityInput.setCustomValidity(`Couldn't find "${name}"`); cityInput.reportValidity(); return; }
      prefs.cityName = place.name; prefs.cityLat = place.lat; prefs.cityLon = place.lon;
    }
    cityForm.hidden = true;
    canvas.focus();
    menuHost.weatherSettingsChanged();
  });
  cityInput.addEventListener('input', () => cityInput.setCustomValidity(''));
  const closeCity = () => { cityForm.hidden = true; canvas.focus(); };
  $('city-cancel').addEventListener('click', closeCity);
  cityForm.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); closeCity(); } });
}

boot();
