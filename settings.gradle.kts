plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "koog-coding-agent"

// Core modules
include("core:orchestrator")
include("core:session")
include("core:coroutines")

// Model modules
include("models:wrappers")

// Switching modules
include("switching:analyzer")
include("switching:monitor")
include("switching:decision")

// Context modules
include("context:hierarchy")
include("context:provider")
include("context:navigation")

// Server modules
include("server")
include("server:routes")
include("server:streaming")

// Kotlin analysis
include("kotlin:analysis")

// Database
include("database")
include("database:repositories")

// Learning modules
include("learning:tracker")
include("learning:optimizer")
include("learning:patterns")

// Pipeline modules
include("pipeline:parser")
include("pipeline:assembler")
include("pipeline:execution")
include("pipeline:processor")

// UI modules
include("ui:chat")
include("ui:config")

// Security
include("security")
include("security:access")

// Launcher (contains main)
include("launcher")
