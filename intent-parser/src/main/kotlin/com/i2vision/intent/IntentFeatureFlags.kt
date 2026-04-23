package com.i2vision.intent

/**
 * Feature flags for Intent system.
 * 
 * Allows gradual rollout of Intent-based discovery alongside existing Strategy system.
 * Feature flags can be controlled via environment variables or config files.
 */
object IntentFeatureFlags {

    /**
     * Enable Intent-based discovery system.
     * 
     * When enabled, users can use --intent flag in CLI.
     * When disabled, only Strategy-based discovery works.
     * 
     * Environment variable: I2VISION_INTENT_ENABLED
     * Default: true (enabled by default for testing)
     */
    val INTENT_ENABLED: Boolean =
        System.getenv("I2VISION_INTENT_ENABLED")?.toBoolean() ?: true

    /**
     * Enable Intent resolution logging.
     * 
     * When enabled, logs intent resolution steps for debugging.
     * 
     * Environment variable: I2VISION_INTENT_LOGGING
     * Default: false
     */
    val INTENT_LOGGING: Boolean =
        System.getenv("I2VISION_INTENT_LOGGING")?.toBoolean() ?: false

    /**
     * Enable Intent validation strict mode.
     * 
     * When enabled, invalid intents throw exceptions.
     * When disabled, invalid intents fall back to default.
     * 
     * Environment variable: I2VISION_INTENT_STRICT
     * Default: true
     */
    val INTENT_STRICT: Boolean =
        System.getenv("I2VISION_INTENT_STRICT")?.toBoolean() ?: true

    /**
     * Enable Intent-only mode (disable Strategy system).
     * 
     * When enabled, only Intent-based discovery is allowed.
     * Strategy-based commands will show deprecation warnings.
     * 
     * Environment variable: I2VISION_INTENT_ONLY
     * Default: false (both systems work in parallel)
     */
    val INTENT_ONLY: Boolean =
        System.getenv("I2VISION_INTENT_ONLY")?.toBoolean() ?: false

    /**
     * Check if Intent system is available.
     */
    fun isIntentSystemAvailable(): Boolean = INTENT_ENABLED

    /**
     * Check if Intent system is the primary (Strategy system deprecated).
     */
    fun isIntentPrimary(): Boolean = INTENT_ONLY

    /**
     * Log intent resolution step.
     */
    fun logResolution(step: String, details: String) {
        if (INTENT_LOGGING) {
            println("[INTENT_RESOLUTION] $step: $details")
        }
    }
}
