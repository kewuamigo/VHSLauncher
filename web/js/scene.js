// The whole launcher is one WebGL scene in a 1920x1080 design space with y pointing down and
// z towards the viewer, so transforms read like CSS. Ported from SceneRenderer.kt.

import * as art from './art.js';
import { Gl, mat } from './gl.js';
import { shelf, SETUP_KEY } from './apps.js';

const P = { IDLE: 0, INSERTING: 1, LOADING: 2, OSD: 3, PLAYING: 4, MENU: 5, EJECTING: 6 };
const DURATION = { [P.INSERTING]: 1600, [P.LOADING]: 900, [P.OSD]: 900, [P.EJECTING]: 1500 };

const pose = (x, y, z, rx, ry, s, wy = 0, wx = 0) => ({ x, y, z, rx, ry, s, wy, wx });
const lerp = (a, b, t) => a + (b - a) * t;
const lerpPose = (a, b, t) => pose(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t), lerp(a.rx, b.rx, t),
  lerp(a.ry, b.ry, t), lerp(a.s, b.s, t), lerp(a.wy, b.wy, t), lerp(a.wx, b.wx, t));
const ease = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
const seg = (u, a, b) => Math.min(1, Math.max(0, (u - a) / (b - a)));
const pad = (n, w) => String(n).padStart(w, '0');

// Key poses. The door entry pose has the spine flush with the VCR front (z = 0) and exactly as
// wide as the gap between the door's guide rails.
const DOOR_SCALE = 1.407;
const LIFT = pose(960, 330, 240, 42, -18, 1.05);
const entryPose = (dy) => pose(740, 860 + dy, -82.5 * DOOR_SCALE, 90, 0, DOOR_SCALE);
const deepPose = (dy) => pose(740, 856 + dy, -82.5 * DOOR_SCALE - 320, 90, 0, DOOR_SCALE);

function face(rx, ry, push, w, h) {
  const m = mat.create();
  mat.rotate(m, rx, 1, 0, 0); mat.rotate(m, ry, 0, 1, 0);
  mat.translate(m, 0, 0, push); mat.scale(m, w, h, 1);
  return m;
}
const FACES = {
  top: face(0, 0, 24, 300, 165),
  back: face(0, 180, 24, 300, 165),
  spine: face(-90, 0, 82.5, 300, 48),
  flap: face(90, 0, 82.5, 300, 48),
  left: face(0, -90, 150, 48, 165),
  right: face(0, 90, 150, 48, 165),
};

export class Scene {
  /** host: { launch(item), playing(item|null) }; sfx.play(name); menu: SetupMenu */
  constructor(canvas, { prefs, sfx, menu, host }) {
    this.canvas = canvas;
    this.prefs = prefs; this.sfx = sfx; this.menu = menu; this.host = host;
    const gl = canvas.getContext('webgl', { antialias: true, depth: true, alpha: false, premultipliedAlpha: true });
    if (!gl) throw new Error('WebGL is not available');
    this.gl = gl;
    this.g = new Gl(gl);

    this.items = []; this.allApps = []; this.pendingApps = null; this.weather = null;
    this.sel = 0; this.selAnim = 0; this.phase = P.IDLE; this.phaseStart = 0; this.playStart = 0;
    this.lastFrame = performance.now(); this.wobbleT = 0; this.spinT = 0; this.frozenWy = 0; this.frozenWx = 0;
    this.insertSoundPlayed = false;
    this.cassetteTex = new Map();
    this.keys = { display: '', keypad: -1, title: '', clock: '', menu: -1 };
    this.clockSecond = -1; this.clockText = '00:00';

    this.vp = mat.create(); this.model = mat.create(); this.base = mat.create(); this.face = mat.create(); this.mvp = mat.create();
    this.view = { x: 0, y: 0, w: 1, h: 1 };

    this.t = {
      bg: this.g.texture(art.background()),
      vcr: this.g.texture(art.vcr(2)),
      flap: this.g.texture(art.flap(2)),
      hub: this.g.texture(art.hub()),
      flapSide: this.g.texture(art.cassetteFlapSide(2)),
      side: this.g.texture(art.cassetteSide(2)),
      back: this.g.texture(art.cassetteBack(1.5)),
      display: null, keypad: null, title: null, clock: null, osd: null, menu: null,
    };

    gl.clearColor(0, 0, 0, 1);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.depthFunc(gl.LEQUAL);
    gl.disable(gl.CULL_FACE);
    this.resize();
  }

