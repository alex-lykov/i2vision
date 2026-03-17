# i2vision-architecture

Architecture detection for multi-technology projects with framework and pattern recognition.

## Overview

i2vision-architecture provides intelligent detection of project architectures, including:
- Multi-language detection (Kotlin, Java, TypeScript, Python, Go, Rust, Swift, C#)
- Framework detection (Spring Boot, Ktor, React, Vue, Angular, etc.)
- Platform detection (Backend, Frontend, CLI, Desktop, Mobile, Infrastructure, Library)
- Architecture pattern detection (Layered, Hexagonal, Clean, Microservices, etc.)
- Multi-module project analysis
- Entry point pattern generation for intelligent code discovery

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:i2vision-architecture:1.0.0")
}
```

## Usage

### Detecting Technology Stack (Recommended)

```kotlin
import com.i2vision.architecture.ArchitectureDetector

val detector = ArchitectureDetector("/path/to/project")
val stack = detector.detectStack()

println("Primary Language: ${stack.primaryLanguage}")
println("Frameworks: ${stack.frameworks.joinToString()}")
println("Platforms: ${stack.platforms.joinToString()}")
println("Patterns: ${stack.patterns.joinToString()}")
println("Modules: ${stack.modules.size}")
println("Confidence: ${stack.confidence}")
```

### Legacy Detection (Deprecated)

```kotlin
import com.i2vision.architecture.ArchitectureDetector

val detector = ArchitectureDetector("/path/to/project")
val result = detector.detect()

println("Architecture: ${result.type}")
println("Confidence: ${result.confidence}")
println("Entry Points: ${result.entryPointPatterns.joinToString()}")
```

### Supported Languages

- Kotlin (.kt)
- Java (.java)
- TypeScript (.ts, .tsx)
- JavaScript (.js, .jsx)
- Python (.py)
- Go (.go)
- Rust (.rs)
- Swift (.swift)
- C# (.cs)

### Supported Frameworks

**Backend:**
- Spring Boot
- Ktor
- Micronaut
- Quarkus

**Frontend:**
- React
- Vue
- Angular

**CLI:**
- Clikt
- Picocli

**Desktop:**
- Compose Desktop
- JavaFX

**Mobile:**
- Android
- Compose Multiplatform

**Agent/AI:**
- Agent Framework

### Architecture Patterns

- Layered
- Hexagonal (Ports & Adapters)
- Clean Architecture
- Microservices
- Modular Monolith
- Event-Driven
- P2P
- Agent-Based

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.
