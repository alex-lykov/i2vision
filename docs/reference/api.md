# API Reference

Complete API documentation for i2vision.

## Base URL

```
http://localhost:3001
```

---

## Context Endpoints

### GET /context/health

Health check endpoint.

**Response:**

```json
{
  "status": "healthy",
  "service": "i2vision"
}
```

### GET /context/quick

Quick context with auto-detection.

**Parameters:**

- `q` (required): File path or query
- `type` (optional): `file`, `dir`, or `auto` (default: `auto`)

**Example:**

```bash
curl "http://localhost:3001/context/quick?q=src/main/Application.kt"
```

**Response:**

```json
{
  "query": "src/main/Application.kt",
  "type": "file",
  "context": "# Application.kt Context\n...",
  "confidence": 0.88,
  "status": "READY",
  "suggestions": ["Consider 'component-boundaries' for architecture view"]
}
```

### GET /context/file

Task-specific context for a file.

**Parameters:**

- `path` (required): File path
- `task` (optional): Task type (default: `code_analysis`)
    - `debug` - Debug mode with error analysis
    - `refactor` - Refactoring with dependency analysis
    - `testing` - Test coverage and quality
    - `security` - Security vulnerability scan
    - `performance` - Performance bottleneck analysis
    - `feature` - Feature development context

**Example:**

```bash
curl "http://localhost:3001/context/file?path=Application.kt&task=refactor"
```

**Response:**

```json
{
  "content": "# Refactoring Context for Application.kt\n...",
  "confidence": 0.92,
  "status": "READY",
  "layers": ["Structure", "Logic", "Flow"],
  "suggestedActions": ["component-boundaries", "dependency-audit"]
}
```

### GET /context/suggest

Get strategy suggestions for a file.

**Parameters:**

- `path` (required): File path

**Example:**

```bash
curl "http://localhost:3001/context/suggest?path=Application.kt"
```

**Response:**

```json
{
  "path": "Application.kt",
  "suggestions": [
    {
      "strategy": "component-boundaries",
      "reason": "Complex file with multiple responsibilities",
      "expectedImprovement": 0.35
    }
  ],
  "totalSuggestions": 1
}
```

---

## Analysis Endpoints

### GET /analyze/refactor

Get LLM-powered refactoring suggestions for a module.

**Parameters:**

- `path` (required): Module path
- `target` (optional): Specific target path within module

**Example:**

```bash
curl "http://localhost:3001/analyze/refactor?path=core/orchestrator"
```

**Response:**

```json
{
  "module": "core/orchestrator",
  "healthScore": 0.802,
  "complexityScore": 0.325,
  "maintainabilityScore": 0.992,
  "architectureStyle": "MICROSERVICES",
  "layerViolations": [
    {
      "type": "CIRCULAR_DEPENDENCY",
      "description": "3 circular dependencies detected",
      "severity": "CRITICAL"
    }
  ],
  "variants": [
    {
      "name": "Extract Shared Kernel",
      "description": "Extract common dependencies to shared module to break cycles",
      "targetStructure": "Shared kernel pattern",
      "estimatedEffort": "3-5 days",
      "complexity": "MEDIUM",
      "benefits": [
        "Breaks circular dependencies",
        "Reduces duplication",
        "Clearer dependency direction"
      ],
      "tradeoffs": [
        "Version coordination needed",
        "Shared module becomes critical",
        "Change impact analysis required"
      ],
      "steps": [
        "1. Identify components in cycles",
        "2. Extract common interfaces to 'shared' module",
        "3. Make both sides depend on abstractions",
        "4. Apply dependency inversion",
        "5. Verify with dependency-cruiser"
      ]
    }
  ],
  "llmPrompt": "You are an expert software architect specializing in VSLFC..."
}
```

---

### GET /analyze/compare

Compare two modules for cross-module analysis.

**Parameters:**

- `module1` (required): First module name
- `module2` (required): Second module name

**Example:**

```bash
curl "http://localhost:3001/analyze/compare?module1=core/orchestrator&module2=core/mcp"
```

**Response:**

```json
{
  "module1": "core/orchestrator",
  "module2": "core/mcp",
  "module1Metrics": {
    "healthScore": 0.805,
    "complexityScore": 0.325,
    "maintainabilityScore": 0.992
  },
  "module2Metrics": {
    "healthScore": 0.876,
    "complexityScore": 0.289,
    "maintainabilityScore": 0.985
  },
  "sharedIssues": [],
  "coupling": {
    "couplingScore": 0.15,
    "dependencies1to2": 2,
    "dependencies2to1": 1,
    "totalDependencies": 3,
    "couplingLevel": "LOW"
  },
  "recommendation": "Modules are well-separated"
}
```

---

## Intelligence Endpoints

### POST /intelligence/proactive/file-opened

Proactively get context when a file is opened.

**Parameters:**

