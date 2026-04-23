/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.api

import com.i2vision.discover.api.models.DiscoveryIntent
import com.i2vision.discover.api.models.ModifiableParameterSet

/**
 * IntentResolver interface for breaking circular dependencies.
 * 
 * This interface defines the contract for intent resolution,
 * allowing discovery pipeline to depend on the interface rather than the concrete implementation.
 */
interface IntentResolver {

    /**
     * Resolve a high-level DiscoveryIntent to a concrete ModifiableParameterSet.
     * 
     * @param intent User's high-level intent for discovery
     * @return Resolved ModifiableParameterSet with discovery parameters
     */
    fun resolveToParameterSet(intent: DiscoveryIntent): ModifiableParameterSet

    /**
     * Validate that a DiscoveryIntent is valid.
     * 
     * @param intent DiscoveryIntent to validate
     * @return true if valid, false otherwise
     */
    fun isValid(intent: DiscoveryIntent): Boolean

    /**
     * Validate a DiscoveryIntent and return validation errors.
     * 
     * @param intent DiscoveryIntent to validate
     * @return List of validation error messages (empty if valid)
     */
    fun validate(intent: DiscoveryIntent): List<String>
}