  // ------------------------------------------------------------ commands

  setApps(all) {
    if (this.phase !== P.IDLE) { this.pendingApps = all; return; }
    this.allApps = all;
    const list = shelf(all, this.prefs);
    const keep = this.items[this.sel]?.key;
    this.items = list;
    for (const t of this.cassetteTex.values()) { this.g.delete(t[0]); this.g.delete(t[1]); }
    this.cassetteTex.clear();
    const i = list.findIndex((it) => it.key === keep);
    this.sel = i >= 0 ? i : 0;
    this.selAnim = this.sel;
    this.keys.title = '';
  }

  setWeather(w) { this.weather = w; this.menu.detectedCity = w?.city ?? null; this.keys.display = ''; }

  settingsChanged() { this.menu.refresh(); this.keys.display = ''; this.clockSecond = -1; }

  get busy() { return this.phase !== P.IDLE; }
  get inMenu() { return this.phase === P.MENU; }

  move(d) {
    if (this.phase === P.MENU) { if (d < 0) this.menu.left(); else this.menu.right(); return; }
    if (this.phase !== P.IDLE || !this.items.length) return;
    const next = Math.min(this.items.length - 1, Math.max(0, this.sel + d));
    if (next !== this.sel) { this.sel = next; this.sfx.play('select'); }
  }

  vertical(d) { if (this.phase === P.MENU) { if (d < 0) this.menu.up(); else this.menu.down(); } }

  play() {
    if (this.phase === P.MENU) { if (this.menu.ok()) this.closeMenu(); return; }
    if (this.phase !== P.IDLE || !this.items.length) return;
    this.frozenWy = this.wobbleY(); this.frozenWx = this.wobbleX();
    this.sfx.play('button');
    this.start(P.INSERTING);
  }

  stop() {
    if (this.phase === P.MENU) { if (this.menu.back()) this.closeMenu(); return; }
    if (this.phase === P.LOADING || this.phase === P.OSD || this.phase === P.PLAYING) this.start(P.EJECTING);
  }

  closeMenu() { this.pendingApps = this.allApps; this.start(P.EJECTING); }

  start(p) {
    this.phase = p;
    this.phaseStart = performance.now();
    if (p === P.INSERTING) this.insertSoundPlayed = false;
    if (p === P.LOADING) { this.playStart = this.phaseStart; this.sfx.play('static'); }
    if (p === P.EJECTING) { this.sfx.play('eject'); this.host.playing(null); }
    if (p === P.IDLE && this.pendingApps) { const a = this.pendingApps; this.pendingApps = null; this.setApps(a); }
  }

  /** Maps a click in design pixels to an action. */
  pick(x, y) {
    if (y >= 962 && y <= 1016 && x >= 384 && x <= 1080) {
      const key = art.transportKeys().find((k) => x - 370 >= k.l && x - 370 <= k.r);
      return { 2: 'prev', 3: 'play', 4: 'next', 7: 'stop', 8: 'stop' }[key?.i] ?? null;
    }
    if (y >= 920 && y <= 936 && x >= 1382 && x <= 1494) return x < 1438 ? 'prev' : 'next';
    if (y >= 200 && y <= 600) {
      const d = (x - 960) / 340;
      if (Math.abs(d) < 0.5) return 'play';
      return { shelf: Math.round(d) || Math.sign(d) };
    }
    return null;
  }

