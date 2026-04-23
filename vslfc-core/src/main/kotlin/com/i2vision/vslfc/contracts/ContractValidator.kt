package com.i2vision.vslfc.contracts

import org.slf4j.LoggerFactory

/**
 * ContractValidator - Validates VSLFC discovery contracts for correctness and completeness.
 * 
 * Ensures contracts are well-formed, have required fields, and meet quality standards.
 */
class ContractValidator {
    private val log = LoggerFactory.getLogger(ContractValidator::class.java)

    /**
     * Validate a contract comprehensively.
     */
    fun validate(contract: DiscoveryContract): ContractValidationResult {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()

        // Validate metadata
        validateMetadata(contract.metadata, errors, warnings)

        // Validate layer expectations
        contract.layerExpectations?.let {
            validateLayerExpectations(it, errors, warnings)
        }

        // Validate discovery config
        contract.discoveryConfig?.let {
            validateDiscoveryConfig(it, errors, warnings)
        }

        // Validate quality gates
        contract.qualityGates?.let {
            validateQualityGates(it, errors, warnings)
        }

        val isValid =
            errors.none { it.severity == ValidationSeverity.ERROR || it.severity == ValidationSeverity.CRITICAL }

        log.info(
            "[VALIDATE] Contract '{}': {} errors, {} warnings, valid={}",
            contract.metadata.name, errors.size, warnings.size, isValid
        )

        return ContractValidationResult(
            contract = contract,
            isValid = isValid,
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Quick validation - only checks critical issues.
     */
    fun validateQuick(contract: DiscoveryContract): Boolean {
        val result = validate(contract)
        return result.isValid
    }

    /**
     * Validate metadata section.
     */
    private fun validateMetadata(
        metadata: ContractMetadata,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Name is required
        if (metadata.name.isBlank()) {
            errors.add(
                ValidationError(
                    field = "contract.name",
                    message = "Contract name is required",
                    severity = ValidationSeverity.CRITICAL
                )
            )
        } else if (!metadata.name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]*$"))) {
            warnings.add(
                ValidationWarning(
                    field = "contract.name",
                    message = "Contract name should use alphanumeric, hyphens, and underscores only",
                    suggestion = "Rename to: ${metadata.name.replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
                )
            )
        }

        // Version format
        if (!metadata.version.matches(Regex("^\\d+\\.\\d+(:?\\.\\d+)?$"))) {
            warnings.add(
                ValidationWarning(
                    field = "contract.version",
                    message = "Version should follow semantic versioning (e.g., 1.0, 1.2.3)"
                )
            )
        }

        // Description recommended
        if (metadata.description.isNullOrBlank()) {
            warnings.add(
                ValidationWarning(
                    field = "contract.description",
                    message = "Contract description is recommended for documentation"
                )
            )
        }

        // Target layers validation
        if (metadata.targetLayers.isEmpty()) {
            errors.add(
                ValidationError(
                    field = "contract.target_layers",
                    message = "At least one target layer must be specified",
                    severity = ValidationSeverity.ERROR
                )
            )
        } else {
            val validLayers = setOf("all", "vision", "structure", "logic", "flow", "code")
            val invalidLayers = metadata.targetLayers.filter { it !in validLayers }
            if (invalidLayers.isNotEmpty()) {
                warnings.add(
                    ValidationWarning(
                        field = "contract.target_layers",
                        message = "Unknown target layers: ${invalidLayers.joinToString()}",
                        suggestion = "Valid layers: ${validLayers.joinToString()}"
                    )
                )
            }
        }
    }

    /**
     * Validate layer expectations.
     */
    private fun validateLayerExpectations(
        expectations: LayerExpectations,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Vision expectations
        expectations.vision?.let { vision ->
            if (vision.requiredCapabilities.isEmpty() && vision.constraints.isEmpty()) {
                warnings.add(
                    ValidationWarning(
                        field = "layer_expectations.vision",
                        message = "Vision expectations defined but empty"
                    )
                )
            }
        }

        // Structure expectations
        expectations.structure?.let { structure ->
            structure.expectedComponents.forEachIndexed { index, component ->
                if (component.name.isBlank()) {
                    errors.add(
                        ValidationError(
                            field = "layer_expectations.structure.expected_components[$index].name",
                            message = "Component name is required"
                        )
                    )
                }
                if (component.filePatterns.isEmpty()) {
                    warnings.add(
                        ValidationWarning(
                            field = "layer_expectations.structure.expected_components[$index].file_patterns",
                            message = "No file patterns specified for component '${component.name}'"
                        )
                    )
                }
            }
        }

        // Logic expectations
        expectations.logic?.let { logic ->
            logic.expectedRules.forEachIndexed { index, rule ->
                if (rule.id.isBlank()) {
                    errors.add(
                        ValidationError(
                            field = "layer_expectations.logic.expected_rules[$index].id",
                            message = "Rule ID is required"
                        )
                    )
                }
                if (rule.description.isBlank()) {
                    warnings.add(
                        ValidationWarning(
                            field = "layer_expectations.logic.expected_rules[$index].description",
                            message = "Rule description is recommended for rule '${rule.id}'"
                        )
                    )
                }
            }
        }

        // Flow expectations
        expectations.flow?.let { flow ->
            flow.entryPoints.forEachIndexed { index, entryPoint ->
                if (entryPoint.pattern.isBlank()) {
                    errors.add(
                        ValidationError(
                            field = "layer_expectations.flow.entry_points[$index].pattern",
                            message = "Entry point pattern is required"
                        )
                    )
                }
            }
        }

        // Code expectations
        expectations.code?.let { code ->
            code.testCoverageMinimum?.let { coverage ->
                if (coverage < 0.0 || coverage > 100.0) {
                    errors.add(
                        ValidationError(
                            field = "layer_expectations.code.test_coverage_minimum",
                            message = "Test coverage must be between 0.0 and 100.0"
                        )
                    )
                }
            }
        }
    }

    /**
     * Validate discovery configuration.
     */
    private fun validateDiscoveryConfig(
        config: DiscoveryConfig,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Include/exclude pattern conflicts
        val includeSet = config.includePatterns.toSet()
        val excludeSet = config.excludePatterns.toSet()
        val conflicts = includeSet.intersect(excludeSet)

        if (conflicts.isNotEmpty()) {
            warnings.add(
                ValidationWarning(
                    field = "discovery_config",
                    message = "Include and exclude patterns overlap: ${conflicts.joinToString()}",
                    suggestion = "Remove conflicting patterns"
                )
            )
        }

        // Entry points validation
        config.entryPoints.forEachIndexed { index, entryPoint ->
            if (entryPoint.isBlank()) {
                errors.add(
                    ValidationError(
                        field = "discovery_config.entry_points[$index]",
                        message = "Entry point cannot be empty"
                    )
                )
            }
        }
    }

    /**
     * Validate quality gates.
     */
    private fun validateQualityGates(
        gates: QualityGates,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Coverage percentage
        if (gates.minCoveragePercentage < 0.0 || gates.minCoveragePercentage > 100.0) {
            errors.add(
                ValidationError(
                    field = "quality_gates.min_coverage_percentage",
                    message = "Coverage percentage must be between 0.0 and 100.0"
                )
            )
        } else if (gates.minCoveragePercentage > 95.0) {
            warnings.add(
                ValidationWarning(
                    field = "quality_gates.min_coverage_percentage",
                    message = "Coverage requirement > 95% may be difficult to achieve"
                )
            )
        }

        // Max orphaned rules
        if (gates.maxOrphanedRules < 0) {
            errors.add(
                ValidationError(
                    field = "quality_gates.max_orphaned_rules",
                    message = "Max orphaned rules cannot be negative"
                )
            )
        }

        // Complexity
        gates.maxComplexity?.let { complexity ->
            if (complexity < 1) {
                errors.add(
                    ValidationError(
                        field = "quality_gates.max_complexity",
                        message = "Max complexity must be at least 1"
                    )
                )
            } else if (complexity > 50) {
                warnings.add(
                    ValidationWarning(
                        field = "quality_gates.max_complexity",
                        message = "Complexity threshold > 50 is very permissive"
                    )
                )
            }
        }
    }

    /**
     * Validate a batch of contracts.
     */
    fun validateBatch(contracts: List<DiscoveryContract>): BatchValidationResult {
        val results = contracts.map { validate(it) }

        val valid = results.count { it.isValid }
        val invalid = results.size - valid
        val totalErrors = results.sumOf { it.errors.size }
        val totalWarnings = results.sumOf { it.warnings.size }

        return BatchValidationResult(
            total = contracts.size,
            valid = valid,
            invalid = invalid,
            totalErrors = totalErrors,
            totalWarnings = totalWarnings,
            results = results
        )
    }

    data class BatchValidationResult(
        val total: Int,
        val valid: Int,
        val invalid: Int,
        val totalErrors: Int,
        val totalWarnings: Int,
        val results: List<ContractValidationResult>
    )
}
