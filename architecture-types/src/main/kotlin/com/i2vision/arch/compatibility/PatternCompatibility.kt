package com.i2vision.arch.compatibility

import com.i2vision.arch.signature.ArchitectureSignature
import com.i2vision.arch.signature.ModulePattern
import com.i2vision.arch.signature.DesignPattern
import com.i2vision.arch.signature.DeploymentPattern

/**
 * Pattern compatibility checker
 * Determines if patterns can coexist or if migration is needed
 */
class PatternCompatibility {
    
    /**
     * Check if two module patterns are compatible
     */
    fun areModulePatternsCompatible(pattern1: ModulePattern, pattern2: ModulePattern): Boolean {
        // Same patterns are always compatible
        if (pattern1 == pattern2) return true
        
        // Unknown patterns are compatible with everything
        if (pattern1 == ModulePattern.UNKNOWN || pattern2 == ModulePattern.UNKNOWN) return true
        
        // Specific compatibility rules
        val compatiblePairs = setOf(
            ModulePattern.LAYERED to ModulePattern.CLEAN_ARCHITECTURE,
            ModulePattern.CLEAN_ARCHITECTURE to ModulePattern.LAYERED,
            ModulePattern.MVC to ModulePattern.MVP,
            ModulePattern.MVP to ModulePattern.MVC,
            ModulePattern.MVC to ModulePattern.MVVM,
            ModulePattern.MVVM to ModulePattern.MVC
        )
        
        return (pattern1 to pattern2) in compatiblePairs || (pattern2 to pattern1) in compatiblePairs
    }
    
    /**
     * Check if two design patterns are compatible
     */
    fun areDesignPatternsCompatible(pattern1: DesignPattern, pattern2: DesignPattern): Boolean {
        // Same patterns are always compatible
        if (pattern1 == pattern2) return true
        
        // Unknown patterns are compatible with everything
        if (pattern1 == DesignPattern.UNKNOWN || pattern2 == DesignPattern.UNKNOWN) return true
        
        // Many design patterns can coexist
        val compatiblePairs = setOf(
            DesignPattern.STRATEGY to DesignPattern.FACTORY,
            DesignPattern.FACTORY to DesignPattern.STRATEGY,
            DesignPattern.PIPELINE to DesignPattern.STRATEGY,
            DesignPattern.STRATEGY to DesignPattern.PIPELINE,
            DesignPattern.EVENT_DRIVEN to DesignPattern.OBSERVER,
            DesignPattern.OBSERVER to DesignPattern.EVENT_DRIVEN
        )
        
        return (pattern1 to pattern2) in compatiblePairs || (pattern2 to pattern1) in compatiblePairs
    }
    
    /**
     * Check if two deployment patterns are compatible
     */
    fun areDeploymentPatternsCompatible(pattern1: DeploymentPattern, pattern2: DeploymentPattern): Boolean {
        // Same patterns are always compatible
        if (pattern1 == pattern2) return true
        
        // Unknown patterns are compatible with everything
        if (pattern1 == DeploymentPattern.UNKNOWN || pattern2 == DeploymentPattern.UNKNOWN) return true
        
        // Monolith can evolve to modular monolith
        val compatiblePairs = setOf(
            DeploymentPattern.MONOLITH to DeploymentPattern.MODULAR_MONOLITH,
            DeploymentPattern.MODULAR_MONOLITH to DeploymentPattern.MONOLITH,
            DeploymentPattern.MODULAR_MONOLITH to DeploymentPattern.AGGREGATOR,
            DeploymentPattern.AGGREGATOR to DeploymentPattern.MODULAR_MONOLITH
        )
        
        return (pattern1 to pattern2) in compatiblePairs || (pattern2 to pattern1) in compatiblePairs
    }
    
    /**
     * Check if two architecture signatures are compatible
     */
    fun areSignaturesCompatible(signature1: ArchitectureSignature, signature2: ArchitectureSignature): Boolean {
        // Check module patterns
        val modulePatterns1 = signature1.modulePatterns.values
        val modulePatterns2 = signature2.modulePatterns.values
        
        if (modulePatterns1.isNotEmpty() && modulePatterns2.isNotEmpty()) {
            val allCompatible = modulePatterns1.all { p1 ->
                modulePatterns2.all { p2 -> areModulePatternsCompatible(p1, p2) }
            }
            if (!allCompatible) return false
        }
        
        // Check design patterns (per-module)
        val allDesignPatterns1 = signature1.moduleDesignPatterns.values.flatten()
        val allDesignPatterns2 = signature2.moduleDesignPatterns.values.flatten()
        if (allDesignPatterns1.isNotEmpty() && allDesignPatterns2.isNotEmpty()) {
            val allCompatible = allDesignPatterns1.all { p1 ->
                allDesignPatterns2.all { p2 -> areDesignPatternsCompatible(p1, p2) }
            }
            if (!allCompatible) return false
        }
        
        // Check deployment patterns
        if (!areDeploymentPatternsCompatible(signature1.deploymentPattern, signature2.deploymentPattern)) {
            return false
        }
        
        return true
    }
    
    /**
     * Get compatibility issues between two signatures
     */
    fun getCompatibilityIssues(signature1: ArchitectureSignature, signature2: ArchitectureSignature): List<String> {
        val issues = mutableListOf<String>()
        
        // Check module patterns
        val modulePatterns1 = signature1.modulePatterns.values
        val modulePatterns2 = signature2.modulePatterns.values
        
        for (p1 in modulePatterns1) {
            for (p2 in modulePatterns2) {
                if (!areModulePatternsCompatible(p1, p2)) {
                    issues.add("Module pattern $p1 is not compatible with $p2")
                }
            }
        }
        
        // Check design patterns (per-module)
        val allDesignPatterns1 = signature1.moduleDesignPatterns.values.flatten()
        val allDesignPatterns2 = signature2.moduleDesignPatterns.values.flatten()
        
        for (p1 in allDesignPatterns1) {
            for (p2 in allDesignPatterns2) {
                if (!areDesignPatternsCompatible(p1, p2)) {
                    issues.add("Design pattern $p1 is not compatible with $p2")
                }
            }
        }
        
        // Check deployment patterns
        if (!areDeploymentPatternsCompatible(signature1.deploymentPattern, signature2.deploymentPattern)) {
            issues.add("Deployment pattern ${signature1.deploymentPattern} is not compatible with ${signature2.deploymentPattern}")
        }
        
        return issues
    }
}
