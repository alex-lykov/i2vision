/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.i2vision.storage.impl.RolloutManager
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Init Command - Initializes VSLFC directory structure in a project.
 * 
 * Usage: i2vision init <project-path> [options]
 */
class InitCommand : CliktCommand(
    name = "init",
    help = "Initialize VSLFC directory structure in a project"
) {

    private val log = LoggerFactory.getLogger(InitCommand::class.java)

    private val projectPath by option("-p", "--path", help = "Path to project directory (default: current directory)")
    private val force by option("-f", "--force", help = "Force re-initialization even if already rolled out").flag()
    private val validate by option(
        "-v",
        "--validate",
        help = "Validate existing structure instead of initializing"
    ).flag()

    override fun run() {
        val path = projectPath ?: "."
        log.info("[CLI] Init command for project: $path")

        val projectFile = File(path)
        if (!projectFile.exists()) {
            echo("Error: Project path does not exist: $path", err = true)
            throw IllegalArgumentException("Project path does not exist: $path")
        }

        val rolloutManager = RolloutManager()

        if (validate) {
            // Validate existing structure
            echo("Validating VSLFC structure in: $path")
            echo("")

            val validation = runBlocking {
                rolloutManager.validate(projectFile)
            }

            echo("Validation Results:")
            echo("  Valid: ${validation.valid}")
            echo("  Version: ${validation.version ?: "unknown"}")

            if (validation.missing.isNotEmpty()) {
                echo("")
                echo("Missing items:")
                validation.missing.forEach { echo("  - $it") }
            }

            if (validation.invalid.isNotEmpty()) {
                echo("")
                echo("Invalid items:")
                validation.invalid.forEach { echo("  - $it") }
            }

            if (validation.valid) {
                echo("")
                echo("✅ VSLFC structure is valid")
            } else {
                echo("")
                echo("❌ VSLFC structure has issues")
            }
        } else {
            // Initialize structure
            val needsRollout = rolloutManager.needsRollout(projectFile)

            if (!force && !needsRollout) {
                echo("VSLFC structure already initialized in: $path")
                echo("")
                echo("Use --force to re-initialize, or --validate to check validity")
                return
            }

            echo("Initializing VSLFC structure in: $path")
            if (force) {
                echo("(forcing re-initialization)")
            }
            echo("")

            val result = runBlocking {
                rolloutManager.initialize(projectFile)
            }

            if (result.success) {
                echo("✅ VSLFC structure initialized successfully")
                echo("")
                echo("Created items (${result.created.size}):")
                result.created.take(20).forEach { echo("  - $it") }
                if (result.created.size > 20) {
                    echo("  ... and ${result.created.size - 20} more")
                }

                if (result.skipped.isNotEmpty()) {
                    echo("")
                    echo("Skipped items (${result.skipped.size}):")
                    result.skipped.take(10).forEach { echo("  - $it") }
                    if (result.skipped.size > 10) {
                        echo("  ... and ${result.skipped.size - 10} more")
                    }
                }

                echo("")
                echo("Version: ${result.version}")
            } else {
                echo("❌ VSLFC structure initialization failed")
                echo("")
                echo("Errors:")
                result.errors.forEach { echo("  - $it", err = true) }
                throw RuntimeException("Rollout failed")
            }
        }
    }
}
