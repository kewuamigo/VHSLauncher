// Everything flat in the scene is painted once with Canvas 2D and uploaded as a texture.
// Ported from Art.kt: drawing works in the 1920x1080 design space, `s` is the supersampling factor.

const VT = 'VHS-VT323';
const BRUSH = 'VHS-Brush';
const BARLOW = 'VHS-Barlow';

/** 0xAARRGGBB (as in the Android code) to a CSS colour. */
export const C = (argb) => {
  const a = ((argb >>> 24) & 255) / 255, r = (argb >>> 16) & 255, g = (argb >>> 8) & 255, b = argb & 255;
  return `rgba(${r},${g},${b},${a})`;
};

function canvas(w, h, s) {
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.floor(w * s));
  c.height = Math.max(1, Math.floor(h * s));
  const x = c.getContext('2d');
  x.scale(s, s);
  return [c, x];
}

/** Seeded random, so the wood grain is the same on every load. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const randInt = (r, lo, hi) => lo + Math.floor(r() * (hi - lo));

function rect(c, l, t, r, b, style) { c.fillStyle = style; c.fillRect(l, t, r - l, b - t); }
function round(c, l, t, r, b, rad, style) { c.beginPath(); c.roundRect(l, t, r - l, b - t, rad); c.fillStyle = style; c.fill(); }
function roundStroke(c, l, t, r, b, rad, color, w) { c.beginPath(); c.roundRect(l, t, r - l, b - t, rad); c.strokeStyle = color; c.lineWidth = w; c.stroke(); }
function rectStroke(c, l, t, r, b, color, w) { c.strokeStyle = color; c.lineWidth = w; c.strokeRect(l, t, r - l, b - t); }
function circle(c, x, y, r, style) { c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); c.fillStyle = style; c.fill(); }
function circleStroke(c, x, y, r, color, w) { c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); c.strokeStyle = color; c.lineWidth = w; c.stroke(); }
function line(c, x0, y0, x1, y1, color, w, cap = 'butt') {
  c.beginPath(); c.moveTo(x0, y0); c.lineTo(x1, y1); c.strokeStyle = color; c.lineWidth = w; c.lineCap = cap; c.stroke();
}
function poly(c, pts, style, strokeW = 0) {
  c.beginPath(); c.moveTo(pts[0], pts[1]);
  for (let i = 2; i < pts.length; i += 2) c.lineTo(pts[i], pts[i + 1]);
  c.closePath();
  if (strokeW) { c.strokeStyle = style; c.lineWidth = strokeW; c.lineJoin = 'round'; c.stroke(); } else { c.fillStyle = style; c.fill(); }
}
function vgrad(c, top, bottom, colors, stops) {
  const g = c.createLinearGradient(0, top, 0, bottom);
  colors.forEach((col, i) => g.addColorStop(stops ? stops[i] : i / (colors.length - 1), C(col)));
  return g;
}

/** Sets up text drawing; `spacing` is in pixels like the Android letterSpacing * size. */
function font(c, { face = VT, size, weight = 400, color = 0xffffffff, spacing = 0, align = 'left', glow = null, alpha = 1 }) {
  c.font = `${weight} ${size}px ${face}`;
  c.fillStyle = C(color);
  c.textAlign = align;
  c.textBaseline = 'alphabetic';
  if ('letterSpacing' in c) c.letterSpacing = `${spacing}px`;
  c.globalAlpha = alpha;
  if (glow) { c.shadowColor = C(glow[1]); c.shadowBlur = glow[0]; } else { c.shadowBlur = 0; c.shadowColor = 'transparent'; }
}
function text(c, s, x, y, opts) { c.save(); font(c, opts); c.fillText(s, x, y); c.restore(); }
function measure(c, s, opts) { c.save(); font(c, opts); const w = c.measureText(s).width; c.restore(); return w; }
/** Draws `s` so its glyphs' visual middle sits on `cy`. */
function textMid(c, s, x, cy, opts) {
  c.save(); font(c, opts);
  const m = c.measureText(s);
  const asc = m.fontBoundingBoxAscent ?? opts.size * 0.8, desc = m.fontBoundingBoxDescent ?? opts.size * 0.2;
  c.fillText(s, x, cy + (asc - desc) / 2);
  c.restore();
}
function fit(c, s, maxWidth, opts, minSize = 12) {
  const o = { ...opts };
  while (o.size > minSize && measure(c, s, o) > maxWidth) o.size -= 1;
  return o;
}

