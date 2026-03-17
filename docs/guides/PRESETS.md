# Presets Guide

## Overview

Presets are pre-configured discovery and analysis configurations that can be applied to projects. They encapsulate best practices for specific project types, frameworks, or use cases.

## Preset Structure

### Preset Configuration

Presets are defined in YAML format:

```yaml
name: "Spring Boot Preset"
description: "Optimized configuration for Spring Boot projects"
version: "1.0.0"

discovery:
  strategies:
    - name: "spring-boot-standard"
      confidence: 0.9
      priority: 1
  
  layers:
    vision:
      patterns:
        - "**/README.md"
        - "**/src/main/resources/application*.yml"
    structure:
      patterns:
        - "**/build.gradle.kts"
        - "**/pom.xml"
        - "**/src/main/java/**/*.java"
    logic:
      patterns:
        - "**/src/main/java/**/*Service.java"
        - "**/src/main/java/**/*Controller.java"
    flow:
      patterns:
        - "**/src/main/java/**/*Application.java"
    code:
      patterns:
        - "**/src/main/java/**/*.java"
        - "**/src/test/java/**/*.java"

analysis:
  complexity:
    enabled: true
    threshold: 15
  
  patterns:
    enabled: true
    detect:
      - "singleton"
      - "factory"
      - "observer"
  
  code_smells:
    enabled: true
    detect:
      - "long_method"
      - "god_class"
      - "feature_envy"
  
  maintainability:
    enabled: true
    min_score: 50

cache:
  enabled: true
  expiry_minutes: 5
  max_size_mb: 100

output:
  format: "yaml"
  include:
    - "vslfc_artifacts"
    - "complexity_details"
    - "strategy_suggestions"
```

## Built-in Presets

### Standard Presets

**Java Maven**
```yaml
name: "Java Maven"
description: "Standard Maven project structure"
strategies: ["maven-standard"]
```

**Kotlin Gradle**
```yaml
name: "Kotlin Gradle"
description: "Gradle-based Kotlin projects"
strategies: ["kotlin-gradle"]
```

**Spring Boot**
```yaml
name: "Spring Boot"
description: "Spring Boot framework projects"
strategies: ["spring-boot-standard"]
```

**Node.js**
```yaml
name: "Node.js"
description: "Node.js and npm projects"
strategies: ["nodejs-standard"]
```

**Python**
```yaml
name: "Python"
description: "Python projects with pip/poetry"
strategies: ["python-standard"]
```

### Framework-Specific Presets

**React**
```yaml
name: "React"
description: "React frontend projects"
strategies: ["react-standard"]
```

**Django**
```yaml
name: "Django"
description: "Django web framework projects"
strategies: ["django-standard"]
```

**Rails**
```yaml
name: "Rails"
description: "Ruby on Rails projects"
strategies: ["rails-standard"]
```

## Using Presets

### Loading a Preset

```kotlin
import com.i2vision.instant.artifact.ArtifactDiscoveryConfig

val presetPath = "/presets/spring-boot.yaml"
val config = ArtifactDiscoveryConfig.load(presetPath)

val loader = GenericArtifactLoader(projectRoot, config)
val artifacts = loader.loadArtifacts(modulePath)
```

### Applying Preset to ContextProvider

```kotlin
val contextProvider = ContextProvider(projectRoot)
contextProvider.applyPreset(presetPath)

val context = contextProvider.getContext(filePath, modulePath)
```

### Programmatic Preset Creation

```kotlin
val customPreset = ArtifactDiscoveryConfig(
    layers = listOf(
        LayerConfig(
            name = "vision",
            patterns = listOf("**/*.md", "**/*.yaml"),
            mergeStrategy = MergeStrategy.KEEP_ALL
        ),
        LayerConfig(
            name = "structure",
            patterns = listOf("**/build.gradle.kts"),
            mergeStrategy = MergeStrategy.PREFER_LATEST
        )
    )
)

// Save preset
val yaml = Yaml()
val writer = FileWriter("/presets/custom.yaml")
yaml.dump(customPreset, writer)
```

## Creating Custom Presets

### Step 1: Define Project Characteristics

Identify the key characteristics of your project type:
- Build system (Maven, Gradle, npm, pip)
- Framework (Spring, React, Django)
- Language (Java, Kotlin, JavaScript, Python)
- Directory structure conventions

### Step 2: Configure Discovery Strategies

```yaml
discovery:
  strategies:
    - name: "custom-framework"
      confidence: 0.85
      priority: 1
      patterns:
        - "**/framework-config.xml"
        - "**/custom/**"
```

### Step 3: Define Layer Patterns

