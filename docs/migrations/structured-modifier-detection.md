# Migration Guide: Regex to Structured Modifier Detection

## Overview

This guide covers the migration from the legacy regex-based modifier detection to the new structured AST-based detection system introduced in i2vision v2.0.

### What Changed

| Aspect | Before (Regex) | After (Structured) |
|--------|----------------|-------------------|
| **Detection Method** | Pattern matching on raw code text | AST/PSI analysis on parsed code |
| **Accuracy** | ~70-80% (false positives/negatives) | ~95%+ (precise AST traversal) |
| **Language Support** | Single-language patterns | Multi-language via extractors |
| **Performance** | O(n) text scanning | O(1) AST node lookup |
| **Extensibility** | Modify regex patterns | Add extractor implementations |

### Why This Change

1. **Accuracy**: AST analysis correctly identifies modifiers regardless of code formatting, comments, or string literals
2. **Multi-language**: Same architecture supports Kotlin, Java, TypeScript via language-specific extractors
3. **Extensibility**: New modifiers added by implementing `ModifierExtractor` interface
4. **Performance**: Parallel extraction with cached PSI/Treesitter nodes
5. **Debuggability**: Clear separation between detection and verbalization

---

## Breaking Changes

### API Changes

```kotlin
// BEFORE: Direct regex pattern matching
class PatternMatcher {
    fun matchAndDescribe(symbol: Symbol): String? { ... }
}

// AFTER: Structured enrichment with separate verbalization
val enriched = symbol.enrich {
    addModifiers(extractor.extractModifiers(node))
    setStructuralRole(extractor.extractStructuralRole(node))
}
val description = verbalizer.verbalize(enriched)
```

### Removed Components

| Removed | Replacement |
|---------|-------------|
| `PatternMatcher` | `KotlinModifierExtractor`, `JavaModifierExtractor` |
| `VerbalizationPattern` | `ModifierKind` enum + `SymbolModifier` data class |
| Built-in regex patterns | `ModifierExtractor.extractModifiers()` |
| `matchAndDescribe()` | `extractModifiers()` + `verbalizeModifiers()` |

### Cache Format Update

```json
// BEFORE: Symbol only
{
  "symbol": { "name": "UserRepository", "kind": "class" }
}

// AFTER: EnrichedSymbol with modifiers
{
  "symbol": { "name": "UserRepository", "kind": "class" },
  "modifiers": [
    { "kind": "REPOSITORY", "source": "ANNOTATION" },
    { "kind": "DATABASE_ACCESS", "source": "INFERENCE" }
  ],
  "structuralRole": "REPOSITORY",
  "dependencies": ["User"],
  "technicalContext": { "hasDatabaseAccess": true }
}
```

---

## Migration Steps

### Step 1: Update Dependencies

Add the architecture-types module to your project:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.i2vision:architecture-types:2.0.0")
    implementation("com.i2vision:verbalization-core:2.0.0")
}
```

### Step 2: Re-run Discovery

Clear existing cache and re-run discovery to populate modifier data:

```bash
# Clear old cache
rm -rf ~/.i2vision/cache

# Re-run discovery
./i2vision discover --path /your/project --output ./discovery.json
```

### Step 3: Update Custom Verbalizers

If you implemented custom verbalization logic:

```kotlin
// BEFORE: Inline pattern matching
class CustomVerbalizer {
    fun verbalize(symbol: Symbol): String {
        return when {
            symbol.content.contains("suspend ") -> "suspending"
            symbol.content.contains("data class") -> "data class"
            else -> "standard"
        }
    }
}

// AFTER: Use structured modifiers
class CustomVerbalizer(
    private val extractor: KotlinModifierExtractor,
    private val verbalizer: KotlinModifierVerbalizer
) {
    fun verbalize(symbol: Symbol, node: PsiElement): String {
        val enriched = extractor.enrich(symbol, node)
        return verbalizer.verbalize(enriched)
    }
}
```

### Step 4: Update Tests

Update test assertions to use modifier-based verification:

```kotlin
// BEFORE: Text-based assertions
@Test
fun `service class verbalization`() {
    val description = verbalizer.verbalize(serviceSymbol)
    assertTrue(description.contains("Service"))
    assertTrue(description.contains("business logic"))
}

