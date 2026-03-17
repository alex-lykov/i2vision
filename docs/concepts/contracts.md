# Documentation Contracts

## Overview

Documentation Contracts enable formal validation of documentation against source code. They define mappings between documentation sections and VSLFC artifact fields, ensuring documentation stays synchronized and validated.

---

## Core Concepts

### Contract Definition
A contract specifies:
- Which documentation file to import
- Which VSLFC layer it maps to
- How to parse documentation sections
- Confidence and validation rules

### Example Contract
```yaml
contract: vision-documentation
version: 2.0
layer: VISION

documentation:
  primary: "README.md"
  secondary:
    - "docs/architecture.md"

mappings:
  - doc_section: "## Requirements"
    layer_field: "requirements"
    parser: "markdown_list"
    confidence: 0.9
    
validation:
  - rule: "every_requirement_has_code_evidence"
    severity: "warning"
```

---

## Components

### DocContractYamlParser
Parses YAML contract files and validates structure.

**Supports:**
- All 5 VSLFC layers
- Contract versioning
- Batch loading

### DocSectionExtractor
Extracts documentation sections from markdown files.

**Parser Types:**
- `markdown_list` - Bullet point lists
- `table` - Markdown tables
- `free_text` - Prose paragraphs
- `yaml_embedded` - YAML sections in markdown

### CodeEvidenceFinder
Searches source code for evidence matching documentation claims.

**Confidence Multipliers:**
- 0 matches: 0.5x (probably not implemented)
- 1-2 matches: 1.0x (likely implemented)
- 3+ matches: 1.2x (definitely implemented)

### DocLayerImporter
Imports documentation into VSLFC artifacts using contracts.

**Process:**
1. Load contract
2. Extract sections
3. Find code evidence
4. Calculate confidence
5. Generate artifacts

### ContractValidationEngine
Validates imported documentation against code.

**5 Validation Rules:**
1. **Code Evidence** - Every claim needs code evidence
2. **Doc Reference** - Code should be documented
3. **Contradictions** - No conflicts between docs and code
4. **Confidence Decay** - Time-based confidence reduction
5. **Freshness** - Age-based quality assessment

---

## Workflow

### Step 1: Create Contracts
Define YAML contracts for each VSLFC layer:

```yaml
contract: structure-documentation
layer: STRUCTURE
documentation:
  primary: "docs/architecture.md"
mappings:
  - doc_section: "## Components"
    layer_field: "components"
    parser: "markdown_list"
```

### Step 2: Write Documentation
Document your architecture following the contract structure:

```markdown
## Components

### DiscoveryPipeline
**Location:** core/orchestrator/src/.../DiscoveryPipeline.kt
**Purpose:** Orchestrates bottom-up discovery
**Dependencies:** IndexProvider, LinkService
```

### Step 3: Run Import
Enable contracts in the pipeline:

```kotlin
val pipeline = DiscoveryPipeline(
    projectRoot = projectRoot,
    enableDocumentationContracts = true
)

val result = pipeline.discoverFromFiles(sourceFiles)
```

### Step 4: Check Results
Review imported artifacts and validation report:

```yaml
# .semantic-cache/structure/imported-structure.yaml
components:
  - title: "DiscoveryPipeline"
    confidence: 1.0  # High confidence
    evidence:
      - file: "...DiscoveryPipeline.kt"
        type: class_definition
```

---

## Validation Rules

### 1. Code Evidence Rule
Every documented item must have corresponding code evidence.

**Severity:** WARNING

**Triggers When:**
- Documented component not found in code
- Code evidence matches < 0.5 confidence threshold

### 2. Doc Reference Rule
Important code elements should be documented.

**Severity:** INFO

**Triggers When:**
- Code element lacks documentation

### 3. Contradiction Rule
Documentation and code should not contradict.

**Severity:** ERROR

**Triggers When:**
- Documented API differs from code
- Documented flow differs from actual flow

### 4. Confidence Decay Rule
Confidence decreases over time without updates.

**Formula:** confidence × (0.95 ^ months_since_update)

**Severity:** WARNING

### 5. Freshness Rule
Documentation older than threshold needs review.

**Thresholds:**
- 30 days: Warning
- 90 days: Yellow flag
- 180 days: Critical

---

## Confidence Scoring

### Base Scores
```
Perfect match (direct from code): 1.0
Good match (1-2 references): 0.9
Partial match (indirect evidence): 0.7
Weak match (1 reference): 0.5
No match: 0.0
```

### Final Confidence
```
Final = Base × Evidence Multiplier × Freshness Factor

Freshness Factor:
- < 30 days: 1.0
- 30-90 days: 0.9
- 90-180 days: 0.7
- > 180 days: 0.5
```

---

## Configuration

### Location
Contract definitions are stored in the project root or module-specific directories.

### Key Settings
- **Import mode:** automatic or manual
- **Validation mode:** strict, warning, or lenient
- **Freshness thresholds:** configurable per layer

---

## Quality Levels

### Green ✅
- Confidence ≥ 0.9
- All validations pass
- Recently updated (< 30 days)

### Yellow ⚠️
- Confidence 0.7-0.9
- Minor warnings
- Moderately updated (30-90 days)

### Red ❌
- Confidence < 0.7
- Validation errors
- Stale (> 90 days)

---

## Three-Way Synchronization

### Import Flow
User Docs → Contracts → VSLFC Artifacts

### Export Flow (Future)
VSLFC Artifacts → Templates → Generated Docs

### Promote Flow (Future)
Generated Docs → Update → User Docs

---

## Use Cases

### Case 1: Import Existing Documentation
1. Create contracts for your layers
2. Run import
3. Review confidence scores
4. Address low-confidence items

### Case 2: Validate Documentation Quality
1. Run contracts system
2. Get validation report
3. Fix contradictions
4. Update stale documentation

### Case 3: Keep Docs in Sync
1. Set up contracts for your project
2. Run on each commit
3. Get alerts for stale documentation
4. Update as needed

---

## Best Practices

1. **Start with STRUCTURE** - Easiest layer to document
2. **Be specific** - Use exact class/method names
3. **Use links** - Reference actual code locations
4. **Update regularly** - Refresh docs as code changes
5. **Check confidence** - Address low-confidence items

---

## Troubleshooting

### Low Confidence Scores
- Make documentation more specific
- Add code location references
- Include method/class names exactly as in code

### Missing Evidence
- Verify component exists in code
- Check code was compiled/indexed
- Ensure discovery ran successfully

### Stale Documentation Warnings
- Update timestamp in documentation
- Review for accuracy
- Refresh if implementation changed

