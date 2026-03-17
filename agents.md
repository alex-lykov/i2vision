# AGENTS.md

## you work on
- i2vision Layered AI Structure with Parallel Layer Architecture: 
- The Five Layers: |
  Discovery flows bottom-up (existing code) or top-down (greenfield):

  Top-Down (Greenfield):     Vision -> Structure -> Logic -> Flow -> Code
  Bottom-Up (Existing):      Code -> Flow -> Logic -> Structure -> Vision
  Layer	Directory	Role	LLM Persona	Key Outputs
  Code [C]	src/main/	How it is built	Developer	Implementation, unit tests
  Flow [F]	src/flow/	How parts interact	Integrator	*.sd, contracts.yaml, tests.feature
  Logic [L]	src/logic/	What each part does	Designer	business-rules.yaml, entities.yaml, state-machines.yaml
  Structure [S]	src/structure/	How components fit	Architect	components.yaml, dependencies.yaml, data-flows.yaml
  Vision [V]	src/vision/	What we want	Analyst	requirements.yaml, glossary.yaml, constraints.yaml

## testing
- we test the project itself with the means of MCP service
- **MCP Cluster Test**: Run `mcp-cluster-test.ps1` to validate MCP setup:
  - Load project
  - Validate structure
  - Discover and sync
  - Validate cluster status

## git
- commit if requested only!

## Documentation Structure
- **Root Summary**: Maintain `docs/README.md` at project root containing:
  - Project purpose and high-level architecture overview
  - Quick start guide (setup, build, run)
  - Link to detailed docs in modules

- **Module Documentation**: Place significant docs in appropriate module/package directories:
  - `README.md` - Core business logic, expect/actual patterns, API contracts
  - `com/example/domain/README.md` - Domain model documentation
  - `com/example/domain//seqdiag/` - System architecture diagrams and decision records

- ❌ **NEVER CREATE:**
  - temporary, thinking and fix docs
  - Unit Tests if not asked  
  - Fix documentation (explaining what was wrong and how fixed)
  - Problem analysis documents
  - Debugging guides or troubleshooting instructions
  - Solution summaries or implementation walkthroughs
  - Status/progress tracking documents
  - "How to verify", "Before/After", "Your issue" type docs
  - Anything meant for a specific issue (temporary help)

- **Diagram Guidelines**:
  - Use https://sequencediagram.org format for flow diagrams
  - Store diagrams as `.sd` files alongside documentation
  - Include diagrams ONLY for production documentation:
    - Architecture overviews
    - Complex module interactions
    - Critical user flows (auth, payments, sync)
    - State machine transitions
  - DO NOT include temporary diagrams, debug visualizations, or WIP sketches
  - Reference diagrams in markdown: `[Flow Name](./docs/flow-name.sd)`

- **Example Diagram**:
  title User Authentication Flow
  participant "Mobile App" as App
  participant "Backend API" as API
  participant "Database" as DB

App->API: POST /auth/login\n{credentials}
activate API
API->DB: validate credentials
DB-->API: user record
alt valid credentials
API-->App: 200 OK\n{token}
else invalid
API-->App: 401 Unauthorized
end
deactivate API