  // ------------------------------------------------------------ frame

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.round(this.canvas.clientWidth * dpr), h = Math.round(this.canvas.clientHeight * dpr);
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
    let vw, vh;
    if (w * 9 > h * 16) { vh = h; vw = Math.round((h * 16) / 9); } else { vw = w; vh = Math.round((w * 9) / 16); }
    this.view = { x: Math.round((w - vw) / 2), y: Math.round((h - vh) / 2), w: vw, h: vh };

    const proj = mat.perspective(mat.create(), (2 * Math.atan(540 / 1600) * 180) / Math.PI, 1920 / 1080, 300, 4000);
    const view = mat.lookAt(mat.create(), 960, 540, 1600, 960, 540, 0, 0, 1, 0);
    const flip = mat.scale(mat.translate(mat.create(), 0, 1080, 0), 1, -1, 1);
    mat.multiply(this.vp, mat.multiply(mat.create(), proj, view), flip);
  }

  /** Converts a client-space point to design pixels. */
  toDesign(cx, cy) {
    const r = this.canvas.getBoundingClientRect(), dpr = this.canvas.width / r.width;
    const px = (cx - r.left) * dpr, py = (cy - r.top) * dpr;
    return [((px - this.view.x) / this.view.w) * 1920, ((py - this.view.y) / this.view.h) * 1080];
  }

  frame(now) {
    const gl = this.gl;
    const dt = Math.min(0.1, Math.max(0, (now - this.lastFrame) / 1000));
    this.lastFrame = now;
    this.update(now, dt);
    // While a web app is on screen, nothing here is visible.
    if (this.phase === P.PLAYING) return;

    gl.disable(gl.SCISSOR_TEST);
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    const v = this.view;
    gl.viewport(v.x, v.y, v.w, v.h);
    gl.disable(gl.DEPTH_TEST);
    this.g.use(this.g.tex);
    gl.activeTexture(gl.TEXTURE0);
    gl.uniform1i(this.g.tex.u.uTex, 0);

    this.drawRect(this.t.bg, 0, 0, 1920, 1080);
    if (!this.items.length) { this.drawFx(1, now); return; }

    const u = this.elapsed(now);
    const dy = this.phase === P.INSERTING ? this.shake(u) : 0;
    this.drawRect(this.t.vcr, 370, 790 + dy, 1180, 258);
    this.drawDoor(u, dy);
    this.refreshPanels(now);
    this.drawRect(this.t.display, 992, 808 + dy, 368, 104);
    this.drawRect(this.t.keypad, 1370, 808 + dy, 164, 104);

    // Text goes under the cassettes so a tape flying to the VCR passes in front of it.
    this.refreshText();
    this.drawRect(this.t.title, 160, 640, 1600, 90);
    this.drawRect(this.t.clock, 1520, 36, 320, 80);

    this.drawCarousel(u);

    if (this.phase === P.LOADING) this.drawFx(0, now);
    else if (this.phase === P.OSD) this.drawRect(this.t.osd, 0, 0, 1920, 1080);
    else if (this.phase === P.MENU) {
      if (this.menu.version !== this.keys.menu) { this.keys.menu = this.menu.version; this.t.menu = this.g.replace(this.t.menu, art.menu(this.menu.view())); }
      this.drawRect(this.t.menu, 0, 0, 1920, 1080);
    }
    if (this.prefs.scanlines) this.drawFx(1, now);
  }

  elapsed(now) { return Math.min(1, Math.max(0, (now - this.phaseStart) / (DURATION[this.phase] ?? 1))); }

  update(now, dt) {
    this.selAnim += (this.sel - this.selAnim) * (1 - Math.exp(-dt * 12));
    if (Math.abs(this.sel - this.selAnim) < 0.001) this.selAnim = this.sel;
    this.spinT += dt;
    if (this.phase === P.IDLE && this.prefs.wobble) this.wobbleT += dt;

    const done = now - this.phaseStart >= (DURATION[this.phase] ?? Infinity);
    const item = this.items[this.sel];
    switch (this.phase) {
      case P.INSERTING:
        // The mechanism sound starts as the tape meets the door.
        if (!this.insertSoundPlayed && this.elapsed(now) >= 0.55) { this.insertSoundPlayed = true; this.sfx.play('insert'); }
        if (done) this.start(P.LOADING);
        break;
      case P.LOADING:
        if (done) { if (item) this.t.osd = this.g.replace(this.t.osd, art.osd(item.label)); this.start(P.OSD); }
        break;
      case P.OSD:
        if (done) {
          if (item?.key === SETUP_KEY) { this.menu.open(this.allApps); this.start(P.MENU); }
          else { this.start(P.PLAYING); this.host.launch(item); }
        }
        break;
      case P.EJECTING:
        if (done) this.start(P.IDLE);
        break;
    }
  }

  wobbleY() { return this.prefs.wobble ? -24 * Math.cos((2 * Math.PI * this.wobbleT) / 7) : 0; }
  wobbleX() { return this.prefs.wobble ? 4 * Math.cos((2 * Math.PI * this.wobbleT) / 7) : 0; }

  shake(u) {
    const t = seg(u, 0.84, 0.97);
    return t <= 0 || t >= 1 ? 0 : Math.sin(t * Math.PI * 3) * 3 * (1 - t);
  }

  shelfPose() { return pose(960, 400, 140, 26, 0, 1, this.frozenWy, this.frozenWx); }

  flyingPose(u) {
    if (this.phase === P.INSERTING) {
      if (u < 0.22) return lerpPose(this.shelfPose(), LIFT, ease(seg(u, 0, 0.22)));
      if (u < 0.58) return lerpPose(LIFT, entryPose(0), ease(seg(u, 0.22, 0.58)));
    } else if (this.phase === P.EJECTING) {
      if (u >= 0.41 && u < 0.75) return lerpPose(entryPose(0), LIFT, ease(seg(u, 0.41, 0.75)));
      if (u >= 0.75) return lerpPose(LIFT, this.shelfPose(), ease(seg(u, 0.75, 1)));
    }
    return null;
  }

  doorPose(u, dy) {
    switch (this.phase) {
      case P.INSERTING: {
        if (u < 0.58) return null;
        const t = ease(seg(u, 0.58, 0.88));
        return [lerpPose(entryPose(dy), deepPose(dy), t), 0.6 * t];
      }
      case P.LOADING: case P.OSD: case P.PLAYING: case P.MENU: return [deepPose(dy), 0.6];
      case P.EJECTING: {
        if (u < 0.14) return [deepPose(dy), 0.6];
        if (u < 0.41) { const t = ease(seg(u, 0.14, 0.38)); return [lerpPose(deepPose(dy), entryPose(dy), t), 0.6 * (1 - t)]; }
        return null;
      }
    }
    return null;
  }

  flapAngle(u) {
    if (this.phase === P.INSERTING) {
      if (u < 0.2) return 0;
      if (u < 0.36) return lerp(0, -80, ease(seg(u, 0.2, 0.36)));
      if (u < 0.8) return -80;
      if (u < 0.92) return lerp(-80, 5, ease(seg(u, 0.8, 0.92)));
      return lerp(5, 0, seg(u, 0.92, 1));
    }
    if (this.phase === P.EJECTING) {
      if (u < 0.14) return lerp(0, -80, ease(seg(u, 0, 0.14)));
      if (u < 0.58) return -80;
      if (u < 0.7) return lerp(-80, 4, ease(seg(u, 0.58, 0.7)));
      return lerp(4, 0, seg(u, 0.7, 0.78));
    }
    return 0;
  }

  // ------------------------------------------------------------ drawing

  drawDoor(u, dy) {
    const gl = this.gl;
    gl.enable(gl.SCISSOR_TEST);
    this.scissor(498, 808 + dy, 484, 104);
    const door = this.doorPose(u, dy);
    if (door) {
      gl.enable(gl.DEPTH_TEST);
      gl.clear(gl.DEPTH_BUFFER_BIT);
      this.poseMatrix(door[0]);
      this.drawCassette(this.sel, 1, this.spinT * 150);
      gl.disable(gl.DEPTH_TEST);
      if (door[1] > 0) this.drawRect(this.g.white, 498, 808 + dy, 484, 104, 0, 0, 0, door[1]);
    }
    const m = mat.identity(this.model);
    mat.translate(m, 740, 808 + dy, 0);
    mat.rotate(m, this.flapAngle(u), 1, 0, 0);
    mat.translate(m, 0, 52, 0);
    mat.scale(m, 484, 104, 1);
    this.draw(this.t.flap, m);
    gl.disable(gl.SCISSOR_TEST);
  }

  drawCarousel(u) {
    const gl = this.gl;
    gl.enable(gl.DEPTH_TEST);
    gl.clear(gl.DEPTH_BUFFER_BIT);
    const wy = this.wobbleY(), wx = this.wobbleX();
    const from = Math.max(0, Math.floor(this.selAnim - 5)), to = Math.min(this.items.length - 1, Math.floor(this.selAnim + 5));
    for (let i = from; i <= to; i++) {
      if (i === this.sel && this.phase !== P.IDLE) continue;
      const d = i - this.selAnim, ad = Math.abs(d);
      if (ad > 4.6) continue;
      const f = Math.min(ad, 1), dc = Math.max(-1, Math.min(1, d));
      this.poseMatrix(pose(960 + d * 230 + dc * 110, 400 + 34 * f, 140 * (1 - f) - 80 * ad, 26 - 2 * f, -dc * 38, 1 - 0.08 * f, (1 - f) * wy, (1 - f) * wx));
      this.drawCassette(i, 1 - Math.min(0.7, ad * 0.17), ad < 0.5 ? this.spinT * 150 : i * 23);
    }
    const fly = this.flyingPose(u);
    if (fly) { this.poseMatrix(fly); this.drawCassette(this.sel, 1, this.spinT * 150); }
    gl.disable(gl.DEPTH_TEST);
  }

  poseMatrix(p) {
    const m = mat.identity(this.base);
    mat.translate(m, p.x, p.y, p.z);
    mat.rotate(m, p.rx, 1, 0, 0);
    mat.rotate(m, p.ry, 0, 1, 0);
    mat.scale(m, p.s, p.s, p.s);
    if (p.wy) mat.rotate(m, p.wy, 0, 1, 0);
    if (p.wx) mat.rotate(m, p.wx, 1, 0, 0);
  }

  /** Draws cassette `index` at the transform in this.base. */
  drawCassette(index, bright, hubAngle) {
    const tex = this.cassetteTextures(index);
    this.drawFace(tex[0], FACES.top, bright);
    this.drawFace(tex[1], FACES.spine, bright * 0.88);
    this.drawFace(this.t.flapSide, FACES.flap, bright * 0.72);
    this.drawFace(this.t.side, FACES.left, bright * 0.62);
    this.drawFace(this.t.side, FACES.right, bright * 0.62);
    this.drawFace(this.t.back, FACES.back, bright * 0.5);
    for (const hx of [88, 212]) {
      const m = mat.copy(this.face, this.base);
      mat.translate(m, hx - 150, 122 - 82.5, 25.5);
      mat.rotate(m, hubAngle, 0, 0, 1);
      mat.scale(m, 30, 30, 1);
      this.draw(this.t.hub, m, bright, bright, bright, 1);
    }
  }

  drawFace(tex, local, shade) {
    mat.multiply(this.face, this.base, local);
    this.draw(tex, this.face, shade, shade, shade, 1);
  }

  cassetteTextures(index) {
    const item = this.items[index];
    const key = `${item.key}#${index}`;
    const hit = this.cassetteTex.get(key);
    if (hit) { this.cassetteTex.delete(key); this.cassetteTex.set(key, hit); return hit; }
    const style = item.key === SETUP_KEY ? -1 : index;
    const tex = [this.g.texture(art.cassetteTop(item.label, style, 2)), this.g.texture(art.cassetteSpine(item.label, style, 2))];
    this.cassetteTex.set(key, tex);
    while (this.cassetteTex.size > 14) {
      const [k, t] = this.cassetteTex.entries().next().value;
      this.g.delete(t[0]); this.g.delete(t[1]); this.cassetteTex.delete(k);
    }
    return tex;
  }

  refreshPanels(now) {
    const second = Math.floor(now / 1000);
    if (second !== this.clockSecond) {
      this.clockSecond = second;
      const d = new Date(), h = d.getHours(), m = pad(d.getMinutes(), 2);
      this.clockText = this.prefs.clock24 ? `${pad(h, 2)}:${m}` : `${String(h % 12 === 0 ? 12 : h % 12).padStart(2, ' ')}:${m}`;
    }
    const mode = { [P.IDLE]: 'STOP', [P.INSERTING]: 'LOAD', [P.EJECTING]: 'EJCT' }[this.phase] ?? 'PLAY';
    const counter = this.phase === P.IDLE || this.phase === P.INSERTING ? '0000' : pad(Math.min(9999, Math.floor((now - this.playStart) / 1000)), 4);
    const playing = [P.LOADING, P.OSD, P.PLAYING, P.MENU].includes(this.phase);
    const w = this.prefs.weather ? this.weather : null;
    const key = `${mode}|${counter}|${this.clockText}|${playing}|${w?.text}|${w?.kind}`;
    if (key !== this.keys.display) {
      this.keys.display = key;
      this.t.display = this.g.replace(this.t.display, art.display(mode, counter, this.clockText, playing, this.phase !== P.IDLE, w, 2));
    }
    const lit = this.sel % 12;
    if (lit !== this.keys.keypad) { this.keys.keypad = lit; this.t.keypad = this.g.replace(this.t.keypad, art.keypad(lit, 2)); }
  }

  refreshText() {
    const name = this.items[this.sel]?.label ?? '';
    if (name !== this.keys.title) { this.keys.title = name; this.t.title = this.g.replace(this.t.title, art.title(name)); }
    if (this.clockText !== this.keys.clock) { this.keys.clock = this.clockText; this.t.clock = this.g.replace(this.t.clock, art.clock(this.clockText)); }
  }

  // ------------------------------------------------------------ primitives

  draw(tex, m, r = 1, g = 1, b = 1, a = 1) {
    if (!tex) return;
    const gl = this.gl;
    mat.multiply(this.mvp, this.vp, m);
    gl.uniformMatrix4fv(this.g.tex.u.uMVP, false, this.mvp);
    gl.uniform4f(this.g.tex.u.uTint, r, g, b, a);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  drawRect(tex, x, y, w, h, r = 1, g = 1, b = 1, a = 1) {
    const m = mat.identity(this.model);
    mat.translate(m, x + w / 2, y + h / 2, 0);
    mat.scale(m, w, h, 1);
    this.draw(tex, m, r, g, b, a);
  }

  drawFx(mode, now) {
    const gl = this.gl, fx = this.g.fx;
    this.g.use(fx);
    const m = mat.identity(this.model);
    mat.translate(m, 960, 540, 0); mat.scale(m, 1920, 1080, 1);
    mat.multiply(this.mvp, this.vp, m);
    gl.uniformMatrix4fv(fx.u.uMVP, false, this.mvp);
    gl.uniform1f(fx.u.uTime, (now % 100000) / 1000);
    gl.uniform1i(fx.u.uMode, mode);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    this.g.use(this.g.tex);
  }

  /** Scissor box given in design pixels. */
  scissor(x, y, w, h) {
    const v = this.view, sx = v.w / 1920, sy = v.h / 1080;
    this.gl.scissor(Math.floor(v.x + x * sx), Math.floor(v.y + (1080 - (y + h)) * sy), Math.ceil(w * sx), Math.ceil(h * sy));
  }
}
