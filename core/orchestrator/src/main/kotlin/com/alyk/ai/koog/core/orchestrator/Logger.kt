package com.alyk.ai.koog.core.orchestrator

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Simple logging utility for debugging
 */
object Logger {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }
    
    private var minLevel = Level.DEBUG
    
    fun setMinLevel(level: Level) {
        minLevel = level
    }
    
    private fun shouldLog(level: Level): Boolean {
        return level.ordinal >= minLevel.ordinal
    }
    
    private fun formatMessage(level: Level, tag: String, message: String): String {
        val timestamp = LocalDateTime.now().format(formatter)
        return "[$timestamp] [$level] [$tag] $message"
    }
    
    fun debug(tag: String, message: String) {
        if (shouldLog(Level.DEBUG)) {
            println(formatMessage(Level.DEBUG, tag, message))
        }
    }
    
    fun info(tag: String, message: String) {
        if (shouldLog(Level.INFO)) {
            println(formatMessage(Level.INFO, tag, message))
        }
    }
    
    fun warn(tag: String, message: String) {
        if (shouldLog(Level.WARN)) {
            println(formatMessage(Level.WARN, tag, message))
        }
    }
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (shouldLog(Level.ERROR)) {
            println(formatMessage(Level.ERROR, tag, message))
            throwable?.printStackTrace()
        }
    }
}
