package hu.retro.vhslauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Everything flat in the scene is painted once with Canvas and uploaded as a texture.
 * Drawing code works in the 1920x1080 design space; [S] is the supersampling factor.
 */
class Art(context: Context) {

    private val vt: Typeface = Typeface.createFromAsset(context.assets, "fonts/VT323-Regular.ttf")
    private val brush: Typeface = Typeface.createFromAsset(context.assets, "fonts/CaveatBrush-Regular.ttf")
    private val barlow: Typeface = Typeface.createFromAsset(context.assets, "fonts/BarlowSemiCondensed-Medium.ttf")
    private val barlowBold: Typeface = Typeface.createFromAsset(context.assets, "fonts/BarlowSemiCondensed-Bold.ttf")

    private fun bitmap(w: Float, h: Float, s: Float): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap((w * s).toInt(), (h * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(s, s)
        return b to c
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
    }
    private fun text(face: Typeface, size: Float, color: Int, spacingPx: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face; textSize = size; this.color = color
            if (spacingPx != 0f) letterSpacing = spacingPx / size
        }

    private fun vGradient(top: Float, bottom: Float, colors: IntArray, stops: FloatArray? = null) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, bottom, colors, stops, Shader.TileMode.CLAMP)
        }

    /** Draws [s] so its glyphs' visual middle sits on [cy]. */
    private fun Canvas.textMid(s: String, x: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun Paint.fitTo(s: String, maxWidth: Float, maxSize: Float, minSize: Float = 12f): Paint {
        textSize = maxSize
        while (textSize > minSize && measureText(s) > maxWidth) textSize -= 1f
        return this
    }

    // ---------------------------------------------------------------- room

    fun background(): Bitmap {
        val (b, c) = bitmap(1920f, 1080f, 1f)
        // Wood panelling.
        c.drawColor(0xFF1B140E.toInt())
        val groove = fill(0xFF120D09.toInt())
        var x = 118f
        while (x < 1920f) { c.drawRect(x, 0f, x + 3f, 1080f, groove); x += 121f }
        val rnd = Random(7)
        val streak = Paint().apply { strokeWidth = 1f }
        for (i in 0 until 900) {
            val sx = rnd.nextFloat() * 1920f
            streak.color = Color.argb(rnd.nextInt(6, 18), 0, 0, 0)
            c.drawLine(sx, 0f, sx + rnd.nextFloat() * 6f - 3f, 1080f, streak)
        }
        // Warm lamp glow behind the shelf.
        c.save()
        c.scale(1f, 0.55f / 0.6f, 960f, 410f)
        c.drawCircle(960f, 410f, 1150f, Paint().apply {
            shader = RadialGradient(960f, 410f, 1150f, intArrayOf(0x22FFAA5A, 0x00FFAA5A), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
        })
        c.restore()
        drawShelf(c)
        return b
    }

    private fun drawShelf(c: Canvas) {
        val top = 452f
        // Cast shadow on the wall.
        c.drawRect(60f, top + 124f, 1860f, top + 204f, vGradient(top + 124f, top + 204f, intArrayOf(0x99000000.toInt(), 0)).apply {
            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        })
        // Top surface in perspective.
        val surface = Path().apply {
            moveTo(96f, top); lineTo(1824f, top); lineTo(1920f, top + 92f); lineTo(0f, top + 92f); close()
        }
        c.drawPath(surface, vGradient(top, top + 92f, intArrayOf(0xFF24170D.toInt(), 0xFF4A3220.toInt(), 0xFF6A4A2F.toInt()), floatArrayOf(0f, 0.45f, 1f)))
        c.save()
        c.clipPath(surface)
        val rnd = Random(11)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for (i in 0 until 140) {
            val y = top + rnd.nextFloat() * 92f
            val t = (y - top) / 92f
            grain.strokeWidth = 0.6f + rnd.nextFloat() * 1.6f
            grain.color = if (rnd.nextBoolean()) Color.argb(rnd.nextInt(10, 30), 255, 214, 160) else Color.argb(rnd.nextInt(18, 45), 0, 0, 0)
            val p = Path()
            val x0 = rnd.nextFloat() * 1600f - 200f
            val len = 300f + rnd.nextFloat() * 900f
            p.moveTo(x0, y)
            p.cubicTo(x0 + len * 0.3f, y + (rnd.nextFloat() - 0.5f) * 4f * t, x0 + len * 0.7f, y + (rnd.nextFloat() - 0.5f) * 4f * t, x0 + len, y)
            c.drawPath(p, grain)
        }
        c.restore()
        c.drawRect(96f, top, 1824f, top + 4f, fill(0xA6000000.toInt()))
        // Contact shadow under the focused cassette.
        c.save()
        c.scale(1f, 0.1f, 960f, top + 68f)
        c.drawCircle(960f, top + 68f, 280f, Paint().apply {
            shader = RadialGradient(960f, top + 68f, 280f, intArrayOf(0x8C000000.toInt(), 0), null, Shader.TileMode.CLAMP)
        })
        c.restore()
        // Front edge.
        val ft = top + 92f
        c.drawRect(0f, ft, 1920f, ft + 36f, vGradient(ft, ft + 36f,
            intArrayOf(0xFF9A6D48.toInt(), 0xFF6B4A2E.toInt(), 0xFF533822.toInt(), 0xFF3E2A19.toInt()), floatArrayOf(0f, 0.12f, 0.6f, 1f)))
        for (i in 0 until 60) {
            val y = ft + 3f + rnd.nextFloat() * 31f
            grain.strokeWidth = 0.5f + rnd.nextFloat()
            grain.color = Color.argb(rnd.nextInt(14, 40), 0, 0, 0)
            val x0 = rnd.nextFloat() * 1920f
            c.drawLine(x0, y, x0 + 200f + rnd.nextFloat() * 700f, y + rnd.nextFloat() - 0.5f, grain)
        }
        c.drawRect(0f, ft + 34f, 1920f, ft + 36f, fill(0xFF20150C.toInt()))
        // Brackets.
        for (bx in floatArrayOf(300f, 1602f)) {
            val r = RectF(bx, ft + 36f, bx + 18f, ft + 94f)
            c.drawRect(r.left + 4f, r.top + 6f, r.right + 4f, r.bottom + 6f, fill(0x66000000).apply { maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL) })
            c.drawRoundRect(r, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(0xFF1D1D1D.toInt(), 0xFF4A4846.toInt(), 0xFF232323.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            })
            c.drawCircle(bx + 9f, ft + 73f, 3f, fill(0xFF111111.toInt()))
        }
    }

    // ---------------------------------------------------------------- VCR (Orion GHV-1223 P)

    private val buttonWidths = floatArrayOf(64f, 64f, 66f, 98f, 66f, 64f, 64f, 98f, 64f)
    private val buttonLabels = arrayOf("OPERATE", "TIMER", "REW/◂◂", "PLAY", "FF/▸▸", "PAUSE/STILL", "· RECORD", "STOP", "EJECT")

    fun vcr(s: Float): Bitmap {
        val (b, c) = bitmap(1180f, 258f, s)
        // Feet.
        c.drawRoundRect(RectF(60f, 244f, 130f, 258f), 3f, 3f, fill(0xFF0D0C0C.toInt()))
        c.drawRoundRect(RectF(1050f, 244f, 1120f, 258f), 3f, 3f, fill(0xFF0D0C0C.toInt()))
        // Body.
        c.drawRoundRect(RectF(0f, 0f, 1180f, 250f), 6f, 6f, fill(0xFF1A1817.toInt()))
        c.drawRect(0f, 0f, 1180f, 9f, fill(0xFF24211F.toInt()))
        c.drawRect(0f, 9f, 1180f, 10f, fill(0x17FFFFFF))
        c.drawRect(0f, 248f, 1180f, 250f, fill(0xFF0C0B0B.toInt()))

        c.drawText("HQ", 24f, 78f, text(barlowBold, 34f, 0xFFB7A985.toInt(), -1f))

        // Door aperture (the flap and the tape are drawn separately) and the two bezels.
        bezel(c, RectF(128f, 18f, 612f, 122f), 0xFF050505.toInt())
        bezel(c, RectF(622f, 18f, 990f, 122f), 0xFF0A0505.toInt())
        bezel(c, RectF(1000f, 18f, 1164f, 122f), 0xFF0D0C0E.toInt())

        val small = text(barlow, 10f, 0xFFBCB7AE.toInt(), 0.6f).apply { textAlign = Paint.Align.CENTER }
        // MEMORY / RESET, under the display and clear of the transport labels.
        for ((i, label) in listOf("MEMORY", "RESET").withIndex()) {
            val x = 728f + i * 56f
            c.drawRoundRect(RectF(x, 130f, x + 50f, 144f), 2f, 2f, fill(0xFF262425.toInt()))
            c.drawText(label, x + 25f, 157f, small)
        }
        // Remote sensor window.
        c.drawRect(860f, 128f, 972f, 162f, fill(0x5A5A0A0A))
        c.drawRect(860.5f, 128.5f, 971.5f, 161.5f, stroke(0xFFA8925F.toInt(), 1f))
        val sensor = text(barlow, 9.5f, 0xFFC9B68A.toInt(), 0.6f).apply { textAlign = Paint.Align.CENTER }
        val sw = sensor.measureText("REMOTE SENSOR")
        c.drawRect(916f - sw / 2 - 3f, 158f, 916f + sw / 2 + 3f, 166f, fill(0xFF1A1817.toInt()))
        c.drawText("REMOTE SENSOR", 916f, 165f, sensor)
        // PROGRAM - / +.
        for ((i, sign) in listOf("–", "+").withIndex()) {
            val x = 1012f + i * 60f
            c.drawRoundRect(RectF(x, 130f, x + 52f, 146f), 2f, 2f, fill(0xFF2C4C9C.toInt()))
            c.drawText(sign, x + 26f, 143f, text(barlowBold, 13f, 0xFFE6ECFF.toInt()).apply { textAlign = Paint.Align.CENTER })
        }
        c.drawText("PROGRAM", 1068f, 159f, small)

        // VISUAL SEARCH bracket over REW..FF.
        val br = Path().apply { moveTo(106.5f, 137f); lineTo(106.5f, 129.5f); lineTo(335.5f, 129.5f); lineTo(335.5f, 137f) }
        c.drawPath(br, stroke(0xFF8F8A82.toInt(), 1f))
        val vs = text(barlow, 9.5f, 0xFFBCB7AE.toInt(), 1f).apply { textAlign = Paint.Align.CENTER }
        val vw = vs.measureText("VISUAL SEARCH")
        c.drawRect(221f - vw / 2 - 5f, 124f, 221f + vw / 2 + 5f, 134f, fill(0xFF1A1817.toInt()))
        c.drawText("VISUAL SEARCH", 221f, 133f, vs)

        // Label plates, red rule, transport keys.
        val plate = fill(0xFF26252A.toInt())
        val plateText = text(barlow, 10.5f, 0xFFBCB7AE.toInt(), 0.6f).apply { textAlign = Paint.Align.CENTER }
        var x = 14f
        for (i in buttonWidths.indices) {
            val w = buttonWidths[i]
            c.drawRect(x, 144f, x + w, 160f, plate)
            if (i == 0) {
                c.drawCircle(x + 7f, 152f, 6f, fill(0x55FF2A1A).apply { maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL) })
                c.drawCircle(x + 7f, 152f, 3f, fill(0xFFFF3A2A.toInt()))
                c.drawText(buttonLabels[i], x + w / 2 + 5f, 156f, plateText)
            } else {
                c.drawText(buttonLabels[i], x + w / 2, 156f, plateText)
            }
            transportKey(c, RectF(x, 172f, x + w, 226f), i)
            x += w + 4f
        }
        c.drawRect(14f, 164f, 1166f, 166f, fill(0xFFA3221D.toInt()))

        // Hinged cover on the right with the model line and the emblem.
        c.drawRoundRect(RectF(712f, 172f, 1166f, 226f), 2f, 2f, fill(0xFF171615.toInt()))
        c.drawRect(712f, 172f, 1166f, 173f, fill(0xFF2A2725.toInt()))
        c.drawRect(712f, 226f, 1166f, 228f, fill(0xFF0A0A0A.toInt()))
        c.drawText("Video Cassette Recorder", 1078f, 205f, text(barlow, 15f, 0xFF96918A.toInt(), 0.5f).apply { textAlign = Paint.Align.RIGHT })
        c.drawCircle(1117f, 199f, 18f, stroke(0xFF8B8680.toInt(), 2f))
        c.drawText("ORION", 1117f, 202f, text(barlowBold, 8f, 0xFFA29D96.toInt(), 0.5f).apply { textAlign = Paint.Align.CENTER })
        return b
    }

    private fun bezel(c: Canvas, r: RectF, inside: Int) {
        c.drawRoundRect(RectF(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f), 4f, 4f, fill(0xFF2B2826.toInt()))
        c.drawRoundRect(r, 3f, 3f, fill(inside))
        c.drawRect(r.left, r.top, r.right, r.top + 8f, vGradient(r.top, r.top + 8f, intArrayOf(0xAA000000.toInt(), 0)))
    }

    private fun transportKey(c: Canvas, r: RectF, i: Int) {
        c.drawRoundRect(RectF(r.left, r.top + 2f, r.right, r.bottom + 2f), 2f, 2f, fill(0xFF0A0A0A.toInt()))
        c.drawRoundRect(r, 2f, 2f, vGradient(r.top, r.bottom, intArrayOf(0xFF252324.toInt(), 0xFF1A1919.toInt())))
        c.drawRect(r.left + 1f, r.top, r.right - 1f, r.top + 1f, fill(0x12FFFFFF))
        val cx = r.centerX()
        val grille = i == 3 || i == 7
        val cy = if (grille) r.centerY() - 5f else r.centerY()
        val light = stroke(0xFFD9D6D0.toInt(), 2f).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val green = stroke(0xFF3FBF6A.toInt(), 2f).apply { strokeJoin = Paint.Join.ROUND }
        when (i) {
            0 -> { c.drawArc(RectF(cx - 7f, cy - 6f, cx + 7f, cy + 8f), -50f, 280f, false, light); c.drawLine(cx, cy - 8f, cx, cy, light) }
            1 -> { c.drawCircle(cx, cy, 7f, light); c.drawLine(cx, cy - 4f, cx, cy, light); c.drawLine(cx, cy, cx + 3f, cy + 2f, light) }
            2 -> { tri(c, cx + 1f, cy, -1, green); tri(c, cx + 11f, cy, -1, green) }
            3 -> tri(c, cx - 4f, cy, 1, green, 12f)
            4 -> { tri(c, cx - 11f, cy, 1, green); tri(c, cx - 1f, cy, 1, green) }
            5 -> { c.drawLine(cx - 4f, cy - 7f, cx - 4f, cy + 7f, light); c.drawLine(cx + 4f, cy - 7f, cx + 4f, cy + 7f, light) }
            6 -> c.drawCircle(cx, cy, 6.5f, stroke(0xFFE0362C.toInt(), 2.2f))
            7 -> c.drawRect(cx - 6f, cy - 6f, cx + 6f, cy + 6f, stroke(0xFFD9D6D0.toInt(), 2f))
            8 -> { val p = Path().apply { moveTo(cx, cy - 7f); lineTo(cx + 7f, cy + 1f); lineTo(cx - 7f, cy + 1f); close() }; c.drawPath(p, light); c.drawLine(cx - 7f, cy + 6f, cx + 7f, cy + 6f, light) }
        }
        if (grille) {
            val dot = fill(0xFF3C3A3A.toInt())
            var gx = cx - 28f
            while (gx <= cx + 28f) { c.drawCircle(gx, r.centerY() + 12f, 1f, dot); c.drawCircle(gx + 2.5f, r.centerY() + 15f, 1f, dot); gx += 5f }
        }
    }

    private fun tri(c: Canvas, x: Float, cy: Float, dir: Int, p: Paint, w: Float = 10f) {
        val h = 7f
        val path = Path()
        if (dir > 0) { path.moveTo(x, cy - h); path.lineTo(x + w, cy); path.lineTo(x, cy + h) }
        else { path.moveTo(x, cy - h); path.lineTo(x - w, cy); path.lineTo(x, cy + h) }
        path.close()
        c.drawPath(path, p)
    }

    /** The smoked-glass door flap; it rotates about its top edge. */
    fun flap(s: Float): Bitmap {
        val (b, c) = bitmap(484f, 104f, s)
        c.drawRect(0f, 0f, 484f, 104f, vGradient(0f, 104f, intArrayOf(0x9E2E2C2E.toInt(), 0xCC08080A.toInt())))
        c.drawRect(0f, 0f, 484f, 1f, fill(0x1AFFFFFF))
        c.drawRoundRect(RectF(24f, 20f, 31f, 82f), 3f, 3f, fill(0xFF252324.toInt()))
        c.drawRoundRect(RectF(453f, 20f, 460f, 82f), 3f, 3f, fill(0xFF252324.toInt()))
        val logo = text(barlowBold, 20f, 0xFFE9E5DC.toInt(), 1f).apply { textAlign = Paint.Align.CENTER }
        val lw = logo.measureText("VHS")
        c.drawRoundRect(RectF(242f - lw / 2 - 7f, 26f, 242f + lw / 2 + 7f, 50f), 3f, 3f, stroke(0xFFDCD8CF.toInt(), 2f))
        c.textMid("VHS", 242f, 38.5f, logo)
        c.drawText("Front Loading System", 242f, 66f, text(barlow, 14f, 0xFF9A958D.toInt(), 0.5f).apply { textAlign = Paint.Align.CENTER })
        return b
    }

    /** Vacuum-fluorescent display: weather, mode, tape counter, clock. */
    fun display(mode: String, counter: String, clock: String, playing: Boolean, tape: Boolean, weather: Weather?, s: Float): Bitmap {
        val (b, c) = bitmap(368f, 104f, s)
        c.drawColor(0xFF0A0505.toInt())
        val orange = text(vt, 46f, 0xFFFF6A2A.toInt(), 3f).apply { setShadowLayer(9f, 0f, 0f, 0xFFFF4A10.toInt()) }
        val blue = text(vt, 30f, 0xFFBFE6FF.toInt(), 2f).apply { setShadowLayer(7f, 0f, 0f, 0xFF63B8FF.toInt()) }
        val clockPaint = text(vt, 48f, 0xFFD6F1FF.toInt(), 2f).apply { setShadowLayer(9f, 0f, 0f, 0xFF63B8FF.toInt()); textAlign = Paint.Align.RIGHT }
        if (weather != null) {
            pixelIcon(c, weather.kind, 20f, 11f, 2.5f)
            c.drawText(weather.text, 56f, 33f, text(vt, 28f, 0xFFBFE6FF.toInt(), 1f).apply { setShadowLayer(6f, 0f, 0f, 0xFF63B8FF.toInt()) })
        }
        if (playing) {
            val p = Path().apply { moveTo(152f, 20f); lineTo(164f, 27f); lineTo(152f, 34f); close() }
            c.drawPath(p, fill(0xFFFF6A2A.toInt()).apply { setShadowLayer(5f, 0f, 0f, 0xFFFF4A10.toInt()) })
        }
        if (tape) c.drawText("SP", 174f, 34f, text(vt, 16f, 0xFFFF4040.toInt()).apply { setShadowLayer(4f, 0f, 0f, 0xFFFF2020.toInt()) })
        c.drawText(mode, 20f, 82f, orange)
        c.drawText(counter, 140f, 82f, blue)
        c.drawText(clock, 352f, 76f, clockPaint)
        return b
    }

    // 12x9 VFD segment art. '#' cyan, 'o' orange, '+' pale blue.
    private val icons: Map<Weather.Kind, Array<String>> = mapOf(
        Weather.Kind.SUN to arrayOf(
            "....o..o....", ".o........o.", "....oooo....", "...oooooo...", "o..oooooo..o",
            "...oooooo...", "....oooo....", ".o........o.", "....o..o....",
        ),
        Weather.Kind.MOON to arrayOf(
            "....####....", "...##.......", "..##........", "..##........", "..##.....+..",
            "..##........", "...##.......", "....####....", "............",
        ),
        Weather.Kind.PARTLY to arrayOf(
            "......o..o..", ".......oooo.", "......oooooo", "....###oooo.", "...#####....",
            "..#######...", ".##########.", "############", ".##########.",
        ),
        Weather.Kind.CLOUD to arrayOf(
            "............", "....###.....", "...#####....", "..#######...", ".##########.",
            "############", "############", ".##########.", "............",
        ),
        Weather.Kind.FOG to arrayOf(
            "............", "############", "............", ".##########.", "............",
            "############", "............", ".##########.", "............",
        ),
        Weather.Kind.RAIN to arrayOf(
            "....###.....", "...#####....", "..#######...", ".#########..", "############",
            "............", ".+..+..+..+.", "+..+..+..+..", "............",
        ),
        Weather.Kind.SNOW to arrayOf(
            "....###.....", "...#####....", "..#######...", ".#########..", "############",
            "............", ".+...+...+..", "...+...+...+", ".+...+...+..",
        ),
        Weather.Kind.STORM to arrayOf(
            "....###.....", "...#####....", "..#######...", ".#########..", "############",
            ".....oo.....", "....oo......", ".....oo.....", "....o.......",
        ),
    )

    private fun pixelIcon(c: Canvas, kind: Weather.Kind, x: Float, y: Float, px: Float) {
        val rows = icons[kind] ?: return
        val paints = mapOf(
            '#' to fill(0xFFBFE6FF.toInt()).apply { setShadowLayer(3f, 0f, 0f, 0xFF63B8FF.toInt()) },
            'o' to fill(0xFFFF6A2A.toInt()).apply { setShadowLayer(3f, 0f, 0f, 0xFFFF4A10.toInt()) },
            '+' to fill(0xFF8FD0FF.toInt()).apply { setShadowLayer(3f, 0f, 0f, 0xFF63B8FF.toInt()) },
        )
        for ((r, line) in rows.withIndex()) for ((col, ch) in line.withIndex()) {
            val p = paints[ch] ?: continue
            c.drawRect(x + col * px, y + r * px, x + col * px + px * 0.85f, y + r * px + px * 0.85f, p)
        }
    }

    /** The SETUP tape's blue on-screen menu. Painted at half resolution like the PLAY screen. */
    fun menu(view: SetupMenu.View): Bitmap {
        val (b, c) = bitmap(1920f, 1080f, 0.5f)
        c.drawColor(0xFF1A2BC4.toInt())
        val title = text(vt, 72f, Color.WHITE, 6f)
        val base = title.color
        title.color = 0x99FF285A.toInt(); c.drawText(view.title, 123f, 140f, title)
        title.color = 0x9928D8FF.toInt(); c.drawText(view.title, 117f, 140f, title)
        title.color = base; c.drawText(view.title, 120f, 140f, title)
        c.drawRect(120f, 160f, 1800f, 163f, fill(0x99FFFFFF.toInt()))

        val label = text(vt, 46f, Color.WHITE, 3f)
        val value = text(vt, 46f, Color.WHITE, 3f).apply { textAlign = Paint.Align.RIGHT }
        for ((i, row) in view.rows.withIndex()) {
            val y = 240f + i * 66f
            val selected = i == view.cursor
            val alpha = when { row.dim -> 120; selected -> 255; else -> 215 }
            label.alpha = alpha; value.alpha = alpha
            if (selected) {
                val p = Path().apply { moveTo(124f, y - 32f); lineTo(150f, y - 17f); lineTo(124f, y - 2f); close() }
                c.drawPath(p, fill(Color.WHITE))
            }
            if (row.grabbed) c.drawRect(162.5f, y - 44f, 1810f, y + 12f, stroke(Color.WHITE, 3f))
            c.drawText(row.label, 174f, y, label)
            if (row.bar >= 0) {
                for (k in 0 until 10) {
                    val r = RectF(1800f - (10 - k) * 34f, y - 34f, 1800f - (10 - k) * 34f + 26f, y)
                    if (k < row.bar) c.drawRect(r, fill(Color.WHITE).apply { this.alpha = alpha })
                    else c.drawRect(r, stroke(Color.WHITE, 3f).apply { this.alpha = alpha })
                }
            } else if (row.value.isNotEmpty()) {
                c.drawText(row.value, 1800f, y, value)
            }
        }
        c.drawText(view.hint, 120f, 1030f, text(vt, 32f, Color.WHITE, 2f).apply { alpha = 190 })
        return b
    }

    /** The 12-key programme pad; [lit] (0..11) glows green. */
    fun keypad(lit: Int, s: Float): Bitmap {
        val (b, c) = bitmap(164f, 104f, s)
        c.drawColor(0xFF0D0C0E.toInt())
        val letter = text(barlowBold, 9f, 0xFF8E8A84.toInt()).apply { textAlign = Paint.Align.CENTER }
        val num = text(barlow, 11f, 0xFFA29FA6.toInt()).apply { textAlign = Paint.Align.CENTER }
        val litNum = text(barlowBold, 11f, 0xFF8DFF74.toInt()).apply { textAlign = Paint.Align.CENTER; setShadowLayer(5f, 0f, 0f, 0xFF4CFF2C.toInt()) }
        val col = 25f
        for (i in 0 until 6) {
            val cx = 7f + col * i + col / 2 - 1f
            c.drawText(('A' + i).toString(), cx, 22f, letter)
            c.drawText(('G' + i).toString(), cx, 91f, letter)
            for (row in 0..1) {
                val k = row * 6 + i
                val top = 30f + row * 24f
                val r = RectF(cx - 11f, top, cx + 11f, top + 20f)
                c.drawRoundRect(r, 2f, 2f, fill(0xFF1C1B1E.toInt()))
                c.drawRoundRect(r, 2f, 2f, stroke(0xFF3A383C.toInt(), 1f))
                c.textMid((k + 1).toString(), cx, r.centerY(), if (k == lit) litNum else num)
            }
        }
        return b
    }

    // ---------------------------------------------------------------- cassettes

    class LabelStyle(val ink: Int, val rotation: Float, val paint: (Canvas, RectF) -> Unit)

    private val labelStyles: List<LabelStyle> = listOf(
        LabelStyle(0xFF1B1B1B.toInt(), -0.6f) { c, r -> c.drawRect(r, fill(0xFFF2EEE3.toInt())); c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.13f, fill(0xFFC8341F.toInt())) },
        LabelStyle(0xFF1F3D91.toInt(), 0.5f) { c, r ->
            c.drawRect(r, fill(0xFFF6F2E6.toInt()))
            val line = fill(0x592850AA)
            var y = r.top + r.height() * 0.22f
            while (y < r.bottom) { c.drawRect(r.left, y, r.right, y + 1f, line); y += r.height() * 0.23f }
        },
        LabelStyle(0xFF1C2A55.toInt(), 0f) { c, r -> c.drawRect(r, fill(0xFFD7E6F3.toInt())); c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.14f, fill(0xFF23366E.toInt())) },
        LabelStyle(0xFF111111.toInt(), -0.3f) { c, r ->
            c.drawRect(r, fill(0xFFD9D9D9.toInt()))
            val u = r.height() * 0.057f
            c.drawRect(r.left, r.top, r.right, r.top + u, fill(0xFF111111.toInt()))
            c.drawRect(r.left, r.top + u, r.right, r.top + 2 * u, fill(0xFFC4C4C4.toInt()))
            c.drawRect(r.left, r.top + 2 * u, r.right, r.top + 3 * u, fill(0xFF111111.toInt()))
        },
        LabelStyle(0xFF111111.toInt(), -1.4f) { c, r -> c.drawRect(r, fill(0xFFE7D9B0.toInt())) },
        LabelStyle(0xFF121212.toInt(), 0f) { c, r -> c.drawRect(r, fill(0xFFF0BF2C.toInt())); c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.11f, fill(0xFF121212.toInt())) },
        LabelStyle(0xFF2A1A10.toInt(), 0.7f) { c, r ->
            c.drawRect(r, fill(0xFFE8E0CC.toInt()))
            val h = r.height()
            c.drawRect(r.left, r.top + h * 0.74f, r.right, r.top + h * 0.82f, fill(0xFFE07B24.toInt()))
            c.drawRect(r.left, r.top + h * 0.82f, r.right, r.top + h * 0.90f, fill(0xFFA8441B.toInt()))
            c.drawRect(r.left, r.top + h * 0.90f, r.right, r.bottom, fill(0xFF5A2A14.toInt()))
        },
        LabelStyle(0xFF222222.toInt(), 0f) { c, r -> c.drawRect(r, fill(0xFFFBFAF6.toInt())); c.drawRect(r.left, r.top + r.height() * 0.28f, r.right, r.top + r.height() * 0.28f + 1f, fill(0xFFD23B3B.toInt())) },
        LabelStyle(0xFFB3261E.toInt(), 0.9f) { c, r -> c.drawRect(r, fill(0xFFD9D4C7.toInt())) },
    )

    /** The setup tape: a red service-tape label. */
    private val setupStyle = LabelStyle(0xFFF6F2E6.toInt(), -0.4f) { c, r ->
        c.drawRect(r, fill(0xFF9E1F1A.toInt()))
        c.drawRect(r.left, r.bottom - r.height() * 0.12f, r.right, r.bottom, fill(0xFF111111.toInt()))
    }

    /** Negative indexes select the setup tape's label. */
    private fun style(index: Int) = if (index < 0) setupStyle else labelStyles[index % labelStyles.size]

    /** Tape-pack diameters seen through the window, varied per cassette. */
    fun packs(index: Int): Pair<Float, Float> {
        val left = 74f + (abs(index) * 37) % 60
        return left to 208f - left
    }

    private fun Canvas.label(r: RectF, st: LabelStyle, block: Canvas.() -> Unit) {
        save()
        rotate(st.rotation, r.centerX(), r.centerY())
        drawRect(r.left, r.top + 1f, r.right, r.bottom + 1f, fill(0x55000000))
        save(); clipRect(r); st.paint(this, r); block(); restore()
        restore()
    }

    /** Window face: label sticker on top, reel window below. Hubs are separate quads so they can spin. */
    fun cassetteTop(name: String, index: Int, s: Float): Bitmap {
        val (b, c) = bitmap(300f, 165f, s)
        val body = RectF(0f, 0f, 300f, 165f)
        c.drawRoundRect(body, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(122f, 0f, 178f, 165f, intArrayOf(0xFF2A2A2A.toInt(), 0xFF121212.toInt(), 0xFF0C0C0C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawRoundRect(RectF(0.5f, 0.5f, 299.5f, 164.5f), 6f, 6f, stroke(0xFF3A3A3A.toInt(), 1f))
        c.drawRect(6f, 1f, 294f, 3f, fill(0x24FFFFFF))

        val st = style(index)
        c.label(RectF(14f, 12f, 286f, 82f), st) {
            drawText("VHS · E-180", 276f, 22f, text(barlowBold, 9f, st.ink, 1f).apply { alpha = 180; textAlign = Paint.Align.RIGHT })
            val p = text(brush, 46f, st.ink).fitTo(name, 236f, 46f)
            textMid(name, 30f, 51f, p)
        }

        // Reel window.
        val win = RectF(58f, 94f, 242f, 150f)
        c.save()
        c.clipRect(win)
        c.drawColor(0xFF17110D.toInt())
        val (pl, pr) = packs(index)
        for ((cx, d) in listOf(88f to pl, 212f to pr)) {
            c.drawCircle(cx, 122f, d / 2f, fill(0xFF3A281B.toInt()))
            c.drawCircle(cx, 122f, d / 2f * 0.6f, stroke(0xFF2B1D13.toInt(), 1f))
        }
        c.drawRect(win.left, win.top, win.right, win.top + 8f, vGradient(win.top, win.top + 8f, intArrayOf(0xCC000000.toInt(), 0)))
        val ang = Math.toRadians(115.0)
        val vx = sin(ang).toFloat(); val vy = -cos(ang).toFloat()
        val len = win.width() * vx + win.height() * -vy
        c.drawRect(win, Paint().apply {
            shader = LinearGradient(win.centerX() - vx * len / 2, win.centerY() - vy * len / 2, win.centerX() + vx * len / 2, win.centerY() + vy * len / 2,
                intArrayOf(0x29FFFFFF, 0x29FFFFFF, 0, 0, 0x12FFFFFF, 0x12FFFFFF, 0, 0),
                floatArrayOf(0f, 0.18f, 0.18f, 0.6f, 0.6f, 0.66f, 0.66f, 1f), Shader.TileMode.CLAMP)
        })
        c.restore()
        c.drawRoundRect(RectF(59f, 95f, 241f, 149f), 4f, 4f, stroke(0xFF050505.toInt(), 2f))

        for ((x, y) in listOf(13f to 153f, 287f to 153f, 13f to 89f, 287f to 89f)) {
            c.drawCircle(x, y, 3f, fill(0xFF2C2C2C.toInt()))
            c.drawCircle(x, y, 3f, stroke(0xFF050505.toInt(), 1f))
        }
        return b
    }

    /** The label spine that faces you on the shelf and goes into the VCR last. */
    fun cassetteSpine(name: String, index: Int, s: Float): Bitmap {
        val (b, c) = bitmap(300f, 48f, s)
        c.drawRect(0f, 0f, 300f, 48f, vGradient(0f, 48f, intArrayOf(0xFF1E1E1E.toInt(), 0xFF0B0B0B.toInt())))
        c.drawRect(0f, 0f, 300f, 1f, fill(0x1FFFFFFF))
        val st = style(index)
        c.label(RectF(10f, 6f, 290f, 42f), st) {
            val sp = text(barlowBold, 10f, st.ink, 1f).apply { textAlign = Paint.Align.RIGHT }
            textMid("SP · 180", 280f, 24f, sp)
            val p = text(brush, 28f, st.ink).fitTo(name, 200f, 28f)
            textMid(name, 22f, 25f, p)
        }
        return b
    }

    fun cassetteFlapSide(s: Float): Bitmap {
        val (b, c) = bitmap(300f, 48f, s)
        c.drawColor(0xFF141414.toInt())
        c.drawRect(0f, 0f, 300f, 2f, fill(0xFF262626.toInt()))
        c.drawRect(0f, 45f, 300f, 48f, fill(0xFF262626.toInt()))
        c.drawRect(20f, 20f, 280f, 22f, fill(0xFF0C0C0C.toInt()))
        return b
    }

    fun cassetteSide(s: Float): Bitmap {
        val (b, c) = bitmap(48f, 165f, s)
        c.drawColor(0xFF151515.toInt())
        var y = 5f
        while (y < 165f) { c.drawRect(0f, y, 48f, y + 2f, fill(0xFF0B0B0B.toInt())); y += 7f }
        c.drawRect(0.5f, 0.5f, 47.5f, 164.5f, stroke(0xFF262626.toInt(), 1f))
        return b
    }

    fun cassetteBack(s: Float): Bitmap {
        val (b, c) = bitmap(300f, 165f, s)
        c.drawRoundRect(RectF(0f, 0f, 300f, 165f), 6f, 6f, fill(0xFF0E0E0E.toInt()))
        c.drawRoundRect(RectF(0.5f, 0.5f, 299.5f, 164.5f), 6f, 6f, stroke(0xFF222222.toInt(), 1f))
        for (cx in floatArrayOf(66f, 234f)) {
            c.drawCircle(cx, 82f, 26f, fill(0xFF080808.toInt()))
            c.drawCircle(cx, 82f, 12f, fill(0xFF1A1A1A.toInt()))
            c.drawCircle(cx, 82f, 10.4f, fill(0xFF050505.toInt()))
        }
        return b
    }

    fun hub(): Bitmap {
        val (b, c) = bitmap(30f, 30f, 64f / 30f)
        val cream = fill(0xFFEBE7DC.toInt())
        c.save()
        c.clipPath(Path().apply { addCircle(15f, 15f, 15f, Path.Direction.CW) })
        for (i in 0 until 6) c.drawArc(RectF(0f, 0f, 30f, 30f), i * 60f - 90f, 22f, true, cream)
        c.restore()
        c.drawCircle(15f, 15f, 9f, cream)
        c.drawCircle(15f, 15f, 5f, fill(0xFF17110D.toInt()))
        return b
    }

    // ---------------------------------------------------------------- text and screens

    fun title(name: String): Bitmap {
        val (b, c) = bitmap(1600f, 90f, 1f)
        val p = text(vt, 66f, 0xFFF3ECDC.toInt(), 6f).apply { textAlign = Paint.Align.CENTER; setShadowLayer(12f, 0f, 0f, 0x55FFBE78) }
        p.fitTo(name.uppercase(), 1500f, 66f)
        c.textMid(name.uppercase(), 800f, 45f, p)
        return b
    }

    fun clock(time: String): Bitmap {
        val (b, c) = bitmap(320f, 80f, 1f)
        val p = text(vt, 56f, 0xFFCFEEFF.toInt(), 3f).apply { textAlign = Paint.Align.RIGHT; setShadowLayer(10f, 0f, 0f, 0x8C78C8FF.toInt()) }
        c.textMid(time, 308f, 40f, p)
        return b
    }

    /** Classic blue VCR on-screen display shown while the app starts. Painted at half resolution on purpose. */
    fun osd(name: String): Bitmap {
        val (b, c) = bitmap(1920f, 1080f, 0.5f)
        c.drawColor(0xFF1A2BC4.toInt())
        fun ghosted(s: String, x: Float, y: Float, p: Paint, off: Float) {
            val base = p.color
            p.color = 0x99FF285A.toInt(); c.drawText(s, x + off, y, p)
            p.color = 0x9928D8FF.toInt(); c.drawText(s, x - off, y, p)
            p.color = base; c.drawText(s, x, y, p)
        }
        val big = text(vt, 64f, Color.WHITE, 4f)
        val play = Path().apply { moveTo(82f, 74f); lineTo(118f, 96f); lineTo(82f, 118f); close() }
        c.drawPath(play, fill(Color.WHITE))
        ghosted("PLAY", 136f, 116f, big, 3f)
        ghosted("SP", 1840f - big.measureText("SP"), 116f, big, 3f)
        val huge = text(vt, 150f, Color.WHITE, 12f).apply { textAlign = Paint.Align.CENTER }
        huge.fitTo(name.uppercase(), 1700f, 150f)
        ghosted(name.uppercase(), 960f, 500f, huge, 4f)
        c.drawText("LOADING...", 960f, 590f, text(vt, 48f, Color.WHITE, 6f).apply { textAlign = Paint.Align.CENTER })
        return b
    }

    companion object {
        fun clamp01(v: Float) = max(0f, min(1f, v))
    }
}
