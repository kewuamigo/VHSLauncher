// WebGL 1 helpers and a tiny matrix library with android.opengl.Matrix semantics
// (column-major; translate/rotate/scale post-multiply, so calls read like CSS transforms).

export const mat = {
  create() { const m = new Float32Array(16); m[0] = m[5] = m[10] = m[15] = 1; return m; },
  identity(m) { m.fill(0); m[0] = m[5] = m[10] = m[15] = 1; return m; },
  copy(out, a) { out.set(a); return out; },
  /** out = a * b */
  multiply(out, a, b) {
    const r = new Float32Array(16);
    for (let c = 0; c < 4; c++) for (let row = 0; row < 4; row++) {
      let s = 0;
      for (let k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
      r[c * 4 + row] = s;
    }
    out.set(r);
    return out;
  },
  /** m = m * T */
  translate(m, x, y, z) {
    for (let i = 0; i < 4; i++) m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z;
    return m;
  },
  /** m = m * S */
  scale(m, x, y, z) {
    for (let i = 0; i < 4; i++) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z; }
    return m;
  },
  /** m = m * R(deg about x,y,z) */
  rotate(m, deg, x, y, z) {
    if (deg === 0) return m;
    const a = (deg * Math.PI) / 180, s = Math.sin(a), c = Math.cos(a), t = 1 - c;
    const len = Math.hypot(x, y, z); x /= len; y /= len; z /= len;
    const r = new Float32Array([
      x * x * t + c, y * x * t + z * s, z * x * t - y * s, 0,
      x * y * t - z * s, y * y * t + c, z * y * t + x * s, 0,
      x * z * t + y * s, y * z * t - x * s, z * z * t + c, 0,
      0, 0, 0, 1,
    ]);
    return mat.multiply(m, m, r);
  },
  perspective(m, fovyDeg, aspect, near, far) {
    const f = 1 / Math.tan((fovyDeg * Math.PI) / 360), nf = 1 / (near - far);
    m.fill(0);
    m[0] = f / aspect; m[5] = f; m[10] = (far + near) * nf; m[11] = -1; m[14] = 2 * far * near * nf;
    return m;
  },
  lookAt(m, ex, ey, ez, cx, cy, cz, ux, uy, uz) {
    let fx = cx - ex, fy = cy - ey, fz = cz - ez;
    const fl = Math.hypot(fx, fy, fz); fx /= fl; fy /= fl; fz /= fl;
    let sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
    const sl = Math.hypot(sx, sy, sz); sx /= sl; sy /= sl; sz /= sl;
    const ux2 = sy * fz - sz * fy, uy2 = sz * fx - sx * fz, uz2 = sx * fy - sy * fx;
    m.set([sx, ux2, -fx, 0, sy, uy2, -fy, 0, sz, uz2, -fz, 0, 0, 0, 0, 1]);
    return mat.translate(m, -ex, -ey, -ez);
  },
};

const VERTEX = `
uniform mat4 uMVP;
attribute vec3 aPos;
attribute vec2 aUV;
varying vec2 vUV;
void main() { vUV = aUV; gl_Position = uMVP * vec4(aPos, 1.0); }`;

// Textures are uploaded premultiplied, like Android bitmaps.
const TEXTURED = `
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uTint;
varying vec2 vUV;
void main() {
  vec4 c = texture2D(uTex, vUV);
  gl_FragColor = vec4(c.rgb * uTint.rgb * uTint.a, c.a * uTint.a);
}`;

// uMode 0: VHS snow with a rolling tracking band. uMode 1: CRT scanlines plus vignette.
const EFFECTS = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform float uTime;
uniform int uMode;
varying vec2 vUV;
float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
void main() {
  if (uMode == 0) {
    vec2 cell = floor(vUV * vec2(480.0, 270.0));
    float frame = floor(mod(uTime * 24.0, 997.0));
    float n = hash(cell + frame * vec2(5.17, 9.31));
    float row = hash(vec2(floor(vUV.y * 270.0), frame));
    float band = exp(-pow((vUV.y - fract(uTime * 0.55)) * 9.0, 2.0));
    float v = clamp(n * 0.8 + band * 0.35 + (row - 0.5) * 0.12, 0.0, 1.0);
    gl_FragColor = vec4(vec3(v), 1.0);
  } else {
    float line = mod(floor(vUV.y * 1080.0), 3.0) < 1.0 ? 0.16 : 0.0;
    vec2 d = (vUV - 0.5) / vec2(0.75, 0.70);
    float vig = smoothstep(0.42, 0.72, length(d)) * 0.55;
    float a = 1.0 - (1.0 - line) * (1.0 - vig);
    gl_FragColor = vec4(0.0, 0.0, 0.0, a);
  }
}`;

export class Gl {
  constructor(gl) {
    this.gl = gl;
    this.tex = this.program(VERTEX, TEXTURED, ['uMVP', 'uTint', 'uTex']);
    this.fx = this.program(VERTEX, EFFECTS, ['uMVP', 'uTime', 'uMode']);
    // Unit quad centred on the origin, (-0.5,-0.5) at the top-left with uv (0,0).
    this.quad = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
      -0.5, -0.5, 0, 0, 0, 0.5, -0.5, 0, 1, 0, -0.5, 0.5, 0, 0, 1, 0.5, 0.5, 0, 1, 1,
    ]), gl.STATIC_DRAW);
    const px = document.createElement('canvas'); px.width = px.height = 1;
    const x = px.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, 1, 1);
    this.white = this.texture(px);
  }

  program(vs, fs, uniforms) {
    const gl = this.gl;
    const compile = (type, src) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const u = {};
    for (const n of uniforms) u[n] = gl.getUniformLocation(p, n);
    return { p, aPos: gl.getAttribLocation(p, 'aPos'), aUV: gl.getAttribLocation(p, 'aUV'), u };
  }

  texture(source) {
    const gl = this.gl;
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
    return t;
  }

  /** Uploads `source` into a new texture, deleting `old` if given. */
  replace(old, source) {
    if (old) this.gl.deleteTexture(old);
    return this.texture(source);
  }

  delete(t) { if (t) this.gl.deleteTexture(t); }

  use(prog) {
    const gl = this.gl;
    gl.useProgram(prog.p);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad);
    gl.vertexAttribPointer(prog.aPos, 3, gl.FLOAT, false, 20, 0);
    gl.enableVertexAttribArray(prog.aPos);
    gl.vertexAttribPointer(prog.aUV, 2, gl.FLOAT, false, 20, 12);
    gl.enableVertexAttribArray(prog.aUV);
  }
}
