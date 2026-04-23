package com.i2vision.discovery

/**
 * Configurable patterns for input-surface discovery per framework.
 * Replaces hardcoded regex lists in discoverInputSurface.
 */
data class FrameworkProfile(
    val name: String,
    /** Regex patterns for input components (TextField, JTextField, etc.) */
    val inputPatterns: List<String>,
    /** Patterns for key/event handling (onKeyEvent, KeyListener, etc.) */
    val keyHandlerPatterns: List<String>,
    /** Patterns for structural components (JPanel, @Composable, etc.) */
    val componentPatterns: List<String> = emptyList()
) {
    /** Combined regex string for search (input + key handler patterns). */
    val searchPattern: String get() = (inputPatterns + keyHandlerPatterns).joinToString("|")
}

/** Built-in profiles for Compose, Swing, and CLI. */
object FrameworkProfiles {
    val COMPOSE = FrameworkProfile(
        name = "compose",
        inputPatterns = listOf(
            "TextField\\(",
            "BasicTextField\\(",
            "ImeAction",
            "KeyboardActions",
            "keyboardOptions"
        ),
        keyHandlerPatterns = listOf(
            "onKeyEvent",
            "onPreviewKeyEvent",
            "KeyEvent"
        )
    )

    val SWING = FrameworkProfile(
        name = "swing",
        inputPatterns = listOf(
            "JTextField",
            "JTextArea",
            "JEditorPane"
        ),
        keyHandlerPatterns = listOf(
            "KeyListener",
            "KeyEvent",
            "addKeyListener",
            "KeyBinding",
            "KeyAdapter"
        )
    )

    val CLI = FrameworkProfile(
        name = "cli",
        inputPatterns = listOf(
            "readLine\\(",
            "System\\.in",
            "stdin",
            "JLine",
            "Lanterna"
        ),
        keyHandlerPatterns = emptyList()
    )

    /** All profiles in discovery order (Compose first, then Swing, then CLI). */
    val ALL = listOf(COMPOSE, SWING, CLI)
}
