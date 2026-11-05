package hu.retro.vhslauncher

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The whole launcher is one GL scene laid out in a 1920x1080 design space with y pointing down
 * and z towards the viewer, so transforms read like CSS. The camera sits 1600 units in front of
 * the screen plane, where one unit is one design pixel.
 *
 * All methods run on the GL thread; the activity reaches them through queueEvent.
 */
class SceneRenderer(
    private val art: Art,
    private val host: Host,
    private val prefs: Prefs,
    private val sfx: Sfx,
    private val menu: SetupMenu,
) : GLSurfaceView.Renderer {

    interface Host {
        fun launch(item: LaunchItem)
        fun showDetails(item: LaunchItem)
    }

    /** MENU: the setup tape is playing and its blue menu is on screen. */
    private enum class Phase { IDLE, INSERTING, LOADING, OSD, PLAYING, MENU, EJECTING }

    private class Pose(
        val x: Float, val y: Float, val z: Float,
        val rx: Float, val ry: Float, val s: Float,
        val wy: Float = 0f, val wx: Float = 0f,
    )

    // ------------------------------------------------------------ state

    private var items: List<LaunchItem> = emptyList()
    private var allApps: List<LaunchItem> = emptyList()
    private var pendingApps: List<LaunchItem>? = null
    private var weather: Weather? = null
    private var insertSoundPlayed = false
    private var sel = 0
    private var selAnim = 0f
    private var phase = Phase.IDLE
    private var phaseStart = 0L
    private var playStart = 0L
    private var ejectAt = 0L
    private var lastFrame = 0L
    private var wobbleT = 0f
    private var spinT = 0f
    private var frozenWy = 0f
    private var frozenWx = 0f

    // ------------------------------------------------------------ GL

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val flip = FloatArray(16)
    private val pv = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val base = FloatArray(16)
    private val face = FloatArray(16)
    private val mvp = FloatArray(16)
    private var vx = 0; private var vy = 0; private var vw = 1; private var vh = 1

    private val faceTop = FloatArray(16)
    private val faceBack = FloatArray(16)
    private val faceSpine = FloatArray(16)
    private val faceFlap = FloatArray(16)
    private val faceLeft = FloatArray(16)
    private val faceRight = FloatArray(16)

    private var texBg = 0; private var texVcr = 0; private var texFlap = 0; private var texHub = 0
    private var texFlapSide = 0; private var texSide = 0; private var texBack = 0
    private var texDisplay = 0; private var texKeypad = 0; private var texTitle = 0
    private var texClock = 0; private var texOsd = 0; private var texMenu = 0; private var menuDrawn = -1
    private var displayKey = ""; private var keypadLit = -1; private var titleKey = ""; private var clockKey = ""
    private var clockSecond = -1L; private var clockText = "00:00"

    /** Per-cassette label textures (top, spine), least recently used first. */
    private val cassetteTex = LinkedHashMap<String, IntArray>(16, 0.75f, true)

    init {
        buildFace(faceTop, 0f, 0f, 24f, 300f, 165f)
        buildFace(faceBack, 0f, 180f, 24f, 300f, 165f)
        buildFace(faceSpine, -90f, 0f, 82.5f, 300f, 48f)
        buildFace(faceFlap, 90f, 0f, 82.5f, 300f, 48f)
        buildFace(faceLeft, 0f, -90f, 150f, 48f, 165f)
        buildFace(faceRight, 0f, 90f, 150f, 48f, 165f)
    }

    private fun buildFace(out: FloatArray, rx: Float, ry: Float, push: Float, w: Float, h: Float) {
        Matrix.setIdentityM(out, 0)
        if (rx != 0f) Matrix.rotateM(out, 0, rx, 1f, 0f, 0f)
        if (ry != 0f) Matrix.rotateM(out, 0, ry, 0f, 1f, 0f)
        Matrix.translateM(out, 0, 0f, 0f, push)
        Matrix.scaleM(out, 0, w, h, 1f)
    }

    // ------------------------------------------------------------ commands from the activity

    /** All installed apps; the shelf is built from them with the user's order and hidden list. */
    fun setApps(all: List<LaunchItem>) {
        if (phase != Phase.IDLE) { pendingApps = all; return }
        allApps = all
        val list = AppRepository.shelf(all, prefs)
        val keep = items.getOrNull(sel)?.key
        val same = list.size == items.size && list.indices.all { list[it].key == items[it].key && list[it].label == items[it].label }
        items = list
        if (!same) {
            cassetteTex.values.forEach { Gl.delete(it[0]); Gl.delete(it[1]) }
            cassetteTex.clear()
        }
        sel = list.indexOfFirst { it.key == keep }.let { if (it >= 0) it else 0 }
        selAnim = sel.toFloat()
        titleKey = ""
    }

    fun setWeather(w: Weather?) {
        weather = w
        menu.detectedCity = w?.city
        displayKey = ""
    }

    /** Settings changed outside the menu (a city was typed in, the Home service toggled). */
    fun settingsChanged() {
        menu.refresh()
        displayKey = ""; clockSecond = -1
    }

    /** Left / right on the remote. */
    fun move(delta: Int) {
        if (phase == Phase.MENU) { if (delta < 0) menu.left() else menu.right(); return }
        if (phase != Phase.IDLE || items.isEmpty()) return
        val next = (sel + delta).coerceIn(0, items.size - 1)
        if (next != sel) { sel = next; sfx.play(Sfx.Sound.SELECT) }
    }

    /** Up / down on the remote; only the menu uses them. */
    fun vertical(delta: Int) {
        if (phase == Phase.MENU) { if (delta < 0) menu.up() else menu.down() }
    }

    fun play() {
        if (phase == Phase.MENU) { if (menu.ok()) closeMenu(); return }
        if (phase != Phase.IDLE || items.isEmpty()) return
        frozenWy = wobbleY(); frozenWx = wobbleX()
        sfx.play(Sfx.Sound.BUTTON)
        start(Phase.INSERTING)
    }

    fun stop() {
        when (phase) {
            Phase.MENU -> if (menu.back()) closeMenu()
            Phase.LOADING, Phase.OSD, Phase.PLAYING -> start(Phase.EJECTING)
            else -> Unit
        }
    }

    fun details() {
        if (phase == Phase.IDLE) items.getOrNull(sel)?.takeIf { !it.isSetup }?.let(host::showDetails)
    }

    /** Coming back to the launcher with a tape in the machine ejects it. */
    fun onHostResume() {
        if (phase == Phase.PLAYING) ejectAt = SystemClock.uptimeMillis() + 350
        if (phase == Phase.MENU) menu.refresh()
    }

    private fun closeMenu() {
        // The shelf is rebuilt with the new order once the tape is back on it.
        pendingApps = allApps
        start(Phase.EJECTING)
    }

    fun launchFailed() {
        if (phase == Phase.PLAYING) start(Phase.EJECTING)
    }

    private fun start(p: Phase) {
        phase = p
        phaseStart = SystemClock.uptimeMillis()
        when (p) {
            Phase.INSERTING -> insertSoundPlayed = false
            Phase.LOADING -> { playStart = phaseStart; sfx.play(Sfx.Sound.STATIC) }
            Phase.EJECTING -> sfx.play(Sfx.Sound.EJECT)
            Phase.IDLE -> pendingApps?.let { pendingApps = null; setApps(it) }
            else -> Unit
        }
    }

    // ------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A fresh context: every texture id from before is gone.
        Gl.init()
        cassetteTex.clear()
        texDisplay = 0; texKeypad = 0; texTitle = 0; texClock = 0; texOsd = 0; texMenu = 0; menuDrawn = -1
        displayKey = ""; keypadLit = -1; titleKey = ""; clockKey = ""
        texBg = Gl.replace(0, art.background())
        texVcr = Gl.replace(0, art.vcr(2f))
        texFlap = Gl.replace(0, art.flap(2f))
        texHub = Gl.replace(0, art.hub())
        texFlapSide = Gl.replace(0, art.cassetteFlapSide(2f))
        texSide = Gl.replace(0, art.cassetteSide(2f))
        texBack = Gl.replace(0, art.cassetteBack(1.5f))
        if (phase == Phase.OSD || phase == Phase.PLAYING) items.getOrNull(sel)?.let { texOsd = Gl.replace(0, art.osd(it.label)) }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        lastFrame = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // Letterbox to 16:9.
        if (width * 9 > height * 16) { vh = height; vw = height * 16 / 9 } else { vw = width; vh = width * 9 / 16 }
        vx = (width - vw) / 2; vy = (height - vh) / 2

        val fovy = Math.toDegrees(2.0 * atan(540.0 / 1600.0)).toFloat()
        Matrix.perspectiveM(proj, 0, fovy, 1920f / 1080f, 300f, 4000f)
        Matrix.setLookAtM(view, 0, 960f, 540f, 1600f, 960f, 540f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(flip, 0)
        Matrix.translateM(flip, 0, 0f, 1080f, 0f)
        Matrix.scaleM(flip, 0, 1f, -1f, 1f)
        Matrix.multiplyMM(pv, 0, proj, 0, view, 0)
        Matrix.multiplyMM(vp, 0, pv, 0, flip, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        update(now, dt)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        useTexProgram()

        drawRect(texBg, 0f, 0f, 1920f, 1080f)
        if (items.isEmpty()) { drawFx(1, now); return }

        val u = elapsed(now)
        val vcrDy = if (phase == Phase.INSERTING) shake(u) else 0f
        drawRect(texVcr, 370f, 790f + vcrDy, 1180f, 258f)
        drawDoor(u, vcrDy)
        refreshPanels(now)
        drawRect(texDisplay, 992f, 808f + vcrDy, 368f, 104f)
        drawRect(texKeypad, 1370f, 808f + vcrDy, 164f, 104f)

        // Text goes under the cassettes so a tape flying to the VCR passes in front of it.
        refreshText()
        drawRect(texTitle, 160f, 640f, 1600f, 90f)
        drawRect(texClock, 1520f, 36f, 320f, 80f)

        drawCarousel(u)

        when (phase) {
            Phase.LOADING -> drawFx(0, now)
            Phase.OSD, Phase.PLAYING -> if (texOsd != 0) drawRect(texOsd, 0f, 0f, 1920f, 1080f)
            Phase.MENU -> {
                if (menu.version != menuDrawn) { menuDrawn = menu.version; texMenu = Gl.replace(texMenu, art.menu(menu.view())) }
                drawRect(texMenu, 0f, 0f, 1920f, 1080f)
            }
            else -> Unit
        }
        if (prefs.scanlines) drawFx(1, now)
    }

    // ------------------------------------------------------------ timeline

    private fun duration(p: Phase) = when (p) {
        Phase.INSERTING -> 1600f
        Phase.LOADING -> 900f
        Phase.OSD -> 900f
        Phase.EJECTING -> 1500f
        else -> 1f
    }

    private fun elapsed(now: Long) = ((now - phaseStart) / duration(phase)).coerceIn(0f, 1f)

    private fun update(now: Long, dt: Float) {
        selAnim += (sel - selAnim) * (1f - exp(-dt * 12f))
        if (abs(sel - selAnim) < 0.001f) selAnim = sel.toFloat()
        spinT += dt
        if (phase == Phase.IDLE && prefs.wobble) wobbleT += dt

        val done = now - phaseStart >= duration(phase)
        when (phase) {
            Phase.INSERTING -> {
                // The mechanism sound starts as the tape meets the door.
                if (!insertSoundPlayed && elapsed(now) >= 0.55f) { insertSoundPlayed = true; sfx.play(Sfx.Sound.INSERT) }
                if (done) start(Phase.LOADING)
            }
            Phase.LOADING -> if (done) {
                items.getOrNull(sel)?.let { texOsd = Gl.replace(texOsd, art.osd(it.label)) }
                start(Phase.OSD)
            }
            Phase.OSD -> if (done) {
                val item = items.getOrNull(sel)
                if (item != null && item.isSetup) {
                    menu.open(allApps)
                    start(Phase.MENU)
                } else {
                    start(Phase.PLAYING)
                    item?.let(host::launch)
                }
            }
            Phase.EJECTING -> if (done) start(Phase.IDLE)
            else -> Unit
        }
        if (ejectAt != 0L && now >= ejectAt) {
            ejectAt = 0L
            if (phase == Phase.PLAYING) start(Phase.EJECTING)
        }
    }

    private fun wobbleY() = if (prefs.wobble) -24f * cos(2f * PI.toFloat() * wobbleT / 7f) else 0f
    private fun wobbleX() = if (prefs.wobble) 4f * cos(2f * PI.toFloat() * wobbleT / 7f) else 0f

    private fun ease(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
    private fun seg(u: Float, a: Float, b: Float) = ((u - a) / (b - a)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun lerp(a: Pose, b: Pose, t: Float) = Pose(
        lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t),
        lerp(a.rx, b.rx, t), lerp(a.ry, b.ry, t), lerp(a.s, b.s, t),
        lerp(a.wy, b.wy, t), lerp(a.wx, b.wx, t),
    )

    private fun shake(u: Float): Float {
        val t = seg(u, 0.84f, 0.97f)
        return if (t <= 0f || t >= 1f) 0f else sin(t * PI.toFloat() * 3f) * 3f * (1f - t)
    }

    // Key poses. The door entry pose has the spine flush with the VCR front (z = 0) and exactly
    // as wide as the gap between the door's guide rails.
    private val doorScale = 1.407f
    private fun shelfPose() = Pose(960f, 400f, 140f, 26f, 0f, 1f, frozenWy, frozenWx)
    private val liftPose = Pose(960f, 330f, 240f, 42f, -18f, 1.05f)
    private fun entryPose(dy: Float) = Pose(740f, 860f + dy, -82.5f * doorScale, 90f, 0f, doorScale)
    private fun deepPose(dy: Float) = Pose(740f, 856f + dy, -82.5f * doorScale - 320f, 90f, 0f, doorScale)

    /** Where the selected tape is while it travels between the shelf and the door, or null. */
    private fun flyingPose(u: Float): Pose? = when (phase) {
        Phase.INSERTING -> when {
            u < 0.22f -> lerp(shelfPose(), liftPose, ease(seg(u, 0f, 0.22f)))
            u < 0.58f -> lerp(liftPose, entryPose(0f), ease(seg(u, 0.22f, 0.58f)))
            else -> null
        }
        Phase.EJECTING -> when {
            u < 0.41f -> null
            u < 0.75f -> lerp(entryPose(0f), liftPose, ease(seg(u, 0.41f, 0.75f)))
            else -> lerp(liftPose, shelfPose(), ease(seg(u, 0.75f, 1f)))
        }
        else -> null
    }

    /** Pose of the tape inside the door and how dark the machine's interior makes it, or null. */
    private fun doorPose(u: Float, dy: Float): Pair<Pose, Float>? = when (phase) {
        Phase.INSERTING -> if (u < 0.58f) null else {
            val t = ease(seg(u, 0.58f, 0.88f))
            lerp(entryPose(dy), deepPose(dy), t) to 0.6f * t
        }
        Phase.LOADING, Phase.OSD, Phase.PLAYING, Phase.MENU -> deepPose(dy) to 0.6f
        Phase.EJECTING -> when {
            u < 0.14f -> deepPose(dy) to 0.6f
            u < 0.41f -> {
                val t = ease(seg(u, 0.14f, 0.38f))
                lerp(deepPose(dy), entryPose(dy), t) to 0.6f * (1f - t)
            }
            else -> null
        }
        else -> null
    }

    private fun flapAngle(u: Float): Float = when (phase) {
        Phase.INSERTING -> when {
            u < 0.20f -> 0f
            u < 0.36f -> lerp(0f, -80f, ease(seg(u, 0.20f, 0.36f)))
            u < 0.80f -> -80f
            u < 0.92f -> lerp(-80f, 5f, ease(seg(u, 0.80f, 0.92f)))
            else -> lerp(5f, 0f, seg(u, 0.92f, 1f))
        }
        Phase.EJECTING -> when {
            u < 0.14f -> lerp(0f, -80f, ease(seg(u, 0f, 0.14f)))
            u < 0.58f -> -80f
            u < 0.70f -> lerp(-80f, 4f, ease(seg(u, 0.58f, 0.70f)))
            else -> lerp(4f, 0f, seg(u, 0.70f, 0.78f))
        }
        else -> 0f
    }

    // ------------------------------------------------------------ drawing

    private fun drawDoor(u: Float, dy: Float) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        scissor(498f, 808f + dy, 484f, 104f)
        doorPose(u, dy)?.let { (pose, dark) ->
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            poseMatrix(pose)
            drawCassette(sel, 1f, spinT * 150f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            if (dark > 0f) drawRect(Gl.white, 498f, 808f + dy, 484f, 104f, 0f, 0f, 0f, dark)
        }
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 740f, 808f + dy, 0f)
        Matrix.rotateM(model, 0, flapAngle(u), 1f, 0f, 0f)
        Matrix.translateM(model, 0, 0f, 52f, 0f)
        Matrix.scaleM(model, 0, 484f, 104f, 1f)
        draw(texFlap, model)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun drawCarousel(u: Float) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        val wy = wobbleY(); val wx = wobbleX()
        val from = (selAnim - 5).toInt().coerceAtLeast(0)
        val to = (selAnim + 5).toInt().coerceAtMost(items.size - 1)
        for (i in from..to) {
            if (i == sel && phase != Phase.IDLE) continue
            val d = i - selAnim
            val ad = abs(d)
            if (ad > 4.6f) continue
            val f = min(ad, 1f)
            val dc = d.coerceIn(-1f, 1f)
            poseMatrix(
                Pose(
                    x = 960f + d * 230f + dc * 110f,
                    y = 400f + 34f * f,
                    z = 140f * (1f - f) - 80f * ad,
                    rx = 26f - 2f * f,
                    ry = -dc * 38f,
                    s = 1f - 0.08f * f,
                    wy = (1f - f) * wy,
                    wx = (1f - f) * wx,
                )
            )
            val spin = if (ad < 0.5f) spinT * 150f else i * 23f
            drawCassette(i, 1f - min(0.7f, ad * 0.17f), spin)
        }
        flyingPose(u)?.let { poseMatrix(it); drawCassette(sel, 1f, spinT * 150f) }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun poseMatrix(p: Pose) {
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, p.x, p.y, p.z)
        Matrix.rotateM(base, 0, p.rx, 1f, 0f, 0f)
        Matrix.rotateM(base, 0, p.ry, 0f, 1f, 0f)
        Matrix.scaleM(base, 0, p.s, p.s, p.s)
        if (p.wy != 0f) Matrix.rotateM(base, 0, p.wy, 0f, 1f, 0f)
        if (p.wx != 0f) Matrix.rotateM(base, 0, p.wx, 1f, 0f, 0f)
    }

    /** Draws cassette [index] at the transform in [base]. */
    private fun drawCassette(index: Int, bright: Float, hubAngle: Float) {
        val tex = cassetteTextures(index)
        drawFace(tex[0], faceTop, bright)
        drawFace(tex[1], faceSpine, bright * 0.88f)
        drawFace(texFlapSide, faceFlap, bright * 0.72f)
        drawFace(texSide, faceLeft, bright * 0.62f)
        drawFace(texSide, faceRight, bright * 0.62f)
        drawFace(texBack, faceBack, bright * 0.5f)
        for (hx in floatArrayOf(88f, 212f)) {
            Matrix.translateM(face, 0, base, 0, hx - 150f, 122f - 82.5f, 25.5f)
            Matrix.rotateM(face, 0, hubAngle, 0f, 0f, 1f)
            Matrix.scaleM(face, 0, 30f, 30f, 1f)
            draw(texHub, face, bright, bright, bright, 1f)
        }
    }

    private fun drawFace(tex: Int, local: FloatArray, shade: Float) {
        Matrix.multiplyMM(face, 0, base, 0, local, 0)
        draw(tex, face, shade, shade, shade, 1f)
    }

    private fun cassetteTextures(index: Int): IntArray {
        val item = items[index]
        val key = "${item.key}#$index"
        cassetteTex[key]?.let { return it }
        val style = if (item.isSetup) -1 else index
        val tex = intArrayOf(
            Gl.replace(0, art.cassetteTop(item.label, style, 2f)),
            Gl.replace(0, art.cassetteSpine(item.label, style, 2f)),
        )
        cassetteTex[key] = tex
        while (cassetteTex.size > 14) {
            val eldest = cassetteTex.entries.first()
            Gl.delete(eldest.value[0]); Gl.delete(eldest.value[1])
            cassetteTex.remove(eldest.key)
        }
        return tex
    }

    private fun refreshPanels(now: Long) {
        val second = now / 1000
        if (second != clockSecond) {
            clockSecond = second
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR_OF_DAY)
            clockText = if (prefs.clock24) "%02d:%02d".format(h, c.get(Calendar.MINUTE))
            else "%2d:%02d".format(if (h % 12 == 0) 12 else h % 12, c.get(Calendar.MINUTE))
        }
        val mode = when (phase) {
            Phase.IDLE -> "STOP"
            Phase.INSERTING -> "LOAD"
            Phase.EJECTING -> "EJCT"
            else -> "PLAY"
        }
        val counter = if (phase == Phase.IDLE || phase == Phase.INSERTING) "0000"
        else "%04d".format(((now - playStart) / 1000).coerceIn(0, 9999))
        val playing = phase == Phase.LOADING || phase == Phase.OSD || phase == Phase.PLAYING || phase == Phase.MENU
        val w = weather?.takeIf { prefs.weather }
        val key = "$mode|$counter|$clockText|$playing|${w?.text}|${w?.kind}"
        if (key != displayKey) {
            displayKey = key
            texDisplay = Gl.replace(texDisplay, art.display(mode, counter, clockText, playing, phase != Phase.IDLE, w, 2f))
        }
        val lit = sel % 12
        if (lit != keypadLit) {
            keypadLit = lit
            texKeypad = Gl.replace(texKeypad, art.keypad(lit, 2f))
        }
    }

    private fun refreshText() {
        val name = items.getOrNull(sel)?.label.orEmpty()
        if (name != titleKey) { titleKey = name; texTitle = Gl.replace(texTitle, art.title(name)) }
        if (clockText != clockKey) { clockKey = clockText; texClock = Gl.replace(texClock, art.clock(clockText)) }
    }

    // ------------------------------------------------------------ primitives

    private fun useTexProgram() {
        GLES20.glUseProgram(Gl.texProgram)
        GLES20.glUniform1i(Gl.uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        bindQuad(Gl.aPos, Gl.aUV)
    }

    private fun bindQuad(pos: Int, uv: Int) {
        Gl.quad.position(0)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 20, Gl.quad)
        GLES20.glEnableVertexAttribArray(pos)
        Gl.quad.position(3)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, Gl.quad)
        GLES20.glEnableVertexAttribArray(uv)
        Gl.quad.position(0)
    }

    private fun draw(tex: Int, m: FloatArray, r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        if (tex == 0) return
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES20.glUniformMatrix4fv(Gl.uMVP, 1, false, mvp, 0)
        GLES20.glUniform4f(Gl.uTint, r, g, b, a)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawRect(tex: Int, x: Float, y: Float, w: Float, h: Float, r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x + w / 2f, y + h / 2f, 0f)
        Matrix.scaleM(model, 0, w, h, 1f)
        draw(tex, model, r, g, b, a)
    }

    private fun drawFx(mode: Int, now: Long) {
        GLES20.glUseProgram(Gl.fxProgram)
        bindQuad(Gl.fxPos, Gl.fxUV)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 960f, 540f, 0f)
        Matrix.scaleM(model, 0, 1920f, 1080f, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(Gl.fxMVP, 1, false, mvp, 0)
        GLES20.glUniform1f(Gl.fxTime, (now % 100_000L) / 1000f)
        GLES20.glUniform1i(Gl.fxMode, mode)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        useTexProgram()
    }

    /** Scissor box given in design pixels. */
    private fun scissor(x: Float, y: Float, w: Float, h: Float) {
        val sx = vw / 1920f; val sy = vh / 1080f
        GLES20.glScissor(
            (vx + x * sx).toInt(),
            (vy + (1080f - (y + h)) * sy).toInt(),
            ceil(w * sx).toInt(),
            ceil(h * sy).toInt(),
        )
    }
}
