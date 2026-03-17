package gui.output

import core.OutputEvent

/**
 * Maps agent output (OutputEvent / AgentResponseChunk) to terminal_settings_definitions keys.
 * Used to filter and format terminal feed based on [TerminalSettingsState].
 *
 * Alignment with koog agent:
 * - Text chunks → show_ai_responses
 * - Progress → show_progress_bar
 * - ToolCall → show_command_execution
 * - Error → show_error_details / show_warnings
 * - System (routing, MCP, etc.) → show_model_info / show_mcp_connections / show_status_symbols
 * - User prompt line → show_user_prompts
 * - Complete → notify_on_complete (or always show)
 */
object TerminalOutputMapping {

    /** Setting key for each OutputEvent variant. Aligns with terminal_settings.properties. */
    fun outputSettingKeyFor(event: OutputEvent): String? = when (event) {
        is OutputEvent.Standard -> "show_ai_responses"
        is OutputEvent.Success -> "show_ai_responses"
        is OutputEvent.Warning -> "show_warnings"
        is OutputEvent.Error -> "show_error_details"
        is OutputEvent.System -> "show_status_bar"
        is OutputEvent.Debug -> "show_raw_json"
        is OutputEvent.Progress -> "show_progress_bar"
        is OutputEvent.Complete -> null // always show
        is OutputEvent.ToolCallDetail -> "show_command_execution"
        is OutputEvent.FileOp -> when (event.operation.lowercase()) {
            "read" -> "show_file_reads"
            "write" -> "show_file_writes"
            "delete" -> "show_file_deletes"
            else -> "show_command_output"
        }
        is OutputEvent.FileDiff -> "show_code_diffs"
        is OutputEvent.Decision -> "show_status_symbols"
        is OutputEvent.Thinking -> "show_thinking_process"
    }

    /** Setting key for user prompt line (>>> [agent] task). */
    const val KEY_USER_PROMPT = "show_user_prompts"

    /** Setting key for MCP/tools line. */
    const val KEY_TOOL_CALL = "show_command_execution"

    /** Setting key for project load/unload system messages. */
    const val KEY_SYSTEM = "show_status_bar"

    /**
     * Attach the appropriate output setting key to a DTO built from [event].
     */
    fun withSettingKey(event: OutputEvent): String? = outputSettingKeyFor(event)
}
