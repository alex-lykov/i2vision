package com.i2vision.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Preset Command - Preset management.
 * 
 * Usage: i2vision preset <action> [options]
 */
class PresetCommand : CliktCommand(
    name = "preset",
    help = "Preset management"
) {
    
    private val log = LoggerFactory.getLogger(PresetCommand::class.java)
    
    private val action by argument("action", help = "Action: list, apply, create")
    private val presetName by option("-n", "--name", help = "Preset name")
    private val presetDir by option("--preset-dir", help = "Directory containing presets")
    
    override fun run() {
        log.info("[CLI] Preset command started with action: $action")
        
        when (action.lowercase()) {
            "list" -> listPresets()
            "apply" -> applyPreset()
            "create" -> createPreset()
            else -> {
                echo("Error: Unknown action '$action'. Valid actions: list, apply, create", err = true)
                throw IllegalArgumentException("Unknown action: $action")
            }
        }
    }
    
    private fun listPresets() {
        val presetDirValue = presetDir ?: ".vision-ai/.presets"
        echo("Listing presets in: $presetDirValue")
        val dir = File(presetDirValue)
        if (!dir.exists()) {
            echo("No presets directory found: $presetDirValue")
            return
        }
        
        val presets = dir.listFiles() ?: emptyArray()
        
        if (presets.isEmpty()) {
            echo("No presets found")
        } else {
            echo("Found ${presets.size} presets:")
            presets.forEach { echo("  - ${it.name}") }
        }
    }
    
    private fun applyPreset() {
        if (presetName == null) {
            echo("Error: --name required for apply action", err = true)
            throw IllegalArgumentException("Preset name required")
        }
        echo("Applying preset: $presetName")
        echo("Preset application not yet implemented")
    }
    
    private fun createPreset() {
        if (presetName == null) {
            echo("Error: --name required for create action", err = true)
            throw IllegalArgumentException("Preset name required")
        }
        echo("Creating preset: $presetName")
        echo("Preset creation not yet implemented")
    }
}