- `path` (required): Opened file path
- `userId` (optional): User identifier (default: `default`)

**Example:**

```bash
curl "http://localhost:3001/intelligence/proactive/file-opened?path=Application.kt&userId=user123"
```

**Response:**

```json
{
  "primaryContext": {
    "content": "...",
    "confidence": 0.88
  },
  "relatedContexts": [
    {
      "context": {...},
      "relevanceScore": 0.75,
      "predictedNeed": "Often accessed together",
      "triggerEvent": "FILE_OPENED"
    }
  ],
  "suggestions": ["3 related files identified"],
  "nextLikelyActions": ["testing", "refactor"]
}
```

### POST /intelligence/proactive/task-started

Pre-fetch context when starting a task.

**Parameters:**

- `task` (required): Task description
- `files` (optional): Comma-separated list of current files

**Example:**

```bash
curl "http://localhost:3001/intelligence/proactive/task-started?task=Fix%20authentication%20bug&files=Auth.kt,User.kt"
```

**Response:**

```json
{
  "taskType": "debug",
  "prefetchedContexts": [...],
  "estimatedTimesSaved": 9,
  "confidenceScore": 0.82
}
```

### POST /intelligence/enhance-request

Enhance an LLM request with project context.

**Parameters:**

- `request` (required): Original LLM request
- `userId` (optional): User identifier (default: `default`)

**Example:**

```bash
curl "http://localhost:3001/intelligence/enhance-request?request=How%20does%20authentication%20work?"
```

**Response:**

```json
{
  "originalRequest": "How does authentication work?",
  "enhancedPrompt": "# Enhanced Request with Project Context\n\n## Primary Context:\n...",
  "contextSources": [
    {
      "context": {...},
      "filePath": "Auth.kt",
      "relevanceScore": 1.0,
      "reasoning": "Directly mentioned in request"
    }
  ],
  "confidenceBoost": 0.35,
  "estimatedQualityImprovement": 0.6
}
```

### GET /intelligence/quality

Get comprehensive quality metrics for a file.

**Parameters:**

- `path` (required): File path

**Example:**

```bash
curl "http://localhost:3001/intelligence/quality?path=Application.kt"
```

**Response:**

```json
{
  "cohesionScore": 0.75,
  "couplingScore": 0.42,
  "complexityScore": 0.58,
  "abstractionLevel": 0.65,
  "testCoverageScore": 0.70,
  "documentationFreshness": 0.82,
  "changeFrequency": 0.35,
  "bugsProneScore": 0.28,
  "maintainabilityIndex": 0.72,
  "refactoringPriority": "MEDIUM",
  "securityRiskLevel": "LOW",
  "performanceImpact": "MEDIUM",
  "topRecommendations": [
    {
      "type": "REFACTORING",
      "priority": 4,
      "description": "High coupling (42%) - consider dependency injection",
      "expectedImpact": "Improved testability and maintainability",
      "estimatedEffort": "2-4 hours",
      "strategySuggestion": "dependency-audit"
    }
  ],
  "riskFactors": [...],
  "improvementOpportunities": [...],
  "moduleRanking": {
    "overallRank": 42,
    "totalModules": 100,
    "percentile": 72.0,
    "strengths": ["High maintainability"],
    "improvementAreas": ["Reduce coupling"]
  },
  "industryBenchmark": {
    "cohesionPercentile": 75.0,
    "complexityPercentile": 65.0,
    "testCoveragePercentile": 70.0,
    "overallHealthGrade": "B"
  }
}
```

### POST /intelligence/feedback

Record user feedback on context quality.

**Parameters:**

- `contextId` (required): Context identifier
- `helpful` (required): Boolean (true/false)
- `rating` (optional): Rating 1-5 (default: 3)
- `comment` (optional): Feedback comment

**Example:**

```bash
curl -X POST "http://localhost:3001/intelligence/feedback?contextId=ctx123&helpful=true&rating=5&comment=Very%20helpful"
```

**Response:**

```json
{
  "status": "success",
  "contextId": "ctx123"
}
```

### GET /intelligence/strategy-recommend

Get AI-recommended strategy for a file and task.

**Parameters:**

- `path` (required): File path
- `task` (optional): Task type (default: `code_analysis`)

**Example:**

```bash
curl "http://localhost:3001/intelligence/strategy-recommend?path=Application.kt&task=refactor"
```

**Response:**

```json
{
  "recommendedStrategy": "component-boundaries",
  "confidence": 0.85,
  "reasoning": "Best performer for kt files with refactor task (45 samples)",
  "alternativeStrategies": [
    {
      "name": "dependency-audit",
      "expectedImprovement": -0.12,
      "reasoning": "Historical effectiveness: 73%"
    }
  ]
}
```

### GET /intelligence/learning-insights

Get insights from the learning loop.

**Example:**

```bash
curl "http://localhost:3001/intelligence/learning-insights"
```

**Response:**

