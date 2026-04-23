/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.i2vision.cli.commands.*
import org.slf4j.LoggerFactory

/**
 * Main entry point for i2vision CLI.
 * 
 * Provides command-line interface to i2vision discovery engine.
 */
class I2VisionCli : CliktCommand(
    name = "i2vision",
    help = "i2vision - Intelligent code discovery and analysis tool"
) {

    private val log = LoggerFactory.getLogger(I2VisionCli::class.java)

    override fun run() {
        log.info("[CLI] i2vision CLI started")
        echo("i2vision - Intelligent code discovery and analysis tool")
        echo("Version 1.0.0")
        echo("")
        echo("Available commands:")
        echo("  init      - Initialize VSLFC directory structure")
        echo("  discover  - Run discovery analysis on a project")
        echo("  contract  - Contract validation and management")
        echo("  preset    - Preset management")
        echo("  context   - Get instant context for files and directories")
        echo("")
        echo("Use 'i2vision <command> --help' for more information")
    }
}

/**
 * Main function - entry point for CLI application.
 */
fun main(args: Array<String>) {
    I2VisionCli().subcommands(InitCommand(), DiscoverCommand(), ContractCommand(), PresetCommand(), ContextCommand())
        .main(args)
}
