/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory

/**
 * CLI Parser - Parses command line arguments and routes to appropriate commands.
 */
class CliParser : CliktCommand(
    name = "i2vision",
    help = "i2vision - Intelligent code discovery and analysis tool"
) {

    private val log = LoggerFactory.getLogger(CliParser::class.java)

    private val verbose by option("-v", "--verbose", help = "Enable verbose output").flag()
    private val quiet by option("-q", "--quiet", help = "Suppress non-error output").flag()
    private val config by option("-c", "--config", help = "Path to configuration file")

    override fun run() {
        log.info("[CLI] Parser started")
        if (verbose) {
            echo("Verbose mode enabled")
        }
        if (quiet) {
            echo("Quiet mode enabled")
        }
        if (config != null) {
            echo("Using config: $config")
        }
    }
}
