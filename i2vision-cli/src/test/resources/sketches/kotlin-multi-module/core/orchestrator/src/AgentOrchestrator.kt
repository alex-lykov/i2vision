package com.example.orchestrator

class AgentOrchestrator(
    private val config: com.example.config.Config,
    private val session: com.example.session.SessionManager
) {
    fun initialize(projectPath: String) {
        config.load(projectPath)
        session.start()
    }
}
