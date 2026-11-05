package hu.retro.vhslauncher

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object Gl {

    private const val VERTEX = """
        uniform mat4 uMVP;
        attribute vec3 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            vUV = aUV;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """

    // Textures come from Android bitmaps, which GLUtils uploads premultiplied.
    private const val TEXTURED = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform vec4 uTint;
        varying vec2 vUV;
        void main() {
            vec4 c = texture2D(uTex, vUV);
            gl_FragColor = vec4(c.rgb * uTint.rgb * uTint.a, c.a * uTint.a);
        }
    """

    // uMode 0: VHS snow with a rolling tracking band. uMode 1: CRT scanlines plus vignette.
    private const val EFFECTS = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform float uTime;
        uniform int uMode;
        varying vec2 vUV;
        // Sine-free hash: sin()-based hashes band into diagonal stripes on mediump GPUs.
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
        }
    """

    var texProgram = 0; private set
    var fxProgram = 0; private set
    var aPos = 0; private set
    var aUV = 0; private set
    var uMVP = 0; private set
    var uTint = 0; private set
    var uTex = 0; private set
    var fxPos = 0; private set
    var fxUV = 0; private set
    var fxMVP = 0; private set
    var fxTime = 0; private set
    var fxMode = 0; private set
    var white = 0; private set

    /** Unit quad centred on the origin, (-0.5,-0.5) at the top-left with uv (0,0). */
    val quad: FloatBuffer = floatBuffer(
        floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 0f,
            0.5f, -0.5f, 0f, 1f, 0f,
            -0.5f, 0.5f, 0f, 0f, 1f,
            0.5f, 0.5f, 0f, 1f, 1f,
        )
    )

    fun init() {
        texProgram = program(VERTEX, TEXTURED)
        aPos = GLES20.glGetAttribLocation(texProgram, "aPos")
        aUV = GLES20.glGetAttribLocation(texProgram, "aUV")
        uMVP = GLES20.glGetUniformLocation(texProgram, "uMVP")
        uTint = GLES20.glGetUniformLocation(texProgram, "uTint")
        uTex = GLES20.glGetUniformLocation(texProgram, "uTex")

        fxProgram = program(VERTEX, EFFECTS)
        fxPos = GLES20.glGetAttribLocation(fxProgram, "aPos")
        fxUV = GLES20.glGetAttribLocation(fxProgram, "aUV")
        fxMVP = GLES20.glGetUniformLocation(fxProgram, "uMVP")
        fxTime = GLES20.glGetUniformLocation(fxProgram, "uTime")
        fxMode = GLES20.glGetUniformLocation(fxProgram, "uMode")

        val px = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        px.eraseColor(0xFFFFFFFF.toInt())
        white = texture(px)
        px.recycle()
    }

    fun texture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    /** Uploads [bitmap] into an existing texture, or a new one when [id] is 0, and recycles the bitmap. */
    fun replace(id: Int, bitmap: Bitmap): Int {
        if (id != 0) delete(id)
        val t = texture(bitmap)
        bitmap.recycle()
        return t
    }

    fun delete(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src.trimIndent())
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }
}
