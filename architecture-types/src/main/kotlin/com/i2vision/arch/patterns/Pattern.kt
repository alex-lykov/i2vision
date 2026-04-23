package com.i2vision.arch.patterns

import com.i2vision.arch.signature.*

/**
 * Base pattern class for architecture patterns
 */
abstract class Pattern(
    val name: String,
    val modulePattern: ModulePattern,
    val designPattern: DesignPattern,
    val deploymentPattern: DeploymentPattern
)

/**
 * Hexagonal Architecture Pattern
 * Ports and adapters architecture with domain at the center
 */
class HexagonalPattern : Pattern(
    name = "Hexagonal",
    modulePattern = ModulePattern.HEXAGONAL,
    designPattern = DesignPattern.STRATEGY,
    deploymentPattern = DeploymentPattern.MODULAR_MONOLITH
)

/**
 * Layered Architecture Pattern
 * Traditional layered architecture with controller, service, repository layers
 */
class LayeredPattern : Pattern(
    name = "Layered",
    modulePattern = ModulePattern.LAYERED,
    designPattern = DesignPattern.FACTORY,
    deploymentPattern = DeploymentPattern.MONOLITH
)

/**
 * Agent Framework Pattern
 * Agent-based architecture with orchestrator and dispatcher
 */
class AgentPattern : Pattern(
    name = "Agent Framework",
    modulePattern = ModulePattern.AGENT_FRAMEWORK,
    designPattern = DesignPattern.AGENT,
    deploymentPattern = DeploymentPattern.MODULAR_MONOLITH
)

/**
 * Pipeline Pattern
 * Pipeline-based architecture with sequential processing stages
 */
class PipelinePattern : Pattern(
    name = "Pipeline",
    modulePattern = ModulePattern.LAYERED,
    designPattern = DesignPattern.PIPELINE,
    deploymentPattern = DeploymentPattern.MONOLITH
)

/**
 * Event-Driven Pattern
 * Event-driven architecture with publishers and subscribers
 */
class EventDrivenPattern : Pattern(
    name = "Event-Driven",
    modulePattern = ModulePattern.MICROSERVICES,
    designPattern = DesignPattern.EVENT_DRIVEN,
    deploymentPattern = DeploymentPattern.MICROSERVICES
)

/**
 * Pattern catalog for matching and lookup
 */
class PatternCatalog {
    private val patterns = listOf(
        HexagonalPattern(),
        LayeredPattern(),
        AgentPattern(),
        PipelinePattern(),
        EventDrivenPattern()
    )

    /**
     * Get all patterns
     */
    fun getAllPatterns(): List<Pattern> = patterns

    /**
     * Find pattern by name
     */
    fun findByName(name: String): Pattern? {
        return patterns.find { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Find patterns by module pattern
     */
    fun findByModulePattern(modulePattern: ModulePattern): List<Pattern> {
        return patterns.filter { it.modulePattern == modulePattern }
    }

    /**
     * Find patterns by design pattern
     */
    fun findByDesignPattern(designPattern: DesignPattern): List<Pattern> {
        return patterns.filter { it.designPattern == designPattern }
    }

    /**
     * Find patterns by deployment pattern
     */
    fun findByDeploymentPattern(deploymentPattern: DeploymentPattern): List<Pattern> {
        return patterns.filter { it.deploymentPattern == deploymentPattern }
    }
}
