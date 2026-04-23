package com.i2vision.mcp.tools.contract

import com.i2vision.vslfc.contracts.ContractValidator
import com.i2vision.mcp.server.ToolResult
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Contract Tools - MCP tools for contract validation.
 */
class ContractTools(
    private val projectRoot: String
) {

    private val log = LoggerFactory.getLogger(ContractTools::class.java)
    private val contractValidator = ContractValidator()

    /**
     * Validate contract - Validate a VSLFC contract.
     */
    suspend fun validateContract(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] validate_contract called with parameters: {}", parameters)

        val contractPath = parameters["contract"] as? String
            ?: return ToolResult.error("Missing required parameter: contract")

        val contractFile = File(projectRoot, contractPath)
        if (!contractFile.exists()) {
            return ToolResult.error("Contract file not found: $contractPath")
        }

        // TODO: Implement contract validation
        return ToolResult.success(
            mapOf(
                "contract" to contractPath,
                "valid" to true,
                "errors" to emptyList<String>()
            )
        )
    }

    /**
     * List contracts - List all contracts in the project.
     */
    suspend fun listContracts(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] list_contracts called with parameters: {}", parameters)

        val contractsDir = File(projectRoot, ".vision-ai/.contracts")
        if (!contractsDir.exists()) {
            return ToolResult.success(
                mapOf(
                    "contracts" to emptyList<String>()
                )
            )
        }

        val contracts = contractsDir.listFiles()
            ?.filter { it.extension == "yaml" || it.extension == "yml" }
            ?.map { it.name }
            ?: emptyList()

        return ToolResult.success(
            mapOf(
                "contracts" to contracts
            )
        )
    }
}
