# Performance Monitoring System

## Overview

Real-time monitoring for detecting slow model responses and context window pressure.

## Architecture

```
LocalModelWrapper.generate()
    ↓ (measures)
PerformanceMonitor.recordMetric()
    ↓ (analyzed by)
DecisionEngine.getPerformanceWarnings() / getPerformanceAlerts()
    ↓ (exposed via)
AgentOrchestrator → UI/StatusBar
```

## Components

### 1. PerformanceMonitor (`switching:monitor`)

**Location**: `@D:\proj\AI\koog-coding-agent\switching\monitor\src\main\kotlin\com\alyk\ai\koog\switching\monitor\PerformanceMonitor.kt`

**Core Methods**:
- `recordMetric(responseTime, tokensUsed, tokensGenerated)` - Captures each request
- `detectWarningSignals()` - Returns list of detected issues
- `checkThresholds()` - Returns alerts exceeding thresholds
- `getStats()` - Summary statistics

**Thresholds**:
| Metric | Warning | Critical |
|--------|---------|----------|
| Response Time | >5s | >15s |
| Token Ratio | >2x used vs generated | - |
| Empty Output | 0 tokens generated | - |

### 2. Signal Types

```kotlin
enum class SignalType {
    SLOW_RESPONSE,      // Response time exceeded threshold
    HIGH_TOKEN_RATIO,   // Inefficient token usage
    TRUNCATION          // Empty/missing output
}

enum class Severity { LOW, MEDIUM, HIGH }
```

### 3. Integration Points

**ModelWrapper** (`models:wrappers`):
```kotlin
val responseTime = measureTimeMillis {
    response = agent.run(prompt)
}
performanceMonitor?.recordMetric(
    responseTime = responseTime,
    tokensUsed = estimateTokens(prompt),
    tokensGenerated = estimateTokens(response)
)
```

**DecisionEngine** (`switching:decision`):
- Delegates to `performanceMonitor` for all monitoring queries
- Exposes: `getPerformanceStats()`, `getPerformanceWarnings()`, `getPerformanceAlerts()`

**AgentOrchestrator** (`core:orchestrator`):
- Single entry point for UI to query monitoring state
- Methods mirror DecisionEngine but provide unified access

## Usage

### Check Current Performance

```kotlin
val stats = orchestrator.getPerformanceStats()
println("Avg response: ${stats.avgResponseTimeMs}ms")
println("Total requests: ${stats.totalRequests}")
```

### Get Active Warnings

```kotlin
val warnings = orchestrator.getPerformanceWarnings()
warnings.forEach { signal ->
    println("[${signal.severity}] ${signal.type}: ${signal.message}")
}
```

### Check for Alerts

```kotlin
val alerts = orchestrator.getPerformanceAlerts()
if (alerts.isNotEmpty()) {
    // Trigger cloud model fallback
    // Or show warning in UI
}
```

## Interpreting Signals

### Slow Response (>5s)
**Cause**: Model struggling with context window or CPU-bound inference.
**Action**: Consider reducing context size or switching to cloud model.

### Very Slow Response (>15s)  
**Cause**: Severe context pressure or system resource exhaustion.
**Action**: Immediate fallback to cloud model recommended.

### High Token Ratio (>2x)
**Cause**: Large prompt but minimal output - context window filling up.
**Action**: Prune context or use hierarchical context reduction.

### Truncation (0 tokens generated)
**Cause**: Model failed to generate, possibly hit token limit mid-generation.
**Action**: Check if response was cut off, retry with smaller context.

## Configuration

Edit thresholds in `PerformanceMonitor.kt`:

```kotlin
companion object {
    const val SLOW_RESPONSE_THRESHOLD_MS = 5000L
    const val VERY_SLOW_RESPONSE_THRESHOLD_MS = 15000L
    const val HIGH_TOKEN_RATIO_THRESHOLD = 2.0
    const val SLIDING_WINDOW_SIZE = 10
}
```

## Data Structure

```kotlin
data class PerformanceStats(
    val totalRequests: Int,
    val avgResponseTimeMs: Long,
    val maxResponseTimeMs: Long,
    val totalTokensUsed: Int,
    val totalTokensGenerated: Int
)

data class WarningSignal(
    val type: SignalType,
    val severity: Severity,
    val message: String
)

data class Alert(
    val message: String,
    val threshold: String,
    val currentValue: Double
)
```

## Future Extensions

1. **Persistence**: Store historical metrics for trend analysis
2. **Adaptive Thresholds**: Adjust based on user's typical response times
3. **Automatic Switching**: Use alerts to trigger cloud model fallback
4. **UI Dashboard**: Real-time charts of response times and token usage
