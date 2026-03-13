package com.alyk.ai.koog.database.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Functional stubs to feed Terminal output based on [TerminalSettingsDefinitions] and [TerminalSettingsState].
 * Uses a settings map (key -> enabled) derived from the repository for the current session.
 *
 * Responsibilities:
 * - Decide whether an output item should be shown (visibility by setting key).
 * - Optionally format the display string (e.g. timestamps, compact mode).
 */
object TerminalOutputFilter {

    /**
     * Whether to show an output item controlled by the given setting key.
     * Stub: if [outputSettingKey] is null, always show; otherwise show iff the setting is enabled.
     * Missing keys in [settings] are treated as "use default from definitions"; here we default to true.
     */
    fun shouldShow(
        outputSettingKey: String?,
        settings: Map<String, Boolean>
    ): Boolean {
        if (outputSettingKey == null) return true
        return settings[outputSettingKey] ?: true
    }

    /**
     * Format a message for display according to display settings.
     * Stub: if [showTimestamps] add a time prefix; otherwise return [message] as-is.
     * Can be extended for compact_mode, word_wrap, etc.
     */
    fun formatMessage(
        message: String,
        showTimestamps: Boolean = false,
        timestamp: Instant = Instant.now(),
        compactMode: Boolean = false
    ): String {
        if (!showTimestamps) return message
        val timeStr = timestamp.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val separator = if (compactMode) " " else " "
        return "[$timeStr]$separator$message"
    }

    /**
     * Combined visibility check for a single output kind.
     * Use this when the UI or ViewModel has already mapped an event to a setting key.
     */
    fun shouldShowAndFormat(
        outputSettingKey: String?,
        settings: Map<String, Boolean>,
        message: String,
        timestamp: Instant = Instant.now()
    ): Pair<Boolean, String> {
        val show = shouldShow(outputSettingKey, settings)
        val showTimestamps = settings["show_timestamps"] ?: false
        val compactMode = settings["compact_mode"] ?: false
        val formatted = formatMessage(message, showTimestamps, timestamp, compactMode)
        return show to formatted
    }
}
