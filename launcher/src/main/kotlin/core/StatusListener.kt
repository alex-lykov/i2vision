package core

import java.time.Duration

interface StatusListener {
    fun onModelChanged(newModel: ModelType, reason: String?)
    fun onContextUpdated(usage: Float, files: Int)
    fun onTaskStarted(task: String)
    fun onTaskCompleted(duration: Duration, tokensUsed: Int)
    fun onError(throwable: Throwable)
}