```json
{
  "totalContextsTracked": 1247,
  "strategiesEvaluated": 12,
  "improvementEvents": 35,
  "degradationEvents": 8,
  "newPatternsDiscovered": 5,
  "topPerformingStrategies": [
    {
      "name": "component-boundaries",
      "successRate": 0.88,
      "usageCount": 234,
      "averageQuality": 0.82,
      "trend": 0.05
    }
  ],
  "overallSystemImprovement": 0.15
}
```

---

## Quality Metrics Explained

### Cohesion Score (0-1)

How well the internals of a module belong together. Higher is better.

- **0.8-1.0**: Excellent - Single, well-defined responsibility
- **0.6-0.8**: Good - Mostly cohesive with minor issues
- **0.4-0.6**: Fair - Some unrelated functionality
- **0.0-0.4**: Poor - Multiple responsibilities, needs refactoring

### Coupling Score (0-1)

How tightly a module depends on others. Lower is better.

- **0.0-0.3**: Excellent - Minimal dependencies
- **0.3-0.5**: Good - Reasonable coupling
- **0.5-0.7**: Fair - High coupling, consider refactoring
- **0.7-1.0**: Poor - Very high coupling, difficult to maintain

### Complexity Score (0-1)

Cyclomatic complexity normalized. Lower is better.

- **0.0-0.3**: Simple - Easy to understand
- **0.3-0.5**: Moderate - Acceptable complexity
- **0.5-0.7**: Complex - Consider simplification
- **0.7-1.0**: Very Complex - High risk, needs refactoring

### Maintainability Index (0-1)

Overall code health. Higher is better.

- **0.8-1.0**: Excellent - Easy to maintain
- **0.6-0.8**: Good - Acceptable maintenance burden
- **0.4-0.6**: Fair - Significant effort needed
- **0.0-0.4**: Poor - High maintenance cost

---

## Task Types

| Task            | Purpose                | Context Focus                      |
|-----------------|------------------------|------------------------------------|
| `code_analysis` | General code review    | Structure, patterns, quality       |
| `debug`         | Fix bugs/errors        | Dependencies, error paths, state   |
| `refactor`      | Improve code structure | Coupling, cohesion, patterns       |
| `testing`       | Write/improve tests    | Coverage, test files, assertions   |
| `security`      | Security review        | Vulnerabilities, auth, validation  |
| `performance`   | Optimize performance   | Bottlenecks, algorithms, resources |
| `feature`       | Add new features       | Architecture, integration points   |

---

## Best Practices

### 1. Use Task-Specific Context

```javascript
// ✅ Good - Task-specific optimization
const ctx = await fetch(`/context/file?path=${file}&task=refactor`);

// ❌ Suboptimal - Generic context
const ctx = await fetch(`/context/quick?q=${file}`);
```

### 2. Leverage Proactive Context

```javascript
// ✅ Proactively fetch when file opens
onFileOpen(async (file) => {
  const proactive = await fetch(`/intelligence/proactive/file-opened?path=${file}`);
  // Context ready before LLM asks
});
```

### 3. Provide Feedback

```javascript
// ✅ Help the system learn
await fetch(`/intelligence/feedback?contextId=${id}&helpful=true&rating=5`);
```

### 4. Use Quality Metrics

```javascript
// ✅ Get comprehensive quality analysis
const quality = await fetch(`/intelligence/quality?path=${file}`);
if (quality.refactoringPriority === 'HIGH') {
  // Show refactoring suggestions
}
```

### 5. Check Learning Insights

```javascript
// ✅ Monitor system improvement
const insights = await fetch(`/intelligence/learning-insights`);
console.log(`System improvement: ${insights.overallSystemImprovement * 100}%`);
```

---

## Integration Examples

### Claude Desktop MCP Tool

```json
{
  "mcpServers": {
    "i2vision-context": {
      "command": "powershell",
      "args": [
        "-File",
        "./i2vision-context.ps1",
        "-StartServer"
      ]
    }
  }
}
```

### VSCode Extension

```typescript
import axios from 'axios';

const i2visionContext = {
  baseUrl: 'http://localhost:3001',
  
  async getContext(file: string, task: string = 'code_analysis') {
    const response = await axios.get(`${this.baseUrl}/context/file`, {
      params: { path: file, task }
    });
    return response.data;
  },
  
  async getQuality(file: string) {
    const response = await axios.get(`${this.baseUrl}/intelligence/quality`, {
      params: { path: file }
    });
    return response.data;
  }
};
```

### Python Script

```python
import requests

def get_context(file_path, task='code_analysis'):
    response = requests.get(
        'http://localhost:3001/context/file',
        params={'path': file_path, 'task': task}
    )
    return response.json()

def get_quality_metrics(file_path):
    response = requests.get(
        'http://localhost:3001/intelligence/quality',
        params={'path': file_path}
    )
    return response.json()
```

