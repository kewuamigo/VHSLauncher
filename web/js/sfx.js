// Short VCR sounds through Web Audio. Browsers only start audio after a click or key press,
// so the context is resumed on the first user gesture.

const SOUNDS = {
  select: ['sfx_select', 0.8],
  switch: ['sfx_switch', 0.45],
  button: ['sfx_button', 0.7],
  insert: ['sfx_insert', 1],
  eject: ['sfx_eject', 1],
  static: ['sfx_static', 0.6],
};

export class Sfx {
  constructor(prefs) {
    this.prefs = prefs;
    this.ctx = null;
    this.buffers = {};
    const unlock = () => { this.init(); this.ctx?.resume(); };
    for (const ev of ['pointerdown', 'keydown']) window.addEventListener(ev, unlock, { capture: true });
  }

  init() {
    if (this.ctx) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ctx = new AC();
    for (const [name, [file]] of Object.entries(SOUNDS)) {
      fetch(`assets/sounds/${file}.mp3`)
        .then((r) => r.arrayBuffer())
        .then((b) => this.ctx.decodeAudioData(b))
        .then((buf) => { this.buffers[name] = buf; })
        .catch(() => {});
    }
  }

  play(name, vary = false) {
    const p = this.prefs, buf = this.buffers[name];
    if (!p.sound || !this.ctx || !buf) return;
    const v = SOUNDS[name][1] * Math.min(10, Math.max(0, p.volume)) / 10;
    if (v <= 0) return;
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    if (vary) src.playbackRate.value = 0.94 + Math.random() * 0.12;
    const gain = this.ctx.createGain();
    gain.gain.value = v;
    src.connect(gain).connect(this.ctx.destination);
    src.start();
  }
}
