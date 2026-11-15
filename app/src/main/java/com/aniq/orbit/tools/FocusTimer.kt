package com.aniq.orbit.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aniq.orbit.Prefs

/**
 * The focus timer's clock. Time is kept as an end moment on the elapsed-realtime clock, so it
 * stays exact through screen-off and rotation. An exact alarm wakes the tablet when it finishes.
 * The state is also saved, so if Android closes OrbitSearch in the background the alarm still
 * finds the timer and chimes.
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
    private var restored = false

    /** When it finished, on the uptime clock the faces animate with. */
    var doneAtUptime = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var appCtx: Context? = null
    private val finisher = Runnable { appCtx?.let { finish(it) } }
    private const val MILESTONE_MS = 10 * 60_000L
    private val ticker = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            if (state != State.RUNNING) return
            val left = remaining()
            if (left in 300..10_300 && Prefs.timerTicks(ctx)) Feedback.tick(ctx)
            planTicks()
        }
    }
    private val milestone = Runnable {
        appCtx?.let { if (state == State.RUNNING && Prefs.timerMilestones(it)) Feedback.milestone(it) }
        planMilestone()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() {
        appCtx?.let { save(it) }
        listeners.toList().forEach { it() }
    }

    val label: String get() = if (focus) "Focus" else "Timer"

    fun remaining(): Long = when (state) {
        State.RUNNING -> (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        State.PAUSED -> pausedLeft
        else -> 0L
    }

    /** 0 at the start, 1 when time is up. Smooth: read it every frame. */
    fun progress(): Float = if (totalMs <= 0) 1f else (1f - remaining().toFloat() / totalMs).coerceIn(0f, 1f)

    fun start(ctx: Context, minutes: Int, isFocus: Boolean) {
        restore(ctx)
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
        restore(ctx)
        if (state != State.RUNNING) return
        if (remaining() > 500) { schedule(ctx); return } // woke early: re-arm
        unschedule(ctx)
        state = State.DONE
        doneAtUptime = SystemClock.uptimeMillis()
        Feedback.finish(ctx)
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
        planTicks()
        planMilestone()
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
        main.removeCallbacks(ticker)
        main.removeCallbacks(milestone)
        try { ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx)) } catch (_: Exception) {}
    }

    /** Next tick on each whole second of the last ten. */
    private fun planTicks() {
        main.removeCallbacks(ticker)
        if (state != State.RUNNING) return
        val left = remaining()
        if (left <= 300) return
        val untilWindow = left - 10_000
        val delay = if (untilWindow > 0) untilWindow else ((left - 1) % 1000) + 1
        main.postDelayed(ticker, delay.coerceAtLeast(1))
    }

    /** Next 10-minute mark of time spent (not counting the very end). */
    private fun planMilestone() {
        main.removeCallbacks(milestone)
        if (state != State.RUNNING) return
        val spent = totalMs - remaining()
        val next = (spent / MILESTONE_MS + 1) * MILESTONE_MS
        if (next >= totalMs - 30_000) return
        main.postDelayed(milestone, next - spent)
    }

    // ---------- saved state ----------

    private fun save(ctx: Context) {
        Prefs.of(ctx).edit()
            .putString("timer_state", state.name)
            .putLong("timer_end", endAt)
            .putLong("timer_paused_left", pausedLeft)
            .putLong("timer_total", totalMs)
            .putBoolean("timer_focus", focus)
            .putInt("timer_last_min", lastMinutes)
            .apply()
    }

    /** Picks up a timer from before OrbitSearch was closed. Does nothing after the first call. */
    fun restore(ctx: Context) {
        if (restored) return
        restored = true
        appCtx = ctx.applicationContext
        val p = Prefs.of(ctx)
        val saved = runCatching { State.valueOf(p.getString("timer_state", "IDLE") ?: "IDLE") }.getOrDefault(State.IDLE)
        lastMinutes = p.getInt("timer_last_min", 25)
        if (saved != State.RUNNING && saved != State.PAUSED) return
        totalMs = p.getLong("timer_total", 0)
        focus = p.getBoolean("timer_focus", true)
        endAt = p.getLong("timer_end", 0)
        pausedLeft = p.getLong("timer_paused_left", 0)
        val now = SystemClock.elapsedRealtime()
        // After a restart the clock starts again from zero, so a saved end moment is meaningless.
        if (totalMs <= 0 || (saved == State.RUNNING && endAt - now > totalMs)) { save(ctx); return }
        state = saved
        if (state == State.RUNNING) {
            if (endAt - now <= 500) {
                // Ended while closed: chime if that was just now, otherwise let it go quietly.
                if (now - endAt > 120_000) { state = State.IDLE; save(ctx); return }
                endAt = now; finish(ctx); return
            }
            schedule(ctx)
        }
    }
}

/** Fires when the timer ends, even if the tablet was asleep. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusTimer.finish(context)
    }
}