// ---------------------------------------------------------------- room

export function background() {
  const [b, c] = canvas(1920, 1080, 1);
  rect(c, 0, 0, 1920, 1080, C(0xff1b140e));
  for (let x = 118; x < 1920; x += 121) rect(c, x, 0, x + 3, 1080, C(0xff120d09));
  const r = rng(7);
  for (let i = 0; i < 900; i++) {
    const sx = r() * 1920;
    line(c, sx, 0, sx + r() * 6 - 3, 1080, `rgba(0,0,0,${randInt(r, 6, 18) / 255})`, 1);
  }
  // Warm lamp glow behind the shelf.
  c.save();
  c.translate(960, 410); c.scale(1, 0.55 / 0.6); c.translate(-960, -410);
  const g = c.createRadialGradient(960, 410, 0, 960, 410, 1150);
  g.addColorStop(0, C(0x22ffaa5a)); g.addColorStop(0.7, C(0x00ffaa5a)); g.addColorStop(1, C(0x00ffaa5a));
  circle(c, 960, 410, 1150, g);
  c.restore();
  drawShelf(c);
  return b;
}

function drawShelf(c) {
  const top = 452;
  rect(c, 60, top + 124, 1860, top + 204, vgrad(c, top + 124, top + 204, [0x99000000, 0x00000000]));
  const surface = new Path2D();
  surface.moveTo(96, top); surface.lineTo(1824, top); surface.lineTo(1920, top + 92); surface.lineTo(0, top + 92); surface.closePath();
  c.fillStyle = vgrad(c, top, top + 92, [0xff24170d, 0xff4a3220, 0xff6a4a2f], [0, 0.45, 1]);
  c.fill(surface);
  c.save();
  c.clip(surface);
  const r = rng(11);
  for (let i = 0; i < 140; i++) {
    const y = top + r() * 92, t = (y - top) / 92;
    c.lineWidth = 0.6 + r() * 1.6;
    c.strokeStyle = r() < 0.5 ? `rgba(255,214,160,${randInt(r, 10, 30) / 255})` : `rgba(0,0,0,${randInt(r, 18, 45) / 255})`;
    const x0 = r() * 1600 - 200, len = 300 + r() * 900;
    c.beginPath(); c.moveTo(x0, y);
    c.bezierCurveTo(x0 + len * 0.3, y + (r() - 0.5) * 4 * t, x0 + len * 0.7, y + (r() - 0.5) * 4 * t, x0 + len, y);
    c.stroke();
  }
  c.restore();
  rect(c, 96, top, 1824, top + 4, C(0xa6000000));
  // Contact shadow under the focused cassette.
  c.save();
  c.translate(960, top + 68); c.scale(1, 0.1); c.translate(-960, -(top + 68));
  const g = c.createRadialGradient(960, top + 68, 0, 960, top + 68, 280);
  g.addColorStop(0, C(0x8c000000)); g.addColorStop(1, 'rgba(0,0,0,0)');
  circle(c, 960, top + 68, 280, g);
  c.restore();
  // Front edge.
  const ft = top + 92;
  rect(c, 0, ft, 1920, ft + 36, vgrad(c, ft, ft + 36, [0xff9a6d48, 0xff6b4a2e, 0xff533822, 0xff3e2a19], [0, 0.12, 0.6, 1]));
  for (let i = 0; i < 60; i++) {
    const y = ft + 3 + r() * 31, x0 = r() * 1920;
    line(c, x0, y, x0 + 200 + r() * 700, y + r() - 0.5, `rgba(0,0,0,${randInt(r, 14, 40) / 255})`, 0.5 + r());
  }
  rect(c, 0, ft + 34, 1920, ft + 36, C(0xff20150c));
  for (const bx of [300, 1602]) {
    rect(c, bx + 4, ft + 42, bx + 22, ft + 100, 'rgba(0,0,0,0.3)');
    const lg = c.createLinearGradient(bx, 0, bx + 18, 0);
    lg.addColorStop(0, C(0xff1d1d1d)); lg.addColorStop(0.45, C(0xff4a4846)); lg.addColorStop(1, C(0xff232323));
    round(c, bx, ft + 36, bx + 18, ft + 94, 3, lg);
    circle(c, bx + 9, ft + 73, 3, C(0xff111111));
  }
}

// ---------------------------------------------------------------- VCR (Orion GHV-1223 P)

const buttonWidths = [64, 64, 66, 98, 66, 64, 64, 98, 64];
const buttonLabels = ['OPERATE', 'TIMER', 'REW/◂◂', 'PLAY', 'FF/▸▸', 'PAUSE/STILL', '· RECORD', 'STOP', 'EJECT'];

/** Where each transport key sits on the VCR front, in VCR-local design pixels. */
export function transportKeys() {
  const keys = [];
  let x = 14;
  for (let i = 0; i < buttonWidths.length; i++) { keys.push({ i, l: x, t: 172, r: x + buttonWidths[i], b: 226 }); x += buttonWidths[i] + 4; }
  return keys;
}

export function vcr(s) {
  const [b, c] = canvas(1180, 258, s);
  round(c, 60, 244, 130, 258, 3, C(0xff0d0c0c));
  round(c, 1050, 244, 1120, 258, 3, C(0xff0d0c0c));
  round(c, 0, 0, 1180, 250, 6, C(0xff1a1817));
  rect(c, 0, 0, 1180, 9, C(0xff24211f));
  rect(c, 0, 9, 1180, 10, C(0x17ffffff));
  rect(c, 0, 248, 1180, 250, C(0xff0c0b0b));

  text(c, 'HQ', 24, 78, { face: BARLOW, weight: 700, size: 34, color: 0xffb7a985, spacing: -1 });

  bezel(c, 128, 18, 612, 122, C(0xff050505));
  bezel(c, 622, 18, 990, 122, C(0xff0a0505));
  bezel(c, 1000, 18, 1164, 122, C(0xff0d0c0e));

  const small = { face: BARLOW, weight: 500, size: 10, color: 0xffbcb7ae, spacing: 0.6, align: 'center' };
  ['MEMORY', 'RESET'].forEach((label, i) => {
    const x = 728 + i * 56;
    round(c, x, 130, x + 50, 144, 2, C(0xff262425));
    text(c, label, x + 25, 157, small);
  });
  rect(c, 860, 128, 972, 162, C(0x5a5a0a0a));
  rectStroke(c, 860.5, 128.5, 971.5, 161.5, C(0xffa8925f), 1);
  const sensor = { face: BARLOW, weight: 500, size: 9.5, color: 0xffc9b68a, spacing: 0.6, align: 'center' };
  const sw = measure(c, 'REMOTE SENSOR', sensor);
  rect(c, 916 - sw / 2 - 3, 158, 916 + sw / 2 + 3, 166, C(0xff1a1817));
  text(c, 'REMOTE SENSOR', 916, 165, sensor);
  ['–', '+'].forEach((sign, i) => {
    const x = 1012 + i * 60;
    round(c, x, 130, x + 52, 146, 2, C(0xff2c4c9c));
    text(c, sign, x + 26, 143, { face: BARLOW, weight: 700, size: 13, color: 0xffe6ecff, align: 'center' });
  });
  text(c, 'PROGRAM', 1068, 159, small);

  c.beginPath(); c.moveTo(106.5, 137); c.lineTo(106.5, 129.5); c.lineTo(335.5, 129.5); c.lineTo(335.5, 137);
  c.strokeStyle = C(0xff8f8a82); c.lineWidth = 1; c.stroke();
  const vs = { face: BARLOW, weight: 500, size: 9.5, color: 0xffbcb7ae, spacing: 1, align: 'center' };
  const vw = measure(c, 'VISUAL SEARCH', vs);
  rect(c, 221 - vw / 2 - 5, 124, 221 + vw / 2 + 5, 134, C(0xff1a1817));
  text(c, 'VISUAL SEARCH', 221, 133, vs);

  const plateText = { face: BARLOW, weight: 500, size: 10.5, color: 0xffbcb7ae, spacing: 0.6, align: 'center' };
  for (const k of transportKeys()) {
    const w = k.r - k.l, x = k.l;
    rect(c, x, 144, x + w, 160, C(0xff26252a));
    if (k.i === 0) {
      c.save(); c.shadowColor = C(0xffff2a1a); c.shadowBlur = 6; circle(c, x + 7, 152, 3, C(0xffff3a2a)); c.restore();
      text(c, buttonLabels[k.i], x + w / 2 + 5, 156, plateText);
    } else text(c, buttonLabels[k.i], x + w / 2, 156, plateText);
    transportKey(c, k.l, k.t, k.r, k.b, k.i);
  }
  rect(c, 14, 164, 1166, 166, C(0xffa3221d));

  round(c, 712, 172, 1166, 226, 2, C(0xff171615));
  rect(c, 712, 172, 1166, 173, C(0xff2a2725));
  rect(c, 712, 226, 1166, 228, C(0xff0a0a0a));
  text(c, 'Video Cassette Recorder', 1078, 205, { face: BARLOW, weight: 500, size: 15, color: 0xff96918a, spacing: 0.5, align: 'right' });
  circleStroke(c, 1117, 199, 18, C(0xff8b8680), 2);
  text(c, 'ORION', 1117, 202, { face: BARLOW, weight: 700, size: 8, color: 0xffa29d96, spacing: 0.5, align: 'center' });
  return b;
}

function bezel(c, l, t, r, b, inside) {
  round(c, l - 2, t - 2, r + 2, b + 2, 4, C(0xff2b2826));
  round(c, l, t, r, b, 3, inside);
  rect(c, l, t, r, t + 8, vgrad(c, t, t + 8, [0xaa000000, 0x00000000]));
}

function transportKey(c, l, t, r, b, i) {
  round(c, l, t + 2, r, b + 2, 2, C(0xff0a0a0a));
  round(c, l, t, r, b, 2, vgrad(c, t, b, [0xff252324, 0xff1a1919]));
  rect(c, l + 1, t, r - 1, t + 1, C(0x12ffffff));
  const cx = (l + r) / 2, grille = i === 3 || i === 7, cy = grille ? (t + b) / 2 - 5 : (t + b) / 2;
  const light = C(0xffd9d6d0), green = C(0xff3fbf6a);
  const tri = (x, dir, col, w = 10) => poly(c, dir > 0 ? [x, cy - 7, x + w, cy, x, cy + 7] : [x, cy - 7, x - w, cy, x, cy + 7], col, 2);
  switch (i) {
    case 0:
      c.beginPath(); c.ellipse(cx, cy + 1, 7, 7, 0, (-50 * Math.PI) / 180, (230 * Math.PI) / 180);
      c.strokeStyle = light; c.lineWidth = 2; c.lineCap = 'round'; c.stroke();
      line(c, cx, cy - 8, cx, cy, light, 2, 'round'); break;
    case 1:
      circleStroke(c, cx, cy, 7, light, 2); line(c, cx, cy - 4, cx, cy, light, 2, 'round'); line(c, cx, cy, cx + 3, cy + 2, light, 2, 'round'); break;
    case 2: tri(cx + 1, -1, green); tri(cx + 11, -1, green); break;
    case 3: tri(cx - 4, 1, green, 12); break;
    case 4: tri(cx - 11, 1, green); tri(cx - 1, 1, green); break;
    case 5: line(c, cx - 4, cy - 7, cx - 4, cy + 7, light, 2, 'round'); line(c, cx + 4, cy - 7, cx + 4, cy + 7, light, 2, 'round'); break;
    case 6: circleStroke(c, cx, cy, 6.5, C(0xffe0362c), 2.2); break;
    case 7: rectStroke(c, cx - 6, cy - 6, cx + 6, cy + 6, light, 2); break;
    case 8: poly(c, [cx, cy - 7, cx + 7, cy + 1, cx - 7, cy + 1], light, 2); line(c, cx - 7, cy + 6, cx + 7, cy + 6, light, 2, 'round'); break;
  }
  if (grille) {
    for (let gx = cx - 28; gx <= cx + 28; gx += 5) {
      circle(c, gx, (t + b) / 2 + 12, 1, C(0xff3c3a3a));
      circle(c, gx + 2.5, (t + b) / 2 + 15, 1, C(0xff3c3a3a));
    }
  }
}

/** The smoked-glass door flap; it rotates about its top edge. */
export function flap(s) {
  const [b, c] = canvas(484, 104, s);
  rect(c, 0, 0, 484, 104, vgrad(c, 0, 104, [0x9e2e2c2e, 0xcc08080a]));
  rect(c, 0, 0, 484, 1, C(0x1affffff));
  round(c, 24, 20, 31, 82, 3, C(0xff252324));
  round(c, 453, 20, 460, 82, 3, C(0xff252324));
  const logo = { face: BARLOW, weight: 700, size: 20, color: 0xffe9e5dc, spacing: 1, align: 'center' };
  const lw = measure(c, 'VHS', logo);
  roundStroke(c, 242 - lw / 2 - 7, 26, 242 + lw / 2 + 7, 50, 3, C(0xffdcd8cf), 2);
  textMid(c, 'VHS', 242, 38.5, logo);
  text(c, 'Front Loading System', 242, 66, { face: BARLOW, weight: 500, size: 14, color: 0xff9a958d, spacing: 0.5, align: 'center' });
  return b;
}

/** Vacuum-fluorescent display: weather, mode, tape counter, clock. */
export function display(mode, counter, clock, playing, tape, weather, s) {
  const [b, c] = canvas(368, 104, s);
  rect(c, 0, 0, 368, 104, C(0xff0a0505));
  if (weather) {
    pixelIcon(c, weather.kind, 20, 11, 2.5);
    text(c, weather.text, 56, 33, { size: 28, color: 0xffbfe6ff, spacing: 1, glow: [6, 0xff63b8ff] });
  }
  if (playing) {
    c.save(); c.shadowColor = C(0xffff4a10); c.shadowBlur = 5; poly(c, [152, 20, 164, 27, 152, 34], C(0xffff6a2a)); c.restore();
  }
  if (tape) text(c, 'SP', 174, 34, { size: 16, color: 0xffff4040, glow: [4, 0xffff2020] });
  text(c, mode, 20, 82, { size: 46, color: 0xffff6a2a, spacing: 3, glow: [9, 0xffff4a10] });
  text(c, counter, 140, 82, { size: 30, color: 0xffbfe6ff, spacing: 2, glow: [7, 0xff63b8ff] });
  text(c, clock, 352, 76, { size: 48, color: 0xffd6f1ff, spacing: 2, glow: [9, 0xff63b8ff], align: 'right' });
  return b;
}

// 12x9 VFD segment art. '#' cyan, 'o' orange, '+' pale blue.
const icons = {
  SUN: ['....o..o....', '.o........o.', '....oooo....', '...oooooo...', 'o..oooooo..o', '...oooooo...', '....oooo....', '.o........o.', '....o..o....'],
  MOON: ['....####....', '...##.......', '..##........', '..##........', '..##.....+..', '..##........', '...##.......', '....####....', '............'],
  PARTLY: ['......o..o..', '.......oooo.', '......oooooo', '....###oooo.', '...#####....', '..#######...', '.##########.', '############', '.##########.'],
  CLOUD: ['............', '....###.....', '...#####....', '..#######...', '.##########.', '############', '############', '.##########.', '............'],
  FOG: ['............', '############', '............', '.##########.', '............', '############', '............', '.##########.', '............'],
  RAIN: ['....###.....', '...#####....', '..#######...', '.#########..', '############', '............', '.+..+..+..+.', '+..+..+..+..', '............'],
  SNOW: ['....###.....', '...#####....', '..#######...', '.#########..', '############', '............', '.+...+...+..', '...+...+...+', '.+...+...+..'],
  STORM: ['....###.....', '...#####....', '..#######...', '.#########..', '############', '.....oo.....', '....oo......', '.....oo.....', '....o.......'],
};

function pixelIcon(c, kind, x, y, px) {
  const rows = icons[kind];
  if (!rows) return;
  const colors = { '#': [0xffbfe6ff, 0xff63b8ff], o: [0xffff6a2a, 0xffff4a10], '+': [0xff8fd0ff, 0xff63b8ff] };
  c.save();
  c.shadowBlur = 3;
  rows.forEach((lineStr, r) => [...lineStr].forEach((ch, col) => {
    const p = colors[ch];
    if (!p) return;
    c.shadowColor = C(p[1]);
    rect(c, x + col * px, y + r * px, x + col * px + px * 0.85, y + r * px + px * 0.85, C(p[0]));
  }));
  c.restore();
}

/** The SETUP tape's blue on-screen menu. Painted at half resolution like the PLAY screen. */
export function menu(view) {
  const [b, c] = canvas(1920, 1080, 0.5);
  rect(c, 0, 0, 1920, 1080, C(0xff1a2bc4));
  const title = { size: 72, spacing: 6 };
  text(c, view.title, 123, 140, { ...title, color: 0x99ff285a });
  text(c, view.title, 117, 140, { ...title, color: 0x9928d8ff });
  text(c, view.title, 120, 140, { ...title, color: 0xffffffff });
  rect(c, 120, 160, 1800, 163, C(0x99ffffff));
  view.rows.forEach((row, i) => {
    const y = 240 + i * 66, selected = i === view.cursor;
    const alpha = row.dim ? 120 / 255 : selected ? 1 : 215 / 255;
    if (selected) poly(c, [124, y - 32, 150, y - 17, 124, y - 2], '#fff');
    if (row.grabbed) rectStroke(c, 162.5, y - 44, 1810, y + 12, '#fff', 3);
    text(c, row.label, 174, y, { size: 46, spacing: 3, alpha });
    if (row.bar >= 0) {
      c.save(); c.globalAlpha = alpha;
      for (let k = 0; k < 10; k++) {
        const l = 1800 - (10 - k) * 34;
        if (k < row.bar) rect(c, l, y - 34, l + 26, y, '#fff'); else rectStroke(c, l, y - 34, l + 26, y, '#fff', 3);
      }
      c.restore();
    } else if (row.value) text(c, row.value, 1800, y, { size: 46, spacing: 3, alpha, align: 'right' });
  });
  text(c, view.hint, 120, 1030, { size: 32, spacing: 2, alpha: 190 / 255 });
  return b;
}

/** The 12-key programme pad; `lit` (0..11) glows green. */
export function keypad(lit, s) {
  const [b, c] = canvas(164, 104, s);
  rect(c, 0, 0, 164, 104, C(0xff0d0c0e));
  const letter = { face: BARLOW, weight: 700, size: 9, color: 0xff8e8a84, align: 'center' };
  const num = { face: BARLOW, weight: 500, size: 11, color: 0xffa29fa6, align: 'center' };
  const litNum = { face: BARLOW, weight: 700, size: 11, color: 0xff8dff74, align: 'center', glow: [5, 0xff4cff2c] };
  const col = 25;
  for (let i = 0; i < 6; i++) {
    const cx = 7 + col * i + col / 2 - 1;
    text(c, String.fromCharCode(65 + i), cx, 22, letter);
    text(c, String.fromCharCode(71 + i), cx, 91, letter);
    for (let row = 0; row < 2; row++) {
      const k = row * 6 + i, top = 30 + row * 24;
      round(c, cx - 11, top, cx + 11, top + 20, 2, C(0xff1c1b1e));
      roundStroke(c, cx - 11, top, cx + 11, top + 20, 2, C(0xff3a383c), 1);
      textMid(c, String(k + 1), cx, top + 10, k === lit ? litNum : num);
    }
  }
  return b;
}

// ---------------------------------------------------------------- cassettes

const labelStyles = [
  { ink: 0xff1b1b1b, rot: -0.6, paint: (c, l, t, r, b) => { rect(c, l, t, r, b, C(0xfff2eee3)); rect(c, l, t, r, t + (b - t) * 0.13, C(0xffc8341f)); } },
  { ink: 0xff1f3d91, rot: 0.5, paint: (c, l, t, r, b) => {
    rect(c, l, t, r, b, C(0xfff6f2e6));
    for (let y = t + (b - t) * 0.22; y < b; y += (b - t) * 0.23) rect(c, l, y, r, y + 1, C(0x592850aa));
  } },
  { ink: 0xff1c2a55, rot: 0, paint: (c, l, t, r, b) => { rect(c, l, t, r, b, C(0xffd7e6f3)); rect(c, l, t, r, t + (b - t) * 0.14, C(0xff23366e)); } },
  { ink: 0xff111111, rot: -0.3, paint: (c, l, t, r, b) => {
    rect(c, l, t, r, b, C(0xffd9d9d9));
    const u = (b - t) * 0.057;
    rect(c, l, t, r, t + u, C(0xff111111)); rect(c, l, t + u, r, t + 2 * u, C(0xffc4c4c4)); rect(c, l, t + 2 * u, r, t + 3 * u, C(0xff111111));
  } },
  { ink: 0xff111111, rot: -1.4, paint: (c, l, t, r, b) => rect(c, l, t, r, b, C(0xffe7d9b0)) },
  { ink: 0xff121212, rot: 0, paint: (c, l, t, r, b) => { rect(c, l, t, r, b, C(0xfff0bf2c)); rect(c, l, t, r, t + (b - t) * 0.11, C(0xff121212)); } },
  { ink: 0xff2a1a10, rot: 0.7, paint: (c, l, t, r, b) => {
    rect(c, l, t, r, b, C(0xffe8e0cc));
    const h = b - t;
    rect(c, l, t + h * 0.74, r, t + h * 0.82, C(0xffe07b24)); rect(c, l, t + h * 0.82, r, t + h * 0.9, C(0xffa8441b)); rect(c, l, t + h * 0.9, r, b, C(0xff5a2a14));
  } },
  { ink: 0xff222222, rot: 0, paint: (c, l, t, r, b) => { rect(c, l, t, r, b, C(0xfffbfaf6)); rect(c, l, t + (b - t) * 0.28, r, t + (b - t) * 0.28 + 1, C(0xffd23b3b)); } },
  { ink: 0xffb3261e, rot: 0.9, paint: (c, l, t, r, b) => rect(c, l, t, r, b, C(0xffd9d4c7)) },
];
/** The setup tape: a red service-tape label. */
const setupStyle = { ink: 0xfff6f2e6, rot: -0.4, paint: (c, l, t, r, b) => { rect(c, l, t, r, b, C(0xff9e1f1a)); rect(c, l, b - (b - t) * 0.12, r, b, C(0xff111111)); } };
/** Negative indexes select the setup tape's label. */
const style = (index) => (index < 0 ? setupStyle : labelStyles[index % labelStyles.length]);

/** Tape-pack diameters seen through the window, varied per cassette. */
function packs(index) { const left = 74 + ((Math.abs(index) * 37) % 60); return [left, 208 - left]; }

function label(c, l, t, r, b, st, block) {
  c.save();
  c.translate((l + r) / 2, (t + b) / 2); c.rotate((st.rot * Math.PI) / 180); c.translate(-(l + r) / 2, -(t + b) / 2);
  rect(c, l, t + 1, r, b + 1, C(0x55000000));
  c.save(); c.beginPath(); c.rect(l, t, r - l, b - t); c.clip(); st.paint(c, l, t, r, b); block(); c.restore();
  c.restore();
}

/** Window face: label sticker on top, reel window below. Hubs are separate quads so they can spin. */
export function cassetteTop(name, index, s) {
  const [b, c] = canvas(300, 165, s);
  const g = c.createLinearGradient(122, 0, 178, 165);
  g.addColorStop(0, C(0xff2a2a2a)); g.addColorStop(0.55, C(0xff121212)); g.addColorStop(1, C(0xff0c0c0c));
  round(c, 0, 0, 300, 165, 6, g);
  roundStroke(c, 0.5, 0.5, 299.5, 164.5, 6, C(0xff3a3a3a), 1);
  rect(c, 6, 1, 294, 3, C(0x24ffffff));

  const st = style(index);
  label(c, 14, 12, 286, 82, st, () => {
    text(c, 'VHS · E-180', 276, 22, { face: BARLOW, weight: 700, size: 9, color: st.ink, spacing: 1, align: 'right', alpha: 180 / 255 });
    textMid(c, name, 30, 51, fit(c, name, 236, { face: BRUSH, size: 46, color: st.ink }));
  });

  const [wl, wt, wr, wb] = [58, 94, 242, 150];
  c.save();
  c.beginPath(); c.rect(wl, wt, wr - wl, wb - wt); c.clip();
  rect(c, wl, wt, wr, wb, C(0xff17110d));
  const [pl, pr] = packs(index);
  for (const [cx, d] of [[88, pl], [212, pr]]) {
    circle(c, cx, 122, d / 2, C(0xff3a281b));
    circleStroke(c, cx, 122, (d / 2) * 0.6, C(0xff2b1d13), 1);
  }
  rect(c, wl, wt, wr, wt + 8, vgrad(c, wt, wt + 8, [0xcc000000, 0x00000000]));
  const ang = (115 * Math.PI) / 180, vx = Math.sin(ang), vy = -Math.cos(ang);
  const len = (wr - wl) * vx + (wb - wt) * -vy, mx = (wl + wr) / 2, my = (wt + wb) / 2;
  const gl = c.createLinearGradient(mx - (vx * len) / 2, my - (vy * len) / 2, mx + (vx * len) / 2, my + (vy * len) / 2);
  [[0, 0x29ffffff], [0.18, 0x29ffffff], [0.18, 0], [0.6, 0], [0.6, 0x12ffffff], [0.66, 0x12ffffff], [0.66, 0], [1, 0]].forEach(([p, col]) => gl.addColorStop(p, C(col)));
  rect(c, wl, wt, wr, wb, gl);
  c.restore();
  roundStroke(c, 59, 95, 241, 149, 4, C(0xff050505), 2);
  for (const [x, y] of [[13, 153], [287, 153], [13, 89], [287, 89]]) { circle(c, x, y, 3, C(0xff2c2c2c)); circleStroke(c, x, y, 3, C(0xff050505), 1); }
  return b;
}

/** The label spine that faces you on the shelf and goes into the VCR last. */
export function cassetteSpine(name, index, s) {
  const [b, c] = canvas(300, 48, s);
  rect(c, 0, 0, 300, 48, vgrad(c, 0, 48, [0xff1e1e1e, 0xff0b0b0b]));
  rect(c, 0, 0, 300, 1, C(0x1fffffff));
  const st = style(index);
  label(c, 10, 6, 290, 42, st, () => {
    textMid(c, 'SP · 180', 280, 24, { face: BARLOW, weight: 700, size: 10, color: st.ink, spacing: 1, align: 'right' });
    textMid(c, name, 22, 25, fit(c, name, 200, { face: BRUSH, size: 28, color: st.ink }));
  });
  return b;
}

export function cassetteFlapSide(s) {
  const [b, c] = canvas(300, 48, s);
  rect(c, 0, 0, 300, 48, C(0xff141414));
  rect(c, 0, 0, 300, 2, C(0xff262626));
  rect(c, 0, 45, 300, 48, C(0xff262626));
  rect(c, 20, 20, 280, 22, C(0xff0c0c0c));
  return b;
}

export function cassetteSide(s) {
  const [b, c] = canvas(48, 165, s);
  rect(c, 0, 0, 48, 165, C(0xff151515));
  for (let y = 5; y < 165; y += 7) rect(c, 0, y, 48, y + 2, C(0xff0b0b0b));
  rectStroke(c, 0.5, 0.5, 47.5, 164.5, C(0xff262626), 1);
  return b;
}

export function cassetteBack(s) {
  const [b, c] = canvas(300, 165, s);
  round(c, 0, 0, 300, 165, 6, C(0xff0e0e0e));
  roundStroke(c, 0.5, 0.5, 299.5, 164.5, 6, C(0xff222222), 1);
  for (const cx of [66, 234]) { circle(c, cx, 82, 26, C(0xff080808)); circle(c, cx, 82, 12, C(0xff1a1a1a)); circle(c, cx, 82, 10.4, C(0xff050505)); }
  return b;
}

export function hub() {
  const [b, c] = canvas(30, 30, 64 / 30);
  const cream = C(0xffebe7dc);
  for (let i = 0; i < 6; i++) {
    const a0 = ((i * 60 - 90) * Math.PI) / 180, a1 = a0 + (22 * Math.PI) / 180;
    c.beginPath(); c.moveTo(15, 15); c.arc(15, 15, 15, a0, a1); c.closePath(); c.fillStyle = cream; c.fill();
  }
  circle(c, 15, 15, 9, cream);
  circle(c, 15, 15, 5, C(0xff17110d));
  return b;
}

// ---------------------------------------------------------------- text and screens

export function title(name) {
  const [b, c] = canvas(1600, 90, 1);
  const s = name.toUpperCase();
  textMid(c, s, 800, 45, fit(c, s, 1500, { size: 66, color: 0xfff3ecdc, spacing: 6, align: 'center', glow: [12, 0x55ffbe78] }));
  return b;
}

export function clock(time) {
  const [b, c] = canvas(320, 80, 1);
  textMid(c, time, 308, 40, { size: 56, color: 0xffcfeeff, spacing: 3, align: 'right', glow: [10, 0x8c78c8ff] });
  return b;
}

function ghosted(c, s, x, y, opts, off) {
  text(c, s, x + off, y, { ...opts, color: 0x99ff285a });
  text(c, s, x - off, y, { ...opts, color: 0x9928d8ff });
  text(c, s, x, y, { ...opts, color: 0xffffffff });
}

/** Classic blue VCR on-screen display shown while the app starts. Painted at half resolution on purpose. */
export function osd(name) {
  const [b, c] = canvas(1920, 1080, 0.5);
  rect(c, 0, 0, 1920, 1080, C(0xff1a2bc4));
  const big = { size: 64, spacing: 4 };
  poly(c, [82, 74, 118, 96, 82, 118], '#fff');
  ghosted(c, 'PLAY', 136, 116, big, 3);
  ghosted(c, 'SP', 1840 - measure(c, 'SP', big), 116, big, 3);
  const s = name.toUpperCase();
  ghosted(c, s, 960, 500, fit(c, s, 1700, { size: 150, spacing: 12, align: 'center' }), 4);
  text(c, 'LOADING...', 960, 590, { size: 48, spacing: 6, align: 'center' });
  return b;
}
