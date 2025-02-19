/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.test

import com.google.gwt.dev.js.ThrowExceptionOnErrorReporter
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import junit.framework.TestCase
import org.jetbrains.kotlin.checkers.CompilerTestLanguageVersionSettings
import org.jetbrains.kotlin.checkers.parseLanguageVersionSettings
import org.jetbrains.kotlin.cli.common.arguments.K2JsArgumentConstants
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.common.output.writeAllTo
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.incremental.js.IncrementalDataProviderImpl
import org.jetbrains.kotlin.incremental.js.IncrementalResultsConsumerImpl
import org.jetbrains.kotlin.incremental.js.TranslationResultValue
import org.jetbrains.kotlin.ir.backend.js.ic.ICCache
import org.jetbrains.kotlin.js.JavaScript
import org.jetbrains.kotlin.js.backend.JsToStringGenerationVisitor
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.config.*
import org.jetbrains.kotlin.js.dce.DeadCodeElimination
import org.jetbrains.kotlin.js.dce.InputFile
import org.jetbrains.kotlin.js.dce.InputResource
import org.jetbrains.kotlin.js.engine.loadFiles
import org.jetbrains.kotlin.js.facade.K2JSTranslator
import org.jetbrains.kotlin.js.facade.MainCallParameters
import org.jetbrains.kotlin.js.facade.TranslationResult
import org.jetbrains.kotlin.js.facade.TranslationUnit
import org.jetbrains.kotlin.js.parser.parse
import org.jetbrains.kotlin.js.parser.sourcemaps.SourceMapError
import org.jetbrains.kotlin.js.parser.sourcemaps.SourceMapLocationRemapper
import org.jetbrains.kotlin.js.parser.sourcemaps.SourceMapParser
import org.jetbrains.kotlin.js.parser.sourcemaps.SourceMapSuccess
import org.jetbrains.kotlin.js.sourceMap.SourceFilePathResolver
import org.jetbrains.kotlin.js.sourceMap.SourceMap3Builder
import org.jetbrains.kotlin.js.sourceMap.SourceMapBuilderConsumer
import org.jetbrains.kotlin.js.test.utils.*
import org.jetbrains.kotlin.js.util.TextOutputImpl
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.metadata.DebugProtoBuf
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.serialization.js.JsModuleDescriptor
import org.jetbrains.kotlin.serialization.js.JsSerializerProtocol
import org.jetbrains.kotlin.serialization.js.KotlinJavascriptSerializationUtil
import org.jetbrains.kotlin.serialization.js.ModuleKind
import org.jetbrains.kotlin.test.*
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.JsMetadataVersion
import org.jetbrains.kotlin.utils.KotlinJavascriptMetadata
import org.jetbrains.kotlin.utils.KotlinJavascriptMetadataUtils
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.PrintStream
import java.lang.Boolean.getBoolean
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern

abstract class BasicBoxTest(
    protected val pathToTestDir: String,
    testGroupOutputDirPrefix: String,
    private val typedArraysEnabled: Boolean = true,
    private val generateSourceMap: Boolean = false,
    private val generateNodeJsRunner: Boolean = true,
    private val targetBackend: TargetBackend = TargetBackend.JS
) : KotlinTestWithEnvironment() {
    private val additionalCommonFileDirectories = mutableListOf<String>()

    val pathToRootOutputDir = System.getProperty("kotlin.js.test.root.out.dir") ?: error("'kotlin.js.test.root.out.dir' is not set")
    private val testGroupOutputDirForCompilation = File(pathToRootOutputDir + "out/" + testGroupOutputDirPrefix)
    private val testGroupOutputDirForMinification = File(pathToRootOutputDir + "out-min/" + testGroupOutputDirPrefix)
    private val testGroupOutputDirForPir = File(pathToRootOutputDir + "out-pir/" + testGroupOutputDirPrefix)

    protected open fun getOutputPrefixFile(testFilePath: String): File? = null
    protected open fun getOutputPostfixFile(testFilePath: String): File? = null

    protected open val runMinifierByDefault: Boolean = false
    protected open val skipMinification = getBoolean("kotlin.js.skipMinificationTest")
    protected open val overwriteReachableNodes = getBoolean(overwriteReachableNodesProperty)

    protected open val skipRegularMode: Boolean = false
    protected open val runIrDce: Boolean = false
    protected open val runIrPir: Boolean = false

    protected open val incrementalCompilationChecksEnabled = true

    protected open val testChecker get() = if (runTestInNashorn) NashornJsTestChecker else V8JsTestChecker

    protected val logger = KotlinJsTestLogger()


    fun doTest(filePath: String) {
        doTestWithIgnoringByFailFile(filePath)
    }

    fun doTestWithIgnoringByFailFile(filePath: String) {
        val failFile = File("$filePath.fail")
        try {
            doTest(filePath, "OK", MainCallParameters.noCall())
        } catch (e: Throwable) {
            if (failFile.exists()) {
                KotlinTestUtils.assertEqualsToFile(failFile, e.message ?: "")
            } else {
                throw e
            }
        }
    }

    open fun doTest(filePath: String, expectedResult: String, mainCallParameters: MainCallParameters) {
        val file = File(filePath)

        logger.logFile("Test file", file)

        val outputDir = getOutputDir(file)
        val dceOutputDir = getOutputDir(file, testGroupOutputDirForMinification)
        val pirOutputDir = getOutputDir(file, testGroupOutputDirForPir)
        var fileContent = KtTestUtil.doLoadFile(file)

        val needsFullIrRuntime = KJS_WITH_FULL_RUNTIME.matcher(fileContent).find() || WITH_RUNTIME.matcher(fileContent).find() || WITH_STDLIB.matcher(fileContent).find()


        val actualMainCallParameters = if (CALL_MAIN_PATTERN.matcher(fileContent).find())
            MainCallParameters.mainWithArguments(listOf("testArg"))
        else
            mainCallParameters

        val outputPrefixFile = getOutputPrefixFile(filePath)
        val outputPostfixFile = getOutputPostfixFile(filePath)

        val runPlainBoxFunction = RUN_PLAIN_BOX_FUNCTION.matcher(fileContent).find()
        val inferMainModule = INFER_MAIN_MODULE.matcher(fileContent).find()
        val expectActualLinker = EXPECT_ACTUAL_LINKER.matcher(fileContent).find()
        val errorPolicyMatcher = ERROR_POLICY_PATTERN.matcher(fileContent)
        val errorPolicy =
            if (errorPolicyMatcher.find()) ErrorTolerancePolicy.resolvePolicy(errorPolicyMatcher.group(1)) else ErrorTolerancePolicy.DEFAULT

        val skipDceDriven = SKIP_DCE_DRIVEN.matcher(fileContent).find()
        val splitPerModule = SPLIT_PER_MODULE.matcher(fileContent).find()
        val skipMangleVerification = SKIP_MANGLE_VERIFICATION.matcher(fileContent).find()

        val propertyLazyInitialization = PROPERTY_LAZY_INITIALIZATION.matcher(fileContent).find()
        val safeExternalBoolean = SAFE_EXTERNAL_BOOLEAN.matcher(fileContent).find()
        val safeExternalBooleanDiagnosticMatcher = SAFE_EXTERNAL_BOOLEAN_DIAGNOSTIC.matcher(fileContent)

        val safeExternalBooleanDiagnostic =
            if (safeExternalBooleanDiagnosticMatcher.find())
                RuntimeDiagnostic.resolve(safeExternalBooleanDiagnosticMatcher.group(1))
            else null

        TestFileFactoryImpl().use { testFactory ->
            val inputFiles = TestFiles.createTestFiles(
                file.name,
                fileContent,
                testFactory,
                true
            )
            val modules = inputFiles
                .map { it.module }.distinct()
                .map { it.name to it }.toMap()

            fun TestModule.allTransitiveDependencies(): Set<String> {
                return dependenciesSymbols.toSet() + dependenciesSymbols.flatMap { modules[it]!!.allTransitiveDependencies() }
            }

            val checkIC = modules.any { it.value.hasFilesToRecompile }

            val orderedModules = DFS.topologicalOrder(modules.values) { module -> module.dependenciesSymbols.mapNotNull { modules[it] } }

            val testPackage = if (runPlainBoxFunction) null else testFactory.testPackage

            val testFunction = TEST_FUNCTION

            val mainModuleName = when {
                inferMainModule -> orderedModules.last().name
                TEST_MODULE in modules -> TEST_MODULE
                else -> DEFAULT_MODULE
            }
            val testModuleName = if (runPlainBoxFunction) null else mainModuleName
            val mainModule = modules[mainModuleName] ?: error("No module with name \"$mainModuleName\"")
            val icCache = mutableMapOf<String, ICCache>()

            val generatedJsFiles = orderedModules.asReversed().mapNotNull { module ->
                val dependencies = module.dependenciesSymbols.map { modules[it]?.outputFileName(outputDir) + ".meta.js" }
                val allDependencies = module.allTransitiveDependencies().map { modules[it]?.outputFileName(outputDir) + ".meta.js" }
                val friends = module.friendsSymbols.map { modules[it]?.outputFileName(outputDir) + ".meta.js" }

                val outputFileName = module.outputFileName(outputDir) + ".js"
                val dceOutputFileName = module.outputFileName(dceOutputDir) + ".js"
                val pirOutputFileName = module.outputFileName(pirOutputDir) + ".js"
                val abiVersion = module.abiVersion
                val isMainModule = mainModuleName == module.name

                logger.logFile("Output JS", File(outputFileName))
                generateJavaScriptFile(
                    testFactory.tmpDir,
                    file.parent,
                    module,
                    outputFileName,
                    dceOutputFileName,
                    pirOutputFileName,
                    dependencies,
                    allDependencies,
                    friends,
                    modules.size > 1,
                    !SKIP_SOURCEMAP_REMAPPING.matcher(fileContent).find(),
                    outputPrefixFile,
                    outputPostfixFile,
                    actualMainCallParameters,
                    testPackage,
                    testFunction,
                    needsFullIrRuntime,
                    isMainModule,
                    expectActualLinker,
                    skipDceDriven,
                    splitPerModule,
                    errorPolicy,
                    propertyLazyInitialization,
                    safeExternalBoolean,
                    safeExternalBooleanDiagnostic,
                    skipMangleVerification,
                    abiVersion,
                    checkIC,
                    icCache
                )

                when {
                    module.name.endsWith(OLD_MODULE_SUFFIX) -> null
                    // JS_IR generates single js file for all modules (apart from runtime).
                    // TODO: Split and refactor test runner for JS_IR
                    targetBackend in listOf(TargetBackend.JS_IR, TargetBackend.JS_IR_ES6) && !isMainModule -> null
                    else -> Pair(outputFileName, module)
                }
            }

            val globalCommonFiles = JsTestUtils.getFilesInDirectoryByExtension(COMMON_FILES_DIR_PATH, JavaScript.EXTENSION)
            val localCommonFile = file.parent + "/" + COMMON_FILES_NAME + JavaScript.DOT_EXTENSION
            val localCommonFiles = if (File(localCommonFile).exists()) listOf(localCommonFile) else emptyList()

            val additionalCommonFiles = additionalCommonFileDirectories.flatMap { baseDir ->
                JsTestUtils.getFilesInDirectoryByExtension(baseDir + "/", JavaScript.EXTENSION)
            }

            val inputJsFilesBefore = mutableListOf<String>()
            val inputJsFilesAfter = mutableListOf<String>()

            fun copyInputJsFile(inputJsFile: TestFile): String {
                val sourceFile = File(inputJsFile.fileName)
                val targetFile = File(outputDir, inputJsFile.module.outputFileSimpleName() + "-js-" + sourceFile.name)
                FileUtil.copy(File(inputJsFile.fileName), targetFile)
                return targetFile.absolutePath
            }

            inputFiles.forEach {
                val name = it.fileName
                when {
                    name.endsWith("__after.js") ->
                        inputJsFilesAfter += copyInputJsFile(it)

                    name.endsWith(".js") ->
                        inputJsFilesBefore += copyInputJsFile(it)
                }
            }

            val additionalFiles = mutableListOf<String>()

            val moduleKindMatcher = MODULE_KIND_PATTERN.matcher(fileContent)
            val moduleKind = if (moduleKindMatcher.find()) ModuleKind.valueOf(moduleKindMatcher.group(1)) else ModuleKind.PLAIN

            val withModuleSystem = moduleKind != ModuleKind.PLAIN && !NO_MODULE_SYSTEM_PATTERN.matcher(fileContent).find()

            if (withModuleSystem) {
                additionalFiles += MODULE_EMULATION_FILE
            }

            val additionalJsFile = filePath.removeSuffix("." + KotlinFileType.EXTENSION) + JavaScript.DOT_EXTENSION
            if (File(additionalJsFile).exists()) {
                additionalFiles += additionalJsFile
            }

            val additionalMainFiles = mutableListOf<String>()
            val additionalMainJsFile = filePath.removeSuffix("." + KotlinFileType.EXTENSION) + "__main.js"
            if (File(additionalMainJsFile).exists()) {
                additionalMainFiles += additionalMainJsFile
            }

            val allJsFiles = additionalFiles + inputJsFilesBefore + generatedJsFiles.map { it.first } + globalCommonFiles + localCommonFiles +
                    additionalCommonFiles + additionalMainFiles + inputJsFilesAfter

            val dceAllJsFiles = additionalFiles + inputJsFilesBefore + generatedJsFiles.map {
                it.first.replace(
                    outputDir.absolutePath,
                    dceOutputDir.absolutePath
                )
            } + globalCommonFiles + localCommonFiles + additionalCommonFiles + additionalMainFiles + inputJsFilesAfter

            val pirAllJsFiles = additionalFiles + inputJsFilesBefore + generatedJsFiles.map {
                it.first.replace(
                    outputDir.absolutePath,
                    pirOutputDir.absolutePath
                )
            } +
                    globalCommonFiles + localCommonFiles + additionalCommonFiles + additionalMainFiles + inputJsFilesAfter


            val dontRunGeneratedCode =
                InTextDirectivesUtils.dontRunGeneratedCode(targetBackend, file)

            if (!dontRunGeneratedCode && generateNodeJsRunner && !SKIP_NODE_JS.matcher(fileContent).find()) {
                val nodeRunnerName = mainModule.outputFileName(outputDir) + ".node.js"
                val ignored = InTextDirectivesUtils.isIgnoredTarget(TargetBackend.JS, file)
                val nodeRunnerText = generateNodeRunner(allJsFiles, outputDir, mainModuleName, ignored, testPackage)
                FileUtil.writeToFile(File(nodeRunnerName), nodeRunnerText)
            }

            if (!dontRunGeneratedCode) {
                if (!skipRegularMode) {
                    runGeneratedCode(allJsFiles, testModuleName, testPackage, testFunction, expectedResult, withModuleSystem)

                    if (runIrDce) {
                        runGeneratedCode(dceAllJsFiles, testModuleName, testPackage, testFunction, expectedResult, withModuleSystem)
                    }
                }

                if (runIrPir && !skipDceDriven) {
                    runGeneratedCode(pirAllJsFiles, testModuleName, testPackage, testFunction, expectedResult, withModuleSystem)
                }
            }

            performAdditionalChecks(generatedJsFiles.map { it.first }, outputPrefixFile, outputPostfixFile)
            performAdditionalChecks(file, File(mainModule.outputFileName(outputDir)))
            val expectedReachableNodesMatcher = EXPECTED_REACHABLE_NODES.matcher(fileContent)
            val expectedReachableNodesFound = expectedReachableNodesMatcher.find()

            if (!skipMinification &&
                (runMinifierByDefault || expectedReachableNodesFound) &&
                !SKIP_MINIFICATION.matcher(fileContent).find()
            ) {
                val thresholdChecker: (Int) -> Unit = reachableNodesThresholdChecker(
                    expectedReachableNodesFound,
                    expectedReachableNodesMatcher,
                    fileContent,
                    file
                )

                val outputDirForMinification = getOutputDir(file, testGroupOutputDirForMinification)

                if (!dontRunGeneratedCode) {
                    minifyAndRun(
                        workDir = File(outputDirForMinification, file.nameWithoutExtension),
                        allJsFiles = allJsFiles,
                        generatedJsFiles = generatedJsFiles,
                        expectedResult = expectedResult,
                        testModuleName = testModuleName,
                        testPackage = testPackage,
                        testFunction = testFunction,
                        withModuleSystem = withModuleSystem,
                        minificationThresholdChecker = thresholdChecker
                    )
                }
            }
        }
    }

    private fun reachableNodesThresholdChecker(
        expectedReachableNodesFound: Boolean,
        expectedReachableNodesMatcher: Matcher,
        fileContent: String,
        file: File
    ) = { reachableNodesCount: Int ->
        val replacement = "// $EXPECTED_REACHABLE_NODES_DIRECTIVE: $reachableNodesCount"
        val enablingMessage = "To set expected reachable nodes use '$replacement'\n" +
                "To enable automatic overwriting reachable nodes use property '-Pfd.$overwriteReachableNodesProperty=true'"
        if (expectedReachableNodesFound) {
            val expectedReachableNodes = expectedReachableNodesMatcher.group(1).toInt()
            val minThreshold = expectedReachableNodes * 9 / 10
            val maxThreshold = expectedReachableNodes * 11 / 10
            if (reachableNodesCount < minThreshold || reachableNodesCount > maxThreshold) {

                val message = "Number of reachable nodes ($reachableNodesCount) does not fit into expected range " +
                        "[$minThreshold; $maxThreshold]"
                val additionalMessage: String =
                    if (overwriteReachableNodes) {
                        val newText = fileContent.substring(0, expectedReachableNodesMatcher.start()) +
                                replacement +
                                fileContent.substring(expectedReachableNodesMatcher.end())
                        file.writeText(newText)
                        ""
                    } else {
                        "\n$enablingMessage"
                    }

                fail("$message$additionalMessage")
            }
        } else {
            val baseMessage = "The number of expected reachable nodes was not set. Actual reachable nodes: $reachableNodesCount."

            if (overwriteReachableNodes) {
                file.writeText("$replacement\n$fileContent")
                fail(baseMessage)
            } else {
                println("$baseMessage\n$enablingMessage")
            }
        }
    }

    protected open fun runGeneratedCode(
        jsFiles: List<String>,
        testModuleName: String?,
        testPackage: String?,
        testFunction: String,
        expectedResult: String,
        withModuleSystem: Boolean
    ) {
        testChecker.check(jsFiles, testModuleName, testPackage, testFunction, expectedResult, withModuleSystem)
    }

    protected open fun performAdditionalChecks(generatedJsFiles: List<String>, outputPrefixFile: File?, outputPostfixFile: File?) {}
    protected open fun performAdditionalChecks(inputFile: File, outputFile: File) {}

    private fun generateNodeRunner(
        files: Collection<String>,
        dir: File,
        moduleName: String,
        ignored: Boolean,
        testPackage: String?
    ): String {
        val filesToLoad = files.map { FileUtil.getRelativePath(dir, File(it))!!.replace(File.separatorChar, '/') }.map { "\"$it\"" }
        val fqn = testPackage?.let { ".$it" } ?: ""
        val loadAndRun = "load([${filesToLoad.joinToString(",")}], '$moduleName')$fqn.box()"

        val sb = StringBuilder()
        sb.append("module.exports = function(load) {\n")
        if (ignored) {
            sb.append("  try {\n")
            sb.append("    var result = $loadAndRun;\n")
            sb.append("    if (result != 'OK') return 'OK';")
            sb.append("    return 'fail: expected test failure';\n")
            sb.append("  }\n")
            sb.append("  catch (e) {\n")
            sb.append("    return 'OK';\n")
            sb.append("}\n")
        } else {
            sb.append("  return $loadAndRun;\n")
        }
        sb.append("};\n")

        return sb.toString()
    }

    protected fun getOutputDir(file: File, testGroupOutputDir: File = testGroupOutputDirForCompilation): File {
        val stopFile = File(pathToTestDir)
        return generateSequence(file.parentFile) { it.parentFile }
            .takeWhile { it != stopFile }
            .map { it.name }
            .toList().asReversed()
            .fold(testGroupOutputDir, ::File)
    }

    private fun TestModule.outputFileSimpleName(): String {
        val outputFileSuffix = if (this.name == TEST_MODULE) "" else "-$name"
        return getTestName(true) + outputFileSuffix
    }

    private fun TestModule.outputFileName(directory: File) = directory.absolutePath + "/" + outputFileSimpleName() + "_v5"

    private fun generateJavaScriptFile(
        tmpDir: File,
        directory: String,
        module: TestModule,
        outputFileName: String,
        dceOutputFileName: String,
        pirOutputFileName: String,
        dependencies: List<String>,
        allDependencies: List<String>,
        friends: List<String>,
        multiModule: Boolean,
        remap: Boolean,
        outputPrefixFile: File?,
        outputPostfixFile: File?,
        mainCallParameters: MainCallParameters,
        testPackage: String?,
        testFunction: String,
        needsFullIrRuntime: Boolean,
        isMainModule: Boolean,
        expectActualLinker: Boolean,
        skipDceDriven: Boolean,
        splitPerModule: Boolean,
        errorIgnorancePolicy: ErrorTolerancePolicy,
        propertyLazyInitialization: Boolean,
        safeExternalBoolean: Boolean,
        safeExternalBooleanDiagnostic: RuntimeDiagnostic?,
        skipMangleVerification: Boolean,
        abiVersion: KotlinAbiVersion,
        checkIC: Boolean,
        icCache: MutableMap<String, ICCache>
    ) {
        val kotlinFiles = module.files.filter { it.fileName.endsWith(".kt") }
        val testFiles = kotlinFiles.map { it.fileName }
        val globalCommonFiles = JsTestUtils.getFilesInDirectoryByExtension(COMMON_FILES_DIR_PATH, KotlinFileType.EXTENSION)
        val localCommonFile = directory + "/" + COMMON_FILES_NAME + "." + KotlinFileType.EXTENSION
        val localCommonFiles = if (File(localCommonFile).exists()) listOf(localCommonFile) else emptyList()
        // TODO probably it's no longer needed.
        val additionalCommonFiles = additionalCommonFileDirectories.flatMap { baseDir ->
            JsTestUtils.getFilesInDirectoryByExtension(baseDir + "/", KotlinFileType.EXTENSION)
        }
        val additionalFiles = globalCommonFiles + localCommonFiles + additionalCommonFiles
        val allSourceFiles = (testFiles + additionalFiles).map(::File)
        val psiFiles = createPsiFiles(allSourceFiles.sortedBy { it.canonicalPath }.map { it.canonicalPath })

        val sourceDirs = (testFiles + additionalFiles).map { File(it).parent }.distinct()
        val config = createConfig(
            sourceDirs,
            module,
            dependencies,
            allDependencies,
            friends,
            multiModule,
            tmpDir,
            incrementalData = null,
            expectActualLinker = expectActualLinker,
            errorIgnorancePolicy
        )
        val outputFile = File(outputFileName)
        val dceOutputFile = File(dceOutputFileName)
        val pirOutputFile = File(pirOutputFileName)

        val incrementalData = IncrementalData()
        translateFiles(
            psiFiles.map(TranslationUnit::SourceFile),
            outputFile,
            dceOutputFile,
            pirOutputFile,
            config,
            outputPrefixFile,
            outputPostfixFile,
            mainCallParameters,
            incrementalData,
            remap,
            testPackage,
            testFunction,
            needsFullIrRuntime,
            isMainModule,
            skipDceDriven,
            splitPerModule,
            propertyLazyInitialization,
            safeExternalBoolean,
            safeExternalBooleanDiagnostic,
            skipMangleVerification,
            abiVersion,
            checkIC,
            recompile = false,
            icCache
        )

        if (incrementalCompilationChecksEnabled && module.hasFilesToRecompile) {
            checkIncrementalCompilation(
                sourceDirs,
                module,
                kotlinFiles,
                dependencies,
                allDependencies,
                friends,
                multiModule,
                tmpDir,
                remap,
                outputFile,
                outputPrefixFile,
                outputPostfixFile,
                mainCallParameters,
                incrementalData,
                testPackage,
                testFunction,
                needsFullIrRuntime,
                expectActualLinker,
                icCache
            )
        }
    }

    protected open fun checkIncrementalCompilation(
        sourceDirs: List<String>,
        module: TestModule,
        kotlinFiles: List<TestFile>,
        dependencies: List<String>,
        allDependencies: List<String>,
        friends: List<String>,
        multiModule: Boolean,
        tmpDir: File,
        remap: Boolean,
        outputFile: File,
        outputPrefixFile: File?,
        outputPostfixFile: File?,
        mainCallParameters: MainCallParameters,
        incrementalData: IncrementalData,
        testPackage: String?,
        testFunction: String,
        needsFullIrRuntime: Boolean,
        expectActualLinker: Boolean,
        icCaches: MutableMap<String, ICCache>
    ) {
        val sourceToTranslationUnit = hashMapOf<File, TranslationUnit>()
        for (testFile in kotlinFiles) {
            if (testFile.recompile) {
                val sourceFile = File(testFile.fileName)
                incrementalData.translatedFiles.remove(sourceFile)
                sourceToTranslationUnit[sourceFile] = TranslationUnit.SourceFile(createPsiFile(testFile.fileName))
                incrementalData.packageMetadata.remove(testFile.packageName)
            }
        }
        for ((sourceFile, data) in incrementalData.translatedFiles) {
            sourceToTranslationUnit[sourceFile] = TranslationUnit.BinaryAst(data.binaryAst, data.inlineData)
        }
        val translationUnits = sourceToTranslationUnit.keys
            .sortedBy { it.canonicalPath }
            .map { sourceToTranslationUnit[it]!! }

        val recompiledConfig = createConfig(
            sourceDirs,
            module,
            dependencies,
            allDependencies,
            friends,
            multiModule,
            tmpDir,
            incrementalData,
            expectActualLinker,
            ErrorTolerancePolicy.DEFAULT
        )
        val recompiledOutputFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + "-recompiled.js")

        translateFiles(
            translationUnits,
            recompiledOutputFile,
            recompiledOutputFile,
            recompiledOutputFile,
            recompiledConfig,
            outputPrefixFile,
            outputPostfixFile,
            mainCallParameters,
            incrementalData,
            remap,
            testPackage,
            testFunction,
            needsFullIrRuntime,
            isMainModule = false,
            skipDceDriven = true,
            splitPerModule = false,
            propertyLazyInitialization = false,
            safeExternalBoolean = false,
            safeExternalBooleanDiagnostic = null,
            skipMangleVerification = false,
            abiVersion = KotlinAbiVersion.CURRENT,
            incrementalCompilation = true,
            recompile = true,
            mutableMapOf()
        )

        val originalOutput = FileUtil.loadFile(outputFile)
        val recompiledOutput = removeRecompiledSuffix(FileUtil.loadFile(recompiledOutputFile))
        assertEquals("Output file changed after recompilation", originalOutput, recompiledOutput)

        val originalSourceMap = FileUtil.loadFile(File(outputFile.parentFile, outputFile.name + ".map"))
        val recompiledSourceMap =
            removeRecompiledSuffix(FileUtil.loadFile(File(recompiledOutputFile.parentFile, recompiledOutputFile.name + ".map"))
        )
        if (originalSourceMap != recompiledSourceMap) {
            val originalSourceMapParse = SourceMapParser.parse(originalSourceMap)
            val recompiledSourceMapParse = SourceMapParser.parse(recompiledSourceMap)
            if (originalSourceMapParse is SourceMapSuccess && recompiledSourceMapParse is SourceMapSuccess) {
                assertEquals(
                    "Source map file changed after recompilation",
                    originalSourceMapParse.toDebugString(),
                    recompiledSourceMapParse.toDebugString()
                )
            }
            assertEquals("Source map file changed after recompilation", originalSourceMap, recompiledSourceMap)
        }

        if (multiModule) {
            val originalMetadata = FileUtil.loadFile(File(outputFile.parentFile, outputFile.nameWithoutExtension + ".meta.js"))
            val recompiledMetadata = removeRecompiledSuffix(
                FileUtil.loadFile(File(recompiledOutputFile.parentFile, recompiledOutputFile.nameWithoutExtension + ".meta.js"))
            )
            assertEquals(
                "Metadata file changed after recompilation",
                metadataAsString(originalMetadata),
                metadataAsString(recompiledMetadata)
            )
        }
    }

    private fun SourceMapSuccess.toDebugString(): String {
        val out = ByteArrayOutputStream()
        PrintStream(out).use { value.debug(it) }
        return String(out.toByteArray(), Charset.forName("UTF-8"))
    }

    private fun metadataAsString(metadataText: String): String {
        val metadata = mutableListOf<KotlinJavascriptMetadata>().apply {
            KotlinJavascriptMetadataUtils.parseMetadata(metadataText, this)
        }.single()
        val metadataParts = KotlinJavascriptSerializationUtil.readModuleAsProto(metadata.body, metadata.version).body
        return metadataParts.joinToString("-----\n") {
            val binary = it.toByteArray()
            DebugProtoBuf.PackageFragment.parseFrom(binary, JsSerializerProtocol.extensionRegistry).toString()
        }
    }

    private fun removeRecompiledSuffix(text: String): String = text.replace("-recompiled.js", ".js")

    class IncrementalData(
        var header: ByteArray? = null,
        val translatedFiles: MutableMap<File, TranslationResultValue> = hashMapOf(),
        val packageMetadata: MutableMap<String, ByteArray> = hashMapOf()
    )

    protected open fun translateFiles(
        units: List<TranslationUnit>,
        outputFile: File,
        dceOutputFile: File,
        pirOutputFile: File,
        config: JsConfig,
        outputPrefixFile: File?,
        outputPostfixFile: File?,
        mainCallParameters: MainCallParameters,
        incrementalData: IncrementalData,
        remap: Boolean,
        testPackage: String?,
        testFunction: String,
        needsFullIrRuntime: Boolean,
        isMainModule: Boolean,
        skipDceDriven: Boolean,
        splitPerModule: Boolean,
        propertyLazyInitialization: Boolean,
        safeExternalBoolean: Boolean,
        safeExternalBooleanDiagnostic: RuntimeDiagnostic?,
        skipMangleVerification: Boolean,
        abiVersion: KotlinAbiVersion,
        incrementalCompilation: Boolean,
        recompile: Boolean,
        icCache: MutableMap<String, ICCache>
    ) {
        val translator = K2JSTranslator(config, false)
        val translationResult = translator.translateUnits(ExceptionThrowingReporter, units, mainCallParameters)

        if (translationResult !is TranslationResult.Success) {
            val outputStream = ByteArrayOutputStream()
            val collector = PrintingMessageCollector(PrintStream(outputStream), MessageRenderer.PLAIN_FULL_PATHS, true)
            AnalyzerWithCompilerReport.reportDiagnostics(translationResult.diagnostics, collector)
            val messages = outputStream.toByteArray().toString(Charset.forName("UTF-8"))
            throw AssertionError("The following errors occurred compiling test:\n" + messages)
        }

        val outputFiles = translationResult.getOutputFiles(outputFile, outputPrefixFile, outputPostfixFile)
        val outputDir = outputFile.parentFile ?: error("Parent file for output file should not be null, outputFilePath: " + outputFile.path)
        outputFiles.writeAllTo(outputDir)

        if (config.moduleKind != ModuleKind.PLAIN) {
            val content = FileUtil.loadFile(outputFile, true)
            val wrappedContent = wrapWithModuleEmulationMarkers(content, moduleId = config.moduleId, moduleKind = config.moduleKind)
            FileUtil.writeToFile(outputFile, wrappedContent)
        }

        config.configuration[JSConfigurationKeys.INCREMENTAL_RESULTS_CONSUMER]?.let {
            val incrementalService = it as IncrementalResultsConsumerImpl

            for ((srcFile, data) in incrementalService.packageParts) {
                incrementalData.translatedFiles[srcFile] = data
            }

            incrementalData.packageMetadata += incrementalService.packageMetadata

            incrementalData.header = incrementalService.headerMetadata
        }

        processJsProgram(translationResult.program, units.filterIsInstance<TranslationUnit.SourceFile>().map { it.file })
        checkSourceMap(outputFile, translationResult.program, remap)
    }

    protected fun wrapWithModuleEmulationMarkers(
        content: String,
        moduleKind: ModuleKind,
        moduleId: String
    ): String {
        val escapedModuleId = StringUtil.escapeStringCharacters(moduleId)

        return when (moduleKind) {

            ModuleKind.COMMON_JS -> "$KOTLIN_TEST_INTERNAL.beginModule();\n" +
                    "$content\n" +
                    "$KOTLIN_TEST_INTERNAL.endModule(\"$escapedModuleId\");"

            ModuleKind.AMD, ModuleKind.UMD ->
                "if (typeof $KOTLIN_TEST_INTERNAL !== \"undefined\") { " +
                        "$KOTLIN_TEST_INTERNAL.setModuleId(\"$escapedModuleId\"); }\n" +
                        "$content\n"

            ModuleKind.PLAIN -> content
        }
    }

    private fun processJsProgram(program: JsProgram, psiFiles: List<KtFile>) {
        psiFiles.asSequence()
            .map { it.text }
            .forEach { DirectiveTestUtils.processDirectives(program, it) }
        program.verifyAst()
    }

    private fun checkSourceMap(outputFile: File, program: JsProgram, remap: Boolean) {
        val generatedProgram = JsProgram()
        generatedProgram.globalBlock.statements += program.globalBlock.statements.map { it.deepCopy() }
        generatedProgram.accept(object : RecursiveJsVisitor() {
            override fun visitObjectLiteral(x: JsObjectLiteral) {
                super.visitObjectLiteral(x)
                x.isMultiline = false
            }

            override fun visitVars(x: JsVars) {
                x.isMultiline = false
                super.visitVars(x)
            }
        })
        removeLocationFromBlocks(generatedProgram)
        generatedProgram.accept(AmbiguousAstSourcePropagation())

        val output = TextOutputImpl()
        val pathResolver = SourceFilePathResolver(mutableListOf(File(".")), null)
        val sourceMapBuilder = SourceMap3Builder(outputFile, output, "")
        generatedProgram.accept(
            JsToStringGenerationVisitor(
                output, SourceMapBuilderConsumer(File("."), sourceMapBuilder, pathResolver, false, false)
            )
        )
        val code = output.toString()
        val generatedSourceMap = sourceMapBuilder.build()

        val codeWithLines = generatedProgram.toStringWithLineNumbers()

        val parsedProgram = JsProgram()
        parsedProgram.globalBlock.statements += parse(code, ThrowExceptionOnErrorReporter, parsedProgram.scope, outputFile.path).orEmpty()
        removeLocationFromBlocks(parsedProgram)
        val sourceMapParseResult = SourceMapParser.parse(generatedSourceMap)
        val sourceMap = when (sourceMapParseResult) {
            is SourceMapSuccess -> sourceMapParseResult.value
            is SourceMapError -> error("Could not parse source map: ${sourceMapParseResult.message}")
        }

        if (remap) {
            val remapper = SourceMapLocationRemapper(sourceMap)
            remapper.remap(parsedProgram)

            val codeWithRemappedLines = parsedProgram.toStringWithLineNumbers()

            TestCase.assertEquals(codeWithLines, codeWithRemappedLines)
        }
    }

    private fun removeLocationFromBlocks(program: JsProgram) {
        program.globalBlock.accept(object : RecursiveJsVisitor() {
            override fun visitBlock(x: JsBlock) {
                super.visitBlock(x)
                x.source = null
            }
        })
    }

    private fun createPsiFile(fileName: String): KtFile {
        val psiManager = PsiManager.getInstance(project)
        val fileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)

        val file = fileSystem.findFileByPath(fileName) ?: error("File not found: ${fileName}")

        return psiManager.findFile(file) as KtFile
    }

    private fun createPsiFiles(fileNames: List<String>): List<KtFile> = fileNames.map(this::createPsiFile)

    protected fun createConfig(
        sourceDirs: List<String>,
        module: TestModule,
        dependencies: List<String>,
        allDependencies: List<String>,
        friends: List<String>,
        multiModule: Boolean,
        tmpDir: File,
        incrementalData: IncrementalData?,
        expectActualLinker: Boolean,
        errorIgnorancePolicy: ErrorTolerancePolicy,
    ): JsConfig {
        val configuration = environment.configuration.copy()

        configuration.put(CommonConfigurationKeys.DISABLE_INLINE, module.inliningDisabled)
        module.languageVersionSettings?.let { languageVersionSettings ->
            configuration.languageVersionSettings = languageVersionSettings
        }

        val libraries = when (targetBackend) {
            TargetBackend.JS_IR_ES6 -> dependencies
            TargetBackend.JS_IR -> dependencies
            TargetBackend.JS -> JsConfig.JS_STDLIB + JsConfig.JS_KOTLIN_TEST + dependencies
            else -> error("Unsupported target backend: $targetBackend")
        }

        configuration.put(JSConfigurationKeys.LIBRARIES, libraries)
        configuration.put(JSConfigurationKeys.TRANSITIVE_LIBRARIES, allDependencies)
        configuration.put(JSConfigurationKeys.FRIEND_PATHS, friends)

        configuration.put(CommonConfigurationKeys.MODULE_NAME, module.name.removeSuffix(OLD_MODULE_SUFFIX))
        configuration.put(JSConfigurationKeys.MODULE_KIND, module.moduleKind)
        configuration.put(JSConfigurationKeys.TARGET, EcmaVersion.v5)
        configuration.put(JSConfigurationKeys.ERROR_TOLERANCE_POLICY, errorIgnorancePolicy)

        if (errorIgnorancePolicy.allowErrors) {
            configuration.put(JSConfigurationKeys.DEVELOPER_MODE, true)
        }

        val hasFilesToRecompile = module.hasFilesToRecompile
        configuration.put(JSConfigurationKeys.META_INFO, multiModule)
        if (hasFilesToRecompile) {
            val header = incrementalData?.header
            if (header != null) {
                configuration.put(
                    JSConfigurationKeys.INCREMENTAL_DATA_PROVIDER,
                    IncrementalDataProviderImpl(
                        header,
                        incrementalData.translatedFiles,
                        JsMetadataVersion.INSTANCE.toArray(),
                        incrementalData.packageMetadata,
                        emptyMap()
                    )
                )
            }

            configuration.put(JSConfigurationKeys.INCREMENTAL_RESULTS_CONSUMER, IncrementalResultsConsumerImpl())
        }
        configuration.put(JSConfigurationKeys.SOURCE_MAP, true)
        configuration.put(JSConfigurationKeys.SOURCE_MAP_SOURCE_ROOTS, sourceDirs)
        configuration.put(JSConfigurationKeys.SOURCE_MAP_EMBED_SOURCES, module.sourceMapSourceEmbedding)

        configuration.put(JSConfigurationKeys.TYPED_ARRAYS_ENABLED, typedArraysEnabled)

        configuration.put(JSConfigurationKeys.GENERATE_REGION_COMMENTS, true)

        configuration.put(
            JSConfigurationKeys.FILE_PATHS_PREFIX_MAP,
            mapOf(
                tmpDir.absolutePath to "<TMP>",
                File(".").absolutePath.removeSuffix(".") to ""
            )
        )

        configuration.put(CommonConfigurationKeys.EXPECT_ACTUAL_LINKER, expectActualLinker)

        return JsConfig(project, configuration, CompilerEnvironment, METADATA_CACHE, (JsConfig.JS_STDLIB + JsConfig.JS_KOTLIN_TEST).toSet())
    }

    private fun minifyAndRun(
        workDir: File, allJsFiles: List<String>, generatedJsFiles: List<Pair<String, TestModule>>,
        expectedResult: String, testModuleName: String?, testPackage: String?, testFunction: String, withModuleSystem: Boolean,
        minificationThresholdChecker: (Int) -> Unit
    ) {
        val kotlinJsLib = DIST_DIR_JS_PATH + "kotlin.js"
        val kotlinTestJsLib = DIST_DIR_JS_PATH + "kotlin-test.js"
        val kotlinJsLibOutput = File(workDir, "kotlin.min.js").path
        val kotlinTestJsLibOutput = File(workDir, "kotlin-test.min.js").path

        val kotlinJsInputFile = InputFile(InputResource.file(kotlinJsLib), null, kotlinJsLibOutput, "kotlin")
        val kotlinTestJsInputFile = InputFile(InputResource.file(kotlinTestJsLib), null, kotlinTestJsLibOutput, "kotlin-test")

        val filesToMinify = generatedJsFiles.associate { (fileName, module) ->
            val inputFileName = File(fileName).nameWithoutExtension
            fileName to InputFile(InputResource.file(fileName), null, File(workDir, inputFileName + ".min.js").absolutePath, module.name)
        }

        val testFunctionFqn = testModuleName + (if (testPackage.isNullOrEmpty()) "" else ".$testPackage") + ".$testFunction"
        val additionalReachableNodes = setOf(
            testFunctionFqn, "kotlin.kotlin.io.BufferedOutput", "kotlin.kotlin.io.output.flush",
            "kotlin.kotlin.io.output.buffer", "kotlin-test.kotlin.test.overrideAsserter_wbnzx$",
            "kotlin-test.kotlin.test.DefaultAsserter"
        )
        val allFilesToMinify = filesToMinify.values + kotlinJsInputFile + kotlinTestJsInputFile
        val dceResult = DeadCodeElimination.run(allFilesToMinify, additionalReachableNodes, true) { _, _ -> }

        val reachableNodes = dceResult.reachableNodes
        minificationThresholdChecker(reachableNodes.count { it.reachable })

        val runList = mutableListOf<String>()
        runList += kotlinJsLibOutput
        runList += kotlinTestJsLibOutput
        runList += TEST_DATA_DIR_PATH + "nashorn-polyfills.js"
        runList += allJsFiles.map { filesToMinify[it]?.outputPath ?: it }

        val result = engineForMinifier.runAndRestoreContext {
            loadFiles(runList)
            overrideAsserter()
            eval(SETUP_KOTLIN_OUTPUT)
            runTestFunction(testModuleName, testPackage, testFunction, withModuleSystem)
        }
        TestCase.assertEquals(expectedResult, result)
    }

    private inner class TestFileFactoryImpl() : TestFiles.TestFileFactory<TestModule, TestFile>, Closeable {
        var testPackage: String? = null
        val tmpDir = KtTestUtil.tmpDir("js-tests")
        val defaultModule = TestModule(TEST_MODULE, emptyList(), emptyList(), KotlinAbiVersion.CURRENT)
        var languageVersionSettings: LanguageVersionSettings? = null

        override fun createFile(module: TestModule?, fileName: String, text: String, directives: Directives): TestFile? {
            val currentModule = module ?: defaultModule

            val ktFile = KtPsiFactory(project).createFile(text)
            val boxFunction = ktFile.declarations.find { it is KtNamedFunction && it.name == TEST_FUNCTION }
            if (boxFunction != null) {
                testPackage = ktFile.packageFqName.asString()
                if (testPackage?.isEmpty() == true) {
                    testPackage = null
                }
            }

            val moduleKindMatcher = MODULE_KIND_PATTERN.matcher(text)
            if (moduleKindMatcher.find()) {
                currentModule.moduleKind = ModuleKind.valueOf(moduleKindMatcher.group(1))
            }

            if (NO_INLINE_PATTERN.matcher(text).find()) {
                currentModule.inliningDisabled = true
            }

            val temporaryFile = File(tmpDir, "${currentModule.name}/$fileName")
            KtTestUtil.mkdirs(temporaryFile.parentFile)
            temporaryFile.writeText(text, Charsets.UTF_8)

            // TODO Deduplicate logic copied from CodegenTestCase.updateConfigurationByDirectivesInTestFiles
            fun LanguageVersion.toSettings() = CompilerTestLanguageVersionSettings(
                emptyMap(),
                ApiVersion.createByLanguageVersion(this),
                this,
                emptyMap()
            )

            fun LanguageVersionSettings.trySet() {
                val old = languageVersionSettings
                assert(old == null || old == this) { "Should not specify language version settings twice:\n$old\n$this" }
                languageVersionSettings = this
            }
            InTextDirectivesUtils.findStringWithPrefixes(text, "// LANGUAGE_VERSION:")?.let {
                LanguageVersion.fromVersionString(it)?.toSettings()?.trySet()
            }

            parseLanguageVersionSettings(directives)?.trySet()

            // Relies on the order of module creation
            // TODO is that ok?
            currentModule.languageVersionSettings = languageVersionSettings

            SOURCE_MAP_SOURCE_EMBEDDING.find(text)?.let { match ->
                currentModule.sourceMapSourceEmbedding = SourceMapSourceEmbedding.valueOf(match.groupValues[1])
            }

            return TestFile(
                temporaryFile.absolutePath,
                currentModule,
                recompile = RECOMPILE_PATTERN.matcher(text).find(),
                packageName = ktFile.packageFqName.asString()
            )
        }

        override fun createModule(name: String, dependencies: List<String>, friends: List<String>, abiVersions: List<Int>): TestModule {
            val abiVersion = if (abiVersions.isEmpty()) KotlinAbiVersion.CURRENT else {
                assert(abiVersions.size == 3)
                KotlinAbiVersion(abiVersions[0], abiVersions[1], abiVersions[2])
            }
            return TestModule(name, dependencies, friends, abiVersion)
        }

        override fun close() {
            FileUtil.delete(tmpDir)
        }
    }

    protected class TestFile(val fileName: String, val module: TestModule, val recompile: Boolean, val packageName: String) {
        init {
            module.files += this
        }
    }

    protected class TestModule(
        name: String,
        dependencies: List<String>,
        friends: List<String>,
        val abiVersion: KotlinAbiVersion
    ) : KotlinBaseTest.TestModule(name, dependencies, friends) {
        var moduleKind = ModuleKind.PLAIN
        var inliningDisabled = false
        val files = mutableListOf<TestFile>()
        var languageVersionSettings: LanguageVersionSettings? = null
        var sourceMapSourceEmbedding = SourceMapSourceEmbedding.NEVER

        val hasFilesToRecompile get() = files.any { it.recompile }
    }

    override fun createEnvironment() =
        KotlinCoreEnvironment.createForTests(testRootDisposable, CompilerConfiguration(), EnvironmentConfigFiles.JS_CONFIG_FILES)

    companion object {
        val METADATA_CACHE = (JsConfig.JS_STDLIB + JsConfig.JS_KOTLIN_TEST).flatMap { path ->
            KotlinJavascriptMetadataUtils.loadMetadata(path).map { metadata ->
                val parts = KotlinJavascriptSerializationUtil.readModuleAsProto(metadata.body, metadata.version)
                JsModuleDescriptor(metadata.moduleName, parts.kind, parts.importedModules, parts)
            }
        }

        const val TEST_DATA_DIR_PATH = "js/js.translator/testData/"
        const val DIST_DIR_JS_PATH = "dist/js/"

        private const val COMMON_FILES_NAME = "_common"
        private const val COMMON_FILES_DIR = "_commonFiles/"
        const val COMMON_FILES_DIR_PATH = TEST_DATA_DIR_PATH + COMMON_FILES_DIR

        private const val MODULE_EMULATION_FILE = TEST_DATA_DIR_PATH + "/moduleEmulation.js"

        private val MODULE_KIND_PATTERN = Pattern.compile("^// *MODULE_KIND: *(.+)$", Pattern.MULTILINE)
        private val NO_MODULE_SYSTEM_PATTERN = Pattern.compile("^// *NO_JS_MODULE_SYSTEM", Pattern.MULTILINE)

        // Infer main module using dependency graph
        private val INFER_MAIN_MODULE = Pattern.compile("^// *INFER_MAIN_MODULE", Pattern.MULTILINE)

        // Run top level box function
        private val RUN_PLAIN_BOX_FUNCTION = Pattern.compile("^// *RUN_PLAIN_BOX_FUNCTION", Pattern.MULTILINE)

        private val NO_INLINE_PATTERN = Pattern.compile("^// *NO_INLINE *$", Pattern.MULTILINE)
        private val SKIP_NODE_JS = Pattern.compile("^// *SKIP_NODE_JS *$", Pattern.MULTILINE)
        private val SKIP_MINIFICATION = Pattern.compile("^// *SKIP_MINIFICATION *$", Pattern.MULTILINE)
        private val SKIP_SOURCEMAP_REMAPPING = Pattern.compile("^// *SKIP_SOURCEMAP_REMAPPING *$", Pattern.MULTILINE)
        private val EXPECTED_REACHABLE_NODES_DIRECTIVE = "EXPECTED_REACHABLE_NODES"
        private val EXPECTED_REACHABLE_NODES = Pattern.compile("^// *$EXPECTED_REACHABLE_NODES_DIRECTIVE: *([0-9]+) *$", Pattern.MULTILINE)
        private val RECOMPILE_PATTERN = Pattern.compile("^// *RECOMPILE *$", Pattern.MULTILINE)
        private val SOURCE_MAP_SOURCE_EMBEDDING = Regex("^// *SOURCE_MAP_EMBED_SOURCES: ([A-Z]+)*\$", RegexOption.MULTILINE)
        private val CALL_MAIN_PATTERN = Pattern.compile("^// *CALL_MAIN *$", Pattern.MULTILINE)
        private val KJS_WITH_FULL_RUNTIME = Pattern.compile("^// *KJS_WITH_FULL_RUNTIME *\$", Pattern.MULTILINE)
        private val WITH_RUNTIME = Pattern.compile("^// *WITH_RUNTIME *\$", Pattern.MULTILINE)
        private val WITH_STDLIB = Pattern.compile("^// *WITH_STDLIB *\$", Pattern.MULTILINE)
        private val EXPECT_ACTUAL_LINKER = Pattern.compile("^// EXPECT_ACTUAL_LINKER *$", Pattern.MULTILINE)
        private val SKIP_DCE_DRIVEN = Pattern.compile("^// *SKIP_DCE_DRIVEN *$", Pattern.MULTILINE)
        private val SPLIT_PER_MODULE = Pattern.compile("^// *SPLIT_PER_MODULE *$", Pattern.MULTILINE)
        private val SKIP_MANGLE_VERIFICATION = Pattern.compile("^// *SKIP_MANGLE_VERIFICATION *$", Pattern.MULTILINE)

        private val ERROR_POLICY_PATTERN = Pattern.compile("^// *ERROR_POLICY: *(.+)$", Pattern.MULTILINE)

        private val PROPERTY_LAZY_INITIALIZATION = Pattern.compile("^// *PROPERTY_LAZY_INITIALIZATION *$", Pattern.MULTILINE)

        private val SAFE_EXTERNAL_BOOLEAN = Pattern.compile("^// *SAFE_EXTERNAL_BOOLEAN *$", Pattern.MULTILINE)
        private val SAFE_EXTERNAL_BOOLEAN_DIAGNOSTIC = Pattern.compile("^// *SAFE_EXTERNAL_BOOLEAN_DIAGNOSTIC: *(.+)$", Pattern.MULTILINE)

        @JvmStatic
        protected val runTestInNashorn = getBoolean("kotlin.js.useNashorn")

        const val TEST_MODULE = "JS_TESTS"
        const val DEFAULT_MODULE = "main"
        private const val TEST_FUNCTION = "box"
        private const val OLD_MODULE_SUFFIX = "-old"

        const val KOTLIN_TEST_INTERNAL = "\$kotlin_test_internal\$"
        private val engineForMinifier = createScriptEngine()

        const val overwriteReachableNodesProperty = "kotlin.js.overwriteReachableNodes"
    }
}

fun KotlinTestWithEnvironment.createPsiFile(fileName: String): KtFile {
    val psiManager = PsiManager.getInstance(project)
    val fileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)

    val file = fileSystem.findFileByPath(fileName) ?: error("File not found: $fileName")

    return psiManager.findFile(file) as KtFile
}

fun KotlinTestWithEnvironment.createPsiFiles(fileNames: List<String>): List<KtFile> = fileNames.map(this::createPsiFile)

class KotlinJsTestLogger {
    val verbose = getBoolean("kotlin.js.test.verbose")

    fun logFile(description: String, file: File) {
        if (verbose) {
            println("TEST_LOG: $description file://${file.absolutePath}")
        }
    }
}

fun RuntimeDiagnostic.Companion.resolve(
    value: String?,
): RuntimeDiagnostic? = when (value?.lowercase()) {
    K2JsArgumentConstants.RUNTIME_DIAGNOSTIC_LOG -> RuntimeDiagnostic.LOG
    K2JsArgumentConstants.RUNTIME_DIAGNOSTIC_EXCEPTION -> RuntimeDiagnostic.EXCEPTION
    null -> null
    else -> {
        null
    }
}