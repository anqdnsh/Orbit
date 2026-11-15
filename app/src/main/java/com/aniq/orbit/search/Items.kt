package com.aniq.orbit.search

import android.content.pm.LauncherActivityInfo
import com.aniq.orbit.CommandEntry

class AppEntry(
    val label: String,
    val norm: String,
    val initials: String,
    val info: LauncherActivityInfo,
    val key: String,
    val isWork: Boolean,
)

class SettingEntry(
    val id: String,
    val title: String,
    keywords: List<String>,
    /** Intent actions to try in order; the first one the device can open wins. */
    val actions: List<String>,
) {
    val norm = Text.normalize(title)
    val initials = Text.initials(title)
    val keywordNorms = keywords.map { Text.normalize(it) }
}

enum class Filter(val label: String) { ALL("All"), APPS("Apps"), FILES("Files"), SYSTEM("System"), SETTINGS("Settings") }

enum class Glyph { SEARCH, GEAR, GLOBE, CALC, ORBIT, POWER, BOLT, SPARK, PULSE, FILE, TIMER, CROP, SUN, CASH }

sealed class Item {
    abstract val title: String
    abstract val subtitle: String
    open val usageKey: String? = null
    open val glyph: Glyph = Glyph.SEARCH

    class App(val app: AppEntry, suggested: Boolean = false) : Item() {
        override val title = app.label
        override val subtitle = buildString {
            append("App")
            if (app.isWork) append(" · Work")
            if (suggested) append(" · Suggested")
        }
        override val usageKey = app.key
    }

    class Setting(val entry: SettingEntry) : Item() {
        override val title = entry.title
        override val subtitle = "Settings"
        override val usageKey = "set:" + entry.id
        override val glyph = Glyph.GEAR
    }

    class Command(
        val entry: CommandEntry, val param: Int?,
        override val title: String, override val subtitle: String,
    ) : Item() {
        override val usageKey = "cmd:" + entry.id
        override val glyph = entry.glyph
    }

    /** Quick AI: Tab (or Enter on this row) answers right inside the panel. */
    class Ask(val query: String, override val subtitle: String) : Item() {
        override val title = "Ask AI: “$query”"
        override val glyph = Glyph.SPARK
    }

    class File(val entry: FileEntry, override val subtitle: String) : Item() {
        override val title = entry.name
        override val usageKey = "file:" + entry.id
        override val glyph = Glyph.FILE
    }

    /** Currency conversion; [value] is null while today's rates are being fetched. */
    class Money(
        val q: Currency.Query, val value: Double?, override val title: String, override val subtitle: String,
    ) : Item() {
        override val glyph = Glyph.CASH
    }

    /** A row that takes you to a permission page (e.g. "Allow file access"). */
    class Permission(override val title: String, override val subtitle: String, val action: String) : Item() {
        override val glyph = Glyph.GEAR
    }

    class Calc(val expr: String, val value: String) : Item() {
        override val title = "= $value"
        override val subtitle = "$expr  ·  ↵ copies the answer"
        override val glyph = Glyph.CALC
    }

    class Web(val query: String) : Item() {
        override val title = "Search the web for “$query”"
        override val subtitle = "Default browser"
        override val glyph = Glyph.GLOBE
    }

    object OrbitSettings : Item() {
        override val title = "OrbitSearch settings"
        override val subtitle = "Theme, hotkey, keyboard map"
        override val usageKey = "orbit:settings"
        override val glyph = Glyph.ORBIT
        val norm = Text.normalize("orbitsearch settings")
        val keywords = listOf("orbit", "orbitsearch", "orbit settings", "hotkey", "theme", "dark mode", "light mode", "preferences").map { Text.normalize(it) }
    }
}
