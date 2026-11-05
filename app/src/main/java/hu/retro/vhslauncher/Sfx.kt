package hu.retro.vhslauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/** Short VCR sounds through a SoundPool, so playback starts without decoding delay. */
class Sfx(context: Context, private val prefs: Prefs) {

    enum class Sound(val res: Int, val gain: Float) {
        SELECT(R.raw.sfx_select, 0.8f),
        SWITCH(R.raw.sfx_switch, 0.45f),
        BUTTON(R.raw.sfx_button, 0.7f),
        INSERT(R.raw.sfx_insert, 1f),
        EJECT(R.raw.sfx_eject, 1f),
        STATIC(R.raw.sfx_static, 0.6f),
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Media volume, not system sounds: Google TV often has system sounds turned off.
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = Sound.values().associateWith { pool.load(context, it.res, 1) }

    /** Safe to call from any thread. [vary] detunes slightly so repeated clicks don't sound looped. */
    fun play(sound: Sound, vary: Boolean = false) {
        if (!prefs.sound) return
        val v = sound.gain * prefs.volume.coerceIn(0, 10) / 10f
        if (v <= 0f) return
        val rate = if (vary) 0.94f + Random.nextFloat() * 0.12f else 1f
        pool.play(ids.getValue(sound), v, v, 1, 0, rate)
    }

    fun release() = pool.release()
}
