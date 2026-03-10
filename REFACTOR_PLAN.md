# Module Structure Refactoring Plan

## Problem
Current structure has excessive nesting with many single-class modules:
- `switching:analyzer`, `switching:monitor`, `switching:decision` → 3 modules, ~3 classes
- `pipeline:parser`, `pipeline:assembler`, `pipeline:execution`, `pipeline:processor` → 4 modules, ~4 classes  
- `context:hierarchy`, `context:provider`, `context:navigation` → 3 modules, ~3 classes
- `learning:tracker`, `learning:optimizer`, `learning:patterns` → 3 modules, ~3 classes

This creates:
- Unnecessary build complexity
- Harder dependency management
- Slower builds
- Over-engineering for current codebase size

## Proposed Flat Structure

### Keep Package Nesting, Flatten Gradle Modules

Use nested **packages** for organization, but consolidate into fewer **Gradle modules**:

```
core/                    → core (single module)
  ├── orchestrator/
  ├── session/
  ├── coroutines/
  └── config/

switching/               → switching (single module)
  ├── analyzer/
  ├── monitor/
  └── decision/

context/                 → context (single module)
  ├── hierarchy/
  ├── provider/
  └── navigation/

pipeline/                 → pipeline (single module)
  ├── parser/
  ├── assembler/
  ├── execution/
  └── processor/

learning/                 → learning (single module)
  ├── tracker/
  ├── optimizer/
  └── patterns/

models/                   → models (keep split: wrappers + cloud)
  ├── wrappers/
  └── cloud/

server/                   → server (single module)
  ├── routes/
  └── streaming/

database/                 → database (single module)
  └── repositories/

ui/                       → ui (single module)
  ├── chat/
  └── config/

security/                 → security (single module)
  └── access/
```

## Benefits

1. **Faster builds** - Fewer modules to compile
2. **Simpler dependencies** - No need to track 20+ module relationships
3. **Easier refactoring** - Move classes between packages without module changes
4. **Same organization** - Package structure remains clear
5. **Better cohesion** - Related classes in same module

## Migration Steps

1. Consolidate `switching/*` into `switching/`
2. Consolidate `pipeline/*` into `pipeline/`
3. Consolidate `context/*` into `context/`
4. Consolidate `learning/*` into `learning/`
5. Consolidate `server/*` into `server/`
6. Consolidate `database/*` into `database/`
7. Consolidate `ui/*` into `ui/`
8. Consolidate `security/*` into `security/`
9. Keep `core/*` split (or consolidate if preferred)
10. Keep `models/*` split (wrappers vs cloud makes sense)

## Result

**Before:** ~25 modules
**After:** ~12 modules

Same functionality, much simpler structure.
