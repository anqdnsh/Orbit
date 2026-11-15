package com.aniq.orbit.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * The focus timer's clock. Time is kept as an end moment on the elapsed-realtime clock, so it
 * stays exact through screen-off and rotation. An exact alarm wakes the tablet when it finishes.
 */
object FocusTimer {
    enum class State { IDLE, RUNNING, PAUSED, DONE }

    var state = State.IDLE
        private set
    var totalMs = 0L
        private set
    var focus = true
        private set
    private var endAt = 0L
    private var pausedLeft = 0L
    private var lastMinutes = 25

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var appCtx: Context? = null
    private val finisher = Runnable { appCtx?.let { finish(it) } }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    val label: String get() = if (focus) "Focus" else "Timer"

    fun remaining(): Long = when (state) {
        State.RUNNING -> (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        State.PAUSED -> pausedLeft
        else -> 0L
    }

    /** 0 at the start, 1 when time is up. Smooth: read it every frame. */
    fun progress(): Float = if (totalMs <= 0) 1f else (1f - remaining().toFloat() / totalMs).coerceIn(0f, 1f)

    fun start(ctx: Context, minutes: Int, isFocus: Boolean) {
        appCtx = ctx.applicationContext
        lastMinutes = minutes
        focus = isFocus
        totalMs = minutes * 60_000L
        endAt = SystemClock.elapsedRealtime() + totalMs
        state = State.RUNNING
        schedule(ctx)
        changed()
    }

    fun again(ctx: Context) = start(ctx, lastMinutes, focus)

    fun toggle(ctx: Context) {
        when (state) {
            State.RUNNING -> { pausedLeft = remaining(); state = State.PAUSED; unschedule(ctx) }
            State.PAUSED -> { endAt = SystemClock.elapsedRealtime() + pausedLeft; state = State.RUNNING; schedule(ctx) }
            else -> return
        }
        changed()
    }

    fun addMinutes(ctx: Context, m: Int) {
        val add = m * 60_000L
        when (state) {
            State.RUNNING -> { endAt += add; totalMs += add; schedule(ctx) }
            State.PAUSED -> { pausedLeft += add; totalMs += add }
            State.DONE, State.IDLE -> { start(ctx, m, focus); return }
        }
        changed()
    }

    fun end(ctx: Context) {
        unschedule(ctx)
        state = State.IDLE
        changed()
    }

    /** Called by the alarm or the in-app handler; safe to call more than once. */
    fun finish(ctx: Context) {
        if (state != State.RUNNING) return
        if (remaining() > 500) { schedule(ctx); return } // woke early: re-arm
        unschedule(ctx)
        state = State.DONE
        chime(ctx)
        changed()
    }

    fun dismissDone() {
        if (state == State.DONE) { state = State.IDLE; changed() }
    }

    // ---------- alarm ----------

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, TimerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(ctx: Context) {
        appCtx = ctx.applicationContext
        main.removeCallbacks(finisher)
        main.postDelayed(finisher, remaining())
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + remaining()
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
            }
        } catch (_: Exception) {}
    }

    private fun unschedule(ctx: Context) {
        main.removeCallbacks(finisher)
        try { ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx)) } catch (_: Exception) {}
    }

    // ---------- done ----------

    private fun chime(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        } catch (_: Exception) {}
        try {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 160), -1))
        } catch (_: Exception) {}
    }
}

/** Fires when the timer ends, even if the tablet was asleep. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusTimer.finish(context)
    }
}
