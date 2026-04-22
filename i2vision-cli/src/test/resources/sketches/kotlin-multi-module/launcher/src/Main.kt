package com.example.launcher

import com.example.orchestrator.AgentOrchestrator

fun main() {
    val orchestrator = AgentOrchestrator(
        config = com.example.config.Config(),
        session = com.example.session.SessionManager()
    )
    orchestrator.initialize(".")
}