```yaml
layers:
  vision:
    patterns:
      - "**/README.md"
      - "**/docs/**"
      - "**/contributing.md"
  
  structure:
    patterns:
      - "**/build.gradle.kts"
      - "**/settings.gradle.kts"
      - "**/src/**"
  
  logic:
    patterns:
      - "**/src/main/java/**/*Service.java"
      - "**/src/main/java/**/*Repository.java"
  
  flow:
    patterns:
      - "**/src/main/java/**/*Application.java"
      - "**/src/main/java/**/*Config.java"
  
  code:
    patterns:
      - "**/src/main/java/**/*.java"
      - "**/src/test/java/**/*.java"
```

### Step 4: Configure Analysis Settings

```yaml
analysis:
  complexity:
    enabled: true
    threshold: 20
    include_test_files: false
  
  patterns:
    enabled: true
    detect:
      - "singleton"
      - "factory"
      - "builder"
      - "strategy"
  
  code_smells:
    enabled: true
    detect:
      - "long_method"
      - "god_class"
      - "duplicate_code"
      - "magic_numbers"
  
  maintainability:
    enabled: true
    min_score: 60
    factors:
      - "complexity"
      - "duplication"
      - "coupling"
```

### Step 5: Configure Caching

```yaml
cache:
  enabled: true
  expiry_minutes: 10
  max_size_mb: 200
  cleanup_interval_minutes: 30
```

### Step 6: Define Output Format

```yaml
output:
  format: "yaml"
  include:
    - "vslfc_artifacts"
    - "complexity_details"
    - "strategy_suggestions"
    - "artifacts"
  
  exclude:
    - "raw_content"
    - "debug_info"
```

## Preset Inheritance

Presets can inherit from other presets:

```yaml
name: "Spring Boot Microservice"
description: "Spring Boot microservice configuration"
extends: "spring-boot.yaml"

overrides:
  discovery:
    strategies:
      - name: "microservice-pattern"
        confidence: 0.95
        priority: 0
  
  layers:
    flow:
      patterns:
        - "**/src/main/java/**/*Controller.java"
        - "**/src/main/java/**/*Service.java"
        - "**/src/main/java/**/*Repository.java"
```

## Preset Validation

### Schema Validation

Presets should be validated against a schema:

```kotlin
fun validatePreset(preset: ArtifactDiscoveryConfig): List<String> {
    val errors = mutableListOf<String>()
    
    if (preset.layers.isEmpty()) {
        errors.add("Preset must define at least one layer")
    }
    
    preset.layers.forEach { layer ->
        if (layer.patterns.isEmpty()) {
            errors.add("Layer '${layer.name}' must have patterns")
        }
    }
    
    return errors
}
```

### Preset Testing

```kotlin
class PresetTest {
    
    @Test
    fun testSpringBootPreset() {
        val preset = ArtifactDiscoveryConfig.load("/presets/spring-boot.yaml")
        val errors = validatePreset(preset)
        
        assertTrue(errors.isEmpty(), "Preset validation failed: ${errors.joinToString()}")
    }
    
    @Test
    fun testPresetApplication() {
        val preset = ArtifactDiscoveryConfig.load("/presets/spring-boot.yaml")
        val loader = GenericArtifactLoader(testProjectRoot, preset)
        
        val artifacts = loader.loadArtifacts("/src/main/java")
        
        assertNotNull(artifacts)
        assertTrue(artifacts.isNotEmpty())
    }
}
```

## Preset Distribution

### Packaging Presets

Presets can be distributed as part of the i2vision distribution:

```
presets/
  ├── standard/
  │   ├── java-maven.yaml
  │   ├── kotlin-gradle.yaml
  │   └── nodejs.yaml
  ├── frameworks/
  │   ├── spring-boot.yaml
  │   ├── react.yaml
  │   └── django.yaml
  └── custom/
      └── your-preset.yaml
```

### Sharing Presets

Presets can be shared via:
- Git repositories
- Package managers
- Configuration management systems
- Internal documentation

## Best Practices

### Naming Conventions
- Use kebab-case for preset names
- Include framework/language in name
- Use descriptive names for custom presets

### Versioning
- Include version in preset metadata
- Use semantic versioning
- Document breaking changes

### Documentation
- Include description of use case
- Document any special requirements
- Provide examples of projects suited for preset

### Performance
- Minimize pattern complexity
- Use efficient glob patterns
- Configure appropriate cache settings

## Examples

See existing presets in the project for reference:
- `presets/standard/` - Standard project presets
- `presets/frameworks/` - Framework-specific presets
- `presets/custom/` - Custom user presets
