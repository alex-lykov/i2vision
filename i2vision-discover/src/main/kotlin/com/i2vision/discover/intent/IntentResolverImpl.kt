/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.intent

import com.i2vision.discover.api.IntentResolver
import com.i2vision.discover.api.models.*
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Implementation of IntentResolver interface.
 * 
 * This is a minimal implementation that satisfies the interface contract.
 * Full functionality will be added incrementally.
 */
class IntentResolverImpl : IntentResolver {

    private val log = LoggerFactory.getLogger(IntentResolverImpl::class.java)

    override fun resolveToParameterSet(intent: DiscoveryIntent): ModifiableParameterSet {
        log.info(
            "[INTENT] Resolving intent: goal={}, depth={}, quality={}",
            intent.goal, intent.depth, intent.quality
        )

        // TODO: Implement actual intent resolution logic
        // For now, return a minimal parameter set
        return ModifiableParameterSet(
            name = "default-${intent.goal.name.lowercase()}-${intent.depth.name.lowercase()}",
            description = "Resolved parameter set for ${intent.goal.name} discovery at ${intent.depth.name} depth",
            parameters = mapOf(
                "goal" to JsonPrimitive(intent.goal.name),
                "depth" to JsonPrimitive(intent.depth.name),
                "quality" to JsonPrimitive(intent.quality.name),
                "layerFocus" to JsonPrimitive(intent.layerFocus.joinToString(",")),
                "customParameters" to JsonPrimitive(intent.customParameters.toString())
            )
        )
    }

    override fun isValid(intent: DiscoveryIntent): Boolean {
        return validate(intent).isEmpty()
    }

    override fun validate(intent: DiscoveryIntent): List<String> {
        val errors = mutableListOf<String>()

        // Validate required fields
        if (intent.layerFocus.isEmpty() && intent.goal != DiscoveryGoal.UNDERSTAND) {
            errors.add("layerFocus is required for ${intent.goal.name} goal")
        }

        // Validate combinations
        if (intent.depth == IntentDepth.DEEP && intent.quality == DiscoveryQuality.FAST) {
            errors.add("DEEP depth cannot be combined with FAST quality")
        }

        return errors
    }
}