// AFTER: Modifier-based assertions
@Test
fun `service class verbalization`() {
    val enriched = enrichWithModifiers(serviceSymbol)
    assertEquals(StructuralRole.SERVICE, enriched.structuralRole)
    assertTrue(enriched.hasModifier(ModifierKind.SERVICE))
    
    val description = verbalizer.verbalize(enriched)
    assertTrue(description.contains("service component"))
}
```

### Step 5: Verify Output

Compare old and new verbalization output:

```kotlin
// Verify key patterns are preserved
assertTrue(newOutput.contains("Repository"))
assertTrue(newOutput.contains("data class"))
assertTrue(newOutput.contains("suspending"))
```

---

## Code Examples

### Example 1: Service Class Detection

**Code being analyzed:**
```kotlin
@Repository
class UserRepository(private val entityManager: EntityManager) {
    suspend fun findById(id: Long): User? { ... }
}
```

**Before (Regex):**
```kotlin
// PatternMatcher would match:
// - "class\s+(\w+)Repository" → "Data repository for User entities"
// - "suspend\s+fun" → "Asynchronously executes findById operation"
val patterns = loadBuiltInPatterns()
val description = patterns.matchAndDescribe(symbol)
```

**After (Structured):**
```kotlin
val extractor = KotlinModifierExtractor()
val node = parseToPsi(symbol.filePath)

val enriched = extractor.enrich(symbol, node) {
    // Auto-extracted:
    // - ModifierKind.REPOSITORY (from @Repository annotation)
    // - ModifierKind.SUSPEND (from suspend keyword)
    // - ModifierKind.DATABASE_ACCESS (from entityManager reference)
    // - StructuralRole.REPOSITORY (from @Repository)
}

val verbalizer = KotlinModifierVerbalizer()
val description = verbalizer.verbalize(enriched)
// Result: "Data repository for User entities - repository - suspending {database access}"
```

### Example 2: Validation Detection

**Code being analyzed:**
```kotlin
data class Order(
    val items: List<Item>,
    val total: BigDecimal
) {
    init {
        require(items.isNotEmpty()) { "Order must have items" }
        require(total > BigDecimal.ZERO) { "Total must be positive" }
    }
}
```

**Before (Regex):**
```kotlin
// PatternMatcher matches require(...) patterns
// "require\((.+)\)" → "Validates that items.isNotEmpty()"
```

**After (Structured):**
```kotlin
val enriched = extractor.enrich(symbol, node) {
    // Auto-extracted:
    // - ModifierKind.DATA_CLASS (from data keyword)
    // - ModifierKind.BUSINESS_RULE x2 (from require statements)
    // - ModifierKind.INVARIANT (from init block)
    // - StructuralRole.ENTITY (from data class with validation)
}

