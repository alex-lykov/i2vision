package com.i2vision.discover.api

import com.i2vision.discover.api.models.PipelineResult
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.api.models.ContractHint
import com.i2vision.discover.api.models.DiscoveryIntent

/**
 * DiscoveryPipeline interface for breaking circular dependencies.
 * 
 * This interface defines the contract for discovery pipeline implementations,
 * allowing orchestrator to depend on the interface rather than the concrete implementation.
 */
interface DiscoveryPipeline {

    /**
     * Main entry point for depth-aware discovery.
     * Routes to appropriate strategy based on depth level.
     * 
     * @param depth Discovery depth level (BROWSE, STANDARD, DEEP)
     * @param clusterId Optional cluster/module to focus on
     * @param contracts Optional contract hints for contract-based discovery
     * @return PipelineResult with discovered artifacts
     */
    suspend fun discover(
        depth: DiscoveryDepth,
        clusterId: String? = null,
        contracts: List<ContractHint> = emptyList()
    ): PipelineResult

    /**
     * Intent-based discovery entry point.
     * Resolves intent to parameter set and executes discovery.
     * 
     * @param intent User's high-level intent for discovery
     * @param clusterId Optional cluster/module to focus on
     * @param contracts Optional contract hints for contract-based discovery
     * @return PipelineResult with discovered artifacts
     */
    suspend fun discover(
        intent: DiscoveryIntent,
        clusterId: String? = null,
        contracts: List<ContractHint> = emptyList()
    ): PipelineResult
}
