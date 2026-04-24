# Index Provider

Code indexing and symbol extraction for the i2vision discovery system.

## Purpose

Provides code indexing capabilities including:
- Symbol extraction from source files
- Custom index implementation for different project structures
- Semantic path resolution for code navigation
- Integration with storage layer for index persistence

## Features

- **CustomIndex**: Flexible indexing for various project structures
- **SemanticPathResolver**: Semantic path analysis and resolution
- **ScannerService**: File scanning and symbol extraction
- **IndexProvider**: Generic interface for index implementations

## Usage

```kotlin
val indexProvider = CustomIndex(projectRoot)
val symbols = indexProvider.listSourceFiles()
val fileSymbols = indexProvider.symbolsInFile(filePath)
```

## Dependencies

- Kotlin stdlib
- SLF4J logging
- SnakeYAML
- storage-core

## License

MIT License - see LICENSE file for details.