val description = verbalizer.verbalize(enriched)
// Result: "Immutable data container - enforces business rules - maintains invariants {validation}"
```

---

## Troubleshooting

### Issue: Missing Modifiers After Migration

**Symptoms:**
- Verbalization output is missing expected terms like "suspend", "data class"
- `enriched.modifiers` is empty

**Solutions:**
1. **Re-run discovery** - Cache may not have modifier data
   ```bash
   ./i2vision discover --rebuild --path /your/project
   ```

2. **Verify PSI parsing** - Check that source files parse correctly
   ```kotlin
   val psiFile = PsiManager.getInstance(project).findFile(file)
   assertNotNull(psiFile, "File should parse without errors")
   ```

3. **Check language compatibility** - Ensure file extension is recognized
   ```kotlin
   val extractor = when(file.extension) {
       "kt" -> KotlinModifierExtractor()
       "java" -> JavaModifierExtractor()
       else -> null
   }
   ```

### Issue: Wrong Role Detected

**Symptoms:**
- Class detected as wrong role (e.g., Controller detected as Service)

**Solutions:**
1. **Check annotation priority** - Annotations take precedence over naming
   ```kotlin
   // @Controller takes priority over "*Service" naming
   @Controller
   class UserService { ... } // Correctly detected as CONTROLLER
   ```

2. **Verify annotation imports** - Ensure correct Spring imports
   ```kotlin
   import org.springframework.stereotype.Controller  // ✓ Correct
   import some.other.library.Controller              // ✗ Not recognized
   ```

3. **Add custom detection** - Extend extractor for custom annotations
   ```kotlin
   class CustomModifierExtractor : KotlinModifierExtractor() {
       override fun extractStructuralRole(node: KtDeclaration): StructuralRole? {
           if (node.hasAnnotation("com.myapp.CustomService")) {
               return StructuralRole.SERVICE
           }
           return super.extractStructuralRole(node)
       }
   }
   ```

### Issue: Verbalization Format Changed

**Symptoms:**
- Output format different from before
- Missing punctuation or separators

**Solutions:**
1. **Update verbalizer configuration**
   ```kotlin
   val verbalizer = KotlinModifierVerbalizer()
   val context = VerbalizationContext(
       baseDescription = description,
       enrichedSymbol = enriched,
       includeModifiers = true,
       includeRole = true,
       includeTechnicalContext = true
   )
   ```

2. **Customize phrase combining**
   ```kotlin
   override fun combinePhrases(phrases: List<String>): String {
       // Custom format: semicolon-separated
       return phrases.joinToString("; ")
   }
   ```

### Issue: Performance Degradation

**Symptoms:**
- Discovery takes longer than before

**Solutions:**
1. **Enable parallel extraction**
   ```kotlin
   val extractor = KotlinModifierExtractor()
   val enriched = symbols.parallelStream()
       .map { extractor.enrich(it, parsePsi(it)) }
       .toList()
   ```

2. **Cache PSI elements**
   ```kotlin
   val psiCache = LruCache<File, PsiFile>(100)
   fun getPsi(file: File): PsiFile = psiCache.getOrPut(file) {
       PsiManager.getInstance(project).findFile(file)
   }
   ```

---

## FAQ

### Q: Can I still use regex patterns?

**A:** Yes, but as a fallback. The structured system is primary:
```kotlin
class HybridVerbalizer(
    private val extractor: ModifierExtractor<*>,
    private val verbalizer: ModifierVerbalizer
) {
    fun verbalize(symbol: Symbol): String {
        // Try structured first
        val enriched = tryExtractStructured(symbol)
        if (enriched != null) {
            return verbalizer.verbalize(enriched)
        }
        // Fallback to regex
        return RegexVerbalizer.verbalize(symbol)
    }
}
```

### Q: How do I add a new modifier kind?

**A:** Extend the `ModifierKind` enum and add extraction logic:
```kotlin
// 1. Add to ModifierKind enum
enum class ModifierKind {
    // ... existing ...
    MY_CUSTOM_MODIFIER  // Add here
}

// 2. Add to verbalizer
class KotlinModifierVerbalizer : ModifierVerbalizer {
    override fun verbalizeModifier(modifier: SymbolModifier): String {
        return when (modifier.kind) {
            ModifierKind.MY_CUSTOM_MODIFIER -> "custom modifier"
            // ...
        }
    }
}

// 3. Add extraction logic to your ModifierExtractor
class CustomExtractor : ModifierExtractor<*> {
    override fun extractModifiers(node: T): List<SymbolModifier> {
        if (node has myCustomMarker) {
            return listOf(SymbolModifier(ModifierKind.MY_CUSTOM_MODIFIER))
        }
        return emptyList()
    }
}
```

### Q: What's the performance impact?

**A:** Initial benchmarks show:
- **First run**: ~20% slower (PSI parsing overhead)
- **Cached runs**: ~50% faster (reusing parsed AST)
- **Memory**: ~15% increase for AST cache

### Q: How do I migrate existing feedback data?

**A:** Feedback is stored by symbol hash and remains valid:
```kotlin
// Feedback data doesn't need migration
// It's keyed by symbol content hash
val feedback = feedbackStore.getFeedback(symbol.hash)
```

### Q: Can I run both old and new systems in parallel?

**A:** Yes, for gradual migration:
```kotlin
class DualModeVerbalizer {
    private val old = OldPatternMatcher()
    private val new = StructuredVerbalizer()
    
    fun verbalize(symbol: Symbol): String {
        val oldResult = old.matchAndDescribe(symbol)
        val newResult = new.verbalize(symbol)
        
        return if (newResult.quality > oldResult.quality) {
            newResult
        } else {
            oldResult
        }
    }
}
```

---

## Rollback Plan

If issues arise, you can temporarily rollback:

1. **Via configuration** (no code change):
   ```yaml
   # config.yml
   verbalization:
     mode: legacy  # Use old PatternMatcher
   ```

2. **Via dependency**:
   ```kotlin
   // Temporarily use old module
   implementation("com.i2vision:verbalization-legacy:1.x")
   ```

---

## Next Steps

1. Review the [Architecture Types](../concepts/architecture-types.md) documentation
2. Explore the [Modifier Extractor](../concepts/modifier-extractors.md) guide
3. Check the [Changelog](../CHANGELOG.md) for v2.0 details
4. Report issues at https://github.com/i2vision/i2-vision/issues

---

*Last updated: 2026-05-14*
*For i2vision v2.0.0+*