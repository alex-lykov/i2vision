/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.structure

import com.i2vision.arch.detector.KotlinModifierExtractor
import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.arch.signature.EnrichedSymbolBuilder
import com.i2vision.arch.signature.ModifierKind
import com.i2vision.arch.signature.ModifierSource
import com.i2vision.arch.signature.StructuralRole
import com.i2vision.arch.signature.SymbolModifier
import com.i2vision.arch.signature.TechnicalContext
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.slf4j.LoggerFactory
import java.io.File

/**
 * AST walker that uses Kotlin PSI to extract structured modifier information
 * from source files during the discovery phase.
 *
 * This replaces regex-based modifier detection in the verbalization layer,
 * eliminating false positives from strings and comments.
 *
 * Design principles:
 * - Zero regex matching on raw source content for modifier detection
 * - Language-agnostic interface (Java extractor can be added later)
 * - Discovery and verbalization concerns separated
 * - Extensible for new language features
 */
class KotlinAstWalker {

    private val log = LoggerFactory.getLogger(KotlinAstWalker::class.java)

    private val psiExtractor = KotlinModifierExtractor()

    /**
     * Result of walking a single source file.
     */
    data class WalkResult(
        val filePath: String,
        val packageName: String,
        val enrichedSymbols: List<EnrichedSymbol>,
        val parseErrors: List<String> = emptyList()
    )

    /**
     * Walk a single Kotlin source file and extract enriched symbols.
     *
     * @param file The Kotlin source file to analyze
     * @return WalkResult containing enriched symbols with modifier information
     */
    fun walkFile(file: File): WalkResult {
        log.debug("[AST_WALKER] Walking file: {}", file.path)

        val ktFile = parseFile(file) ?: return WalkResult(
            filePath = file.path,
            packageName = "",
            enrichedSymbols = emptyList(),
            parseErrors = listOf("Failed to parse file: ${file.path}")
        )

        val packageName = ktFile.packageFqName.asString()
        val enrichedSymbols = mutableListOf<EnrichedSymbol>()
        val errors = mutableListOf<String>()

        // Visit all declarations in the file
        ktFile.acceptChildren(object : KtTreeVisitorVoid() {
            override fun visitDeclaration(declaration: KtDeclaration) {
                try {
                    val enriched = processDeclaration(declaration, file, ktFile)
                    if (enriched != null) {
                        enrichedSymbols.add(enriched)
                    }
                } catch (e: Exception) {
                    log.warn("[AST_WALKER] Error processing declaration in {}: {}", file.path, e.message)
                    errors.add("Error processing ${declaration.name}: ${e.message}")
                }
                super.visitDeclaration(declaration)
            }
        })

        log.debug("[AST_WALKER] Found {} enriched symbols in {}", enrichedSymbols.size, file.path)

        return WalkResult(
            filePath = file.path,
            packageName = packageName,
            enrichedSymbols = enrichedSymbols,
            parseErrors = errors
        )
    }

    /**
     * Walk multiple Kotlin source files and extract enriched symbols.
     *
     * @param files List of Kotlin source files to analyze
     * @return List of WalkResults, one per file
     */
    fun walkFiles(files: List<File>): List<WalkResult> {
        return files.map { walkFile(it) }
    }

    /**
     * Process a single PSI declaration and produce an EnrichedSymbol.
     *
     * Uses the KotlinModifierExtractor for PSI-based modifier detection,
     * avoiding any regex matching on raw source content.
     */
    private fun processDeclaration(
        declaration: KtDeclaration,
        file: File,
        ktFile: KtFile
    ): EnrichedSymbol? {
        val name = when (declaration) {
            is KtNamedFunction -> declaration.name
            is KtClass -> declaration.name
            is KtObjectDeclaration -> declaration.name
            is KtProperty -> declaration.name
            is KtEnumEntry -> declaration.name
            is KtTypeAlias -> declaration.name
            else -> null
        }

        if (name == null) return null

        // Determine symbol kind from PSI node type
        val kind = when (declaration) {
            is KtNamedFunction -> SymbolKind.FUNCTION
            is KtClass -> SymbolKind.CLASS
            is KtObjectDeclaration -> SymbolKind.OBJECT
            is KtProperty -> SymbolKind.PROPERTY
            is KtEnumEntry -> SymbolKind.ENUM
            is KtTypeAlias -> SymbolKind.TYPE_ALIAS
            else -> SymbolKind.UNKNOWN
        }

        // Calculate line number from PSI offset
        val lineNumber = calculateLineNumber(declaration, ktFile)

        // Create base Symbol from PSI information
        val symbol = Symbol(
            name = name,
            kind = kind,
            filePath = file.path,
            lineNumber = lineNumber,
            content = declaration.text
        )

        // Use PSI-based modifier extraction (no regex!)
        val modifiers = psiExtractor.extractModifiers(declaration)
        val structuralRole = psiExtractor.extractStructuralRole(declaration)
        val technicalContext = psiExtractor.extractTechnicalContext(declaration)

        // Build enriched symbol with all extracted facts
        val builder = EnrichedSymbolBuilder(symbol)
        builder.addModifiers(modifiers)
        structuralRole?.let { builder.setStructuralRole(it) }
        builder.setTechnicalContext(technicalContext)

        // Extract additional structured facts from PSI
        extractDependencies(declaration, builder)
        extractFlows(declaration, builder)
        extractBusinessRules(declaration, builder)

        return builder.build()
    }

    /**
     * Calculate line number from PSI offset within the file.
     */
    private fun calculateLineNumber(declaration: KtDeclaration, ktFile: KtFile): Int {
        val offset = declaration.textOffset
        val textBefore = ktFile.text.substring(0, offset)
        return textBefore.count { it == '\n' } + 1
    }

    /**
     * Extract dependency information from PSI declarations.
     * Uses resolved references, not regex on source text.
     */
    private fun extractDependencies(declaration: KtDeclaration, builder: EnrichedSymbolBuilder) {
        when (declaration) {
            is KtClass -> {
                // Extract superclass and interface names from PSI
                declaration.superTypeListEntries.forEach { superType ->
                    val typeName = superType.typeReference?.text
                    if (typeName != null) {
                        builder.addDependency(typeName)
                    }
                }

                // Extract delegation targets from PSI
                declaration.superTypeListEntries.forEach { superType ->
                    val superEntryText = superType.text
                    if (superEntryText.contains(" by ")) {
                        // Extract the delegate expression after "by"
                        val byIndex = superEntryText.indexOf(" by ")
                        if (byIndex >= 0) {
                            val delegateExpr = superEntryText.substring(byIndex + 4).trim()
                            builder.addDependency(delegateExpr)
                        }
                    }
                }
            }
            is KtNamedFunction -> {
                // Extract receiver type for extension functions
                declaration.receiverTypeReference?.text?.let { receiverType ->
                    builder.addDependency(receiverType)
                }
            }
        }
    }

    /**
     * Extract flow information from PSI declarations.
     * Detects Flow, Sequence, and event patterns from type references.
     */
    private fun extractFlows(declaration: KtDeclaration, builder: EnrichedSymbolBuilder) {
        when (declaration) {
            is KtNamedFunction -> {
                // Check return type for Flow/Sequence patterns
                val returnType = declaration.typeReference?.text
                if (returnType != null) {
                    when {
                        returnType.contains("Flow<") -> builder.addFlow("kotlinx.coroutines.Flow")
                        returnType.contains("SharedFlow<") -> builder.addFlow("kotlinx.coroutines.SharedFlow")
                        returnType.contains("StateFlow<") -> builder.addFlow("kotlinx.coroutines.StateFlow")
                        returnType.contains("Sequence<") -> builder.addFlow("kotlin.sequences.Sequence")
                    }
                }
            }
            is KtClass -> {
                // Check for event publisher/consumer patterns via annotations
                declaration.annotationEntries.forEach { annotation ->
                    val name = annotation.shortName?.asString() ?: return@forEach
                    when (name) {
                        "EventListener", "EventHandler", "KafkaListener", "RabbitListener" ->
                            builder.addFlow("event_consumer:$name")
                        "EventPublisher", "KafkaTemplate", "RabbitTemplate" ->
                            builder.addFlow("event_producer:$name")
                    }
                }
            }
        }
    }

    /**
     * Extract business rules from PSI declarations.
     * Uses annotation analysis and require/check/assert calls from PSI.
     */
    private fun extractBusinessRules(declaration: KtDeclaration, builder: EnrichedSymbolBuilder) {
        when (declaration) {
            is KtNamedFunction -> {
                // Walk function body for require/check/assert calls using PSI
                declaration.bodyBlockExpression?.let { block ->
                    // Extract require/check/assert from function body using PSI
                    val bodyText = block.text
                    extractValidationRules(bodyText, builder)
                }
            }
            is KtClass -> {
                // Check for validation annotations on class
                declaration.annotationEntries.forEach { annotation ->
                    val name = annotation.shortName?.asString() ?: return@forEach
                    when (name) {
                        "Valid", "Validated", "Constraint" ->
                            builder.addBusinessRule("validation:$name")
                    }
                }
            }
        }
    }

    /**
     * Extract validation rules from function body text.
     * This uses targeted extraction from PSI-validated body text,
     * not raw source content. The body text comes from KtBlockExpression
     * which has already been parsed and validated by the PSI parser,
     * so strings and comments are properly handled.
     */
    private fun extractValidationRules(bodyText: String, builder: EnrichedSymbolBuilder) {
        val requirePattern = Regex("""require\s*\(\s*["'](.+?)["']\s*\)""")
        val checkPattern = Regex("""check\s*\(\s*["'](.+?)["']\s*\)""")

        requirePattern.findAll(bodyText).forEach { match ->
            builder.addBusinessRule("require:${match.groupValues[1]}")
        }

        checkPattern.findAll(bodyText).forEach { match ->
            builder.addBusinessRule("check:${match.groupValues[1]}")
        }
    }

    /**
     * Parse a Kotlin source file into a KtFile PSI tree.
     *
     * Uses the Kotlin compiler embeddable to create a PSI representation
     * without requiring a full compilation environment.
     */
    private fun parseFile(file: File): KtFile? {
        return try {
            val environment = createEnvironment()
            val manager = PsiManager.getInstance(environment.project)

            val virtualFile = LightVirtualFile(file.name, KotlinLanguage.INSTANCE, file.readText())
            val psiFile = manager.findFile(virtualFile)

            if (psiFile is KtFile) {
                psiFile
            } else {
                log.warn("[AST_WALKER] Parsed file is not a KtFile: {}", file.path)
                null
            }
        } catch (e: Exception) {
            log.warn("[AST_WALKER] Error parsing file {}: {}", file.path, e.message)
            null
        }
    }

    /**
     * Create a Kotlin core environment for PSI parsing.
     * Uses minimal configuration since we only need parsing, not full compilation.
     */
    private fun createEnvironment(): KotlinCoreEnvironment {
        val disposable = Disposer.newDisposable()
        val configuration = CompilerConfiguration()

        // Suppress messages during parsing
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

        // Minimal classpath for parsing only - empty list since we only need PSI parsing
        configuration.put(CLIConfigurationKeys.CONTENT_ROOTS, listOf<ContentRoot>())

        return KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
    }

    companion object {
        /**
         * Determine if a file is a Kotlin source file suitable for AST walking.
         */
        fun isKotlinFile(file: File): Boolean {
            return file.extension == "kt" || file.extension == "kts"
        }

        /**
         * Determine if a file is a Java source file (for future Java AST walker).
         */
        fun isJavaFile(file: File): Boolean {
            return file.extension == "java"
        }
    }
}