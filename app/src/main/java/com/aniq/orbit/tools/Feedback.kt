package com.aniq.orbit.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aniq.orbit.Prefs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The timer's sounds and haptics. The chime is OrbitSearch's own: three soft rising bell notes,
 * synthesised once and kept in memory, with a tap–tap–thud on the vibration motor timed to them.
 */
object Feedback {
    private const val RATE = 44_100
    private val main = Handler(Looper.getMainLooper())
    private var chimePcm: ShortArray? = null
    private var tickPcm: ShortArray? = null

    // ---------- sound ----------

    /** E5, G♯5, B5, 160 ms apart; each a sine with a touch of its octave, fading like a bell. */
    private fun buildChime(): ShortArray {
        val notes = doubleArrayOf(659.25, 830.61, 987.77)
        val starts = doubleArrayOf(0.0, 0.16, 0.32)
        val decays = doubleArrayOf(0.32, 0.34, 0.55)
        val gains = doubleArrayOf(0.30, 0.27, 0.27)
        val len = (RATE * 2.0).toInt()
        val out = ShortArray(len)
        for (i in 0 until len) {
            val t = i.toDouble() / RATE
            var v = 0.0
            for (n in notes.indices) {
                val u = t - starts[n]
                if (u < 0) continue
                val attack = (u / 0.012).coerceAtMost(1.0)
                val env = attack * exp(-u / decays[n])
                v += gains[n] * env * (sin(2 * PI * notes[n] * u) + 0.18 * sin(4 * PI * notes[n] * u))
            }
            out[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** A 30 ms high blip, very quiet. */
    private fun buildTick(): ShortArray {
        val len = (RATE * 0.03).toInt()
        return ShortArray(len) { i ->
            val t = i.toDouble() / RATE
            val env = (t / 0.002).coerceAtMost(1.0) * exp(-t / 0.008)
            (0.12 * env * sin(2 * PI * 1760 * t) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun play(pcm: ShortArray, usage: Int) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({ runCatching { track.stop(); track.release() } }, pcm.size * 1000L / RATE + 200)
        } catch (_: Exception) {}
    }

    // ---------- haptics ----------

    private fun vibrator(ctx: Context): Vibrator? =
        runCatching { ctx.getSystemService(VibratorManager::class.java).defaultVibrator }.getOrNull()
            ?.takeIf { it.hasVibrator() }

    private fun compose(v: Vibrator, vararg prims: Int): Boolean = v.areAllPrimitivesSupported(*prims)

    // ---------- the moments ----------

    /** Time's up: the chime plus a tap–tap–thud in step with its three notes. */
    fun finish(ctx: Context) {
        val pcm = chimePcm ?: buildChime().also { chimePcm = it }
        val usage = if (Prefs.timerChimeOnSilent(ctx)) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
        play(pcm, usage)
        val v = vibrator(ctx) ?: return
        try {
            val c = VibrationEffect.Composition.PRIMITIVE_CLICK
            val th = VibrationEffect.Composition.PRIMITIVE_THUD
            if (compose(v, c, th)) {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(c, 0.55f)
                        .addPrimitive(c, 0.75f, 140)
                        .addPrimitive(th, 1f, 140)
                        .compose()
                )
            } else if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 14, 146, 14, 146, 60), intArrayOf(0, 140, 0, 190, 0, 255), -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 14, 146, 14, 146, 60), -1))
            }
        } catch (_: Exception) {}
    }

    /** One of the last ten seconds: a light tick you can feel, and barely hear. */
    fun tick(ctx: Context) {
        play(tickPcm ?: buildTick().also { tickPcm = it }, AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        val v = vibrator(ctx) ?: return
        try {
            val t = VibrationEffect.Composition.PRIMITIVE_TICK
            if (compose(v, t)) v.vibrate(VibrationEffect.startComposition().addPrimitive(t, 0.5f).compose())
            else v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Exception) {}
    }

    /** Every 10 minutes of focus: a gentle swell, no sound. */
    fun milestone(ctx: Context) {
        val v = vibrator(ctx) ?: return
        try {
            val r = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
            val c = VibrationEffect.Composition.PRIMITIVE_CLICK
            if (compose(v, r, c)) {
                v.vibrate(VibrationEffect.startComposition().addPrimitive(r, 0.45f).addPrimitive(c, 0.4f).compose())
            } else if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(90, 90))
            } else {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }
}
