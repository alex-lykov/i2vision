package session

import com.alyk.ai.koog.core.session.DecisionLogEntry
import com.alyk.ai.koog.core.session.ISessionStore
import com.alyk.ai.koog.core.session.Session
import com.alyk.ai.koog.database.store.AgentStateStore
import com.alyk.ai.koog.database.store.PersistedSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Session store backed by the database provider (agent state persistence).
 * Converts between [Session] and [PersistedSession].
 */
class DatabaseBackedSessionStore(
    private val agentState: AgentStateStore
) : ISessionStore {

    private val log = LoggerFactory.getLogger(DatabaseBackedSessionStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createSession(projectPath: String?): UUID {
        val id = agentState.createSession(projectPath)
        log.info("Created session id={} projectPath={}", id, projectPath)
        return id
    }

    override suspend fun getSession(sessionId: UUID): Session? {
        val p = agentState.getSession(sessionId) ?: return null
        return toSession(p)
    }

    override suspend fun updateSession(sessionId: UUID, update: (Session) -> Session) {
        val current = agentState.getSession(sessionId) ?: run {
            log.warn("updateSession: session not found id={}", sessionId)
            return
        }
        val updated = update(toSession(current))
        agentState.updateSession(sessionId) {
            PersistedSession(
                id = updated.id,
                projectPath = updated.projectPath,
                conversationHistory = updated.conversationHistory,
                activeModel = updated.activeModel,
                updatedAtMillis = System.currentTimeMillis(),
                currentPhase = updated.currentPhase,
                currentGoal = updated.currentGoal,
                workflowPhase = updated.workflowPhase,
                completedSteps = updated.completedSteps,
                decisionLogJson = if (updated.decisionLog.isEmpty()) null
                    else json.encodeToString(ListSerializer(DecisionLogEntry.serializer()), updated.decisionLog)
            )
        }
        log.debug("Updated session id={}", sessionId)
    }

    override suspend fun deleteSession(sessionId: UUID) {
        agentState.deleteSession(sessionId)
        log.info("Deleted session id={}", sessionId)
    }

    override suspend fun cleanupInactiveSessions(maxAgeMinutes: Long) {
        agentState.cleanupInactiveSessions(maxAgeMinutes)
        log.debug("Cleanup inactive sessions maxAgeMinutes={}", maxAgeMinutes)
    }

    private fun toSession(p: PersistedSession): Session {
        val decisionLog = p.decisionLogJson?.let {
            try {
                json.decodeFromString<List<DecisionLogEntry>>(it).toMutableList()
            } catch (e: Exception) {
                log.warn("Failed to decode decision log: {}", e.message)
                mutableListOf()
            }
        } ?: mutableListOf()
        return Session(
            id = p.id,
            projectPath = p.projectPath,
            conversationHistory = p.conversationHistory.toMutableList(),
            activeModel = p.activeModel,
            currentPhase = p.currentPhase,
            currentGoal = p.currentGoal,
            workflowPhase = p.workflowPhase,
            completedSteps = p.completedSteps.toMutableList(),
            decisionLog = decisionLog
        )
    }
}
