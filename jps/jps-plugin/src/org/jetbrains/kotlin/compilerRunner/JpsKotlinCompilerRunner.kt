/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.compilerRunner

import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.jps.api.GlobalOptions
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.cli.common.CompilerSystemProperties
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.cli.common.messages.MessageCollectorUtil
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.IncrementalCompilation
import org.jetbrains.kotlin.config.additionalArgumentsAsList
import org.jetbrains.kotlin.daemon.client.CompileServiceSession
import org.jetbrains.kotlin.daemon.client.KotlinCompilerClient
import org.jetbrains.kotlin.daemon.common.*
import org.jetbrains.kotlin.jps.build.KotlinBuilder
import org.jetbrains.kotlin.jps.statistic.JpsBuilderMetricReporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class JpsKotlinCompilerRunner {
    private val log: KotlinLogger = JpsKotlinLogger(KotlinBuilder.LOG)

    private var compilerSettings: CompilerSettings? = null

    private inline fun withCompilerSettings(settings: CompilerSettings, fn: () -> Unit) {
        val old = compilerSettings
        try {
            compilerSettings = settings
            fn()
        } finally {
            compilerSettings = old
        }
    }

    companion object {
        @Volatile
        private var _jpsCompileServiceSession: CompileServiceSession? = null

        @TestOnly
        fun shutdownDaemon() {
            _jpsCompileServiceSession?.let {
                try {
                    it.compileService.shutdown()
                } catch (_: Throwable) {
                }
            }
            _jpsCompileServiceSession = null
        }

        fun releaseCompileServiceSession() {
            _jpsCompileServiceSession?.let {
                try {
                    it.compileService.releaseCompileSession(it.sessionId)
                } catch (_: Throwable) {
                }
            }
            _jpsCompileServiceSession = null
        }

        @Synchronized
        private fun getOrCreateDaemonConnection(newConnection: () -> CompileServiceSession?): CompileServiceSession? {
            // TODO: consider adding state "ping" to the daemon interface
            if (_jpsCompileServiceSession == null || _jpsCompileServiceSession!!.compileService.getDaemonOptions() !is CompileService.CallResult.Good<DaemonOptions>) {
                releaseCompileServiceSession()
                _jpsCompileServiceSession = newConnection()
            }

            return _jpsCompileServiceSession
        }

        const val FAIL_ON_FALLBACK_PROPERTY = "test.kotlin.jps.compiler.runner.fail.on.fallback"
    }

    fun classesFqNamesByFiles(
        environment: JpsCompilerEnvironment,
        files: Set<File>,
    ): Set<String> = withDaemonOrFallback(
        withDaemon = {
            doWithDaemon(environment) { sessionId, daemon ->
                daemon.classesFqNamesByFiles(
                    sessionId,
                    files.toSet() // convert to standard HashSet to avoid serialization issues
                )
            }
        },
        fallback = {
            CompilerRunnerUtil.invokeClassesFqNames(environment, files)
        }
    )

    fun runK2JvmCompiler(
        commonArguments: CommonCompilerArguments,
        k2jvmArguments: K2JVMCompilerArguments,
        compilerSettings: CompilerSettings,
        environment: JpsCompilerEnvironment,
        moduleFile: File,
        buildMetricReporter: JpsBuilderMetricReporter?,
    ) {
        val arguments = mergeBeans(commonArguments, XmlSerializerUtil.createCopy(k2jvmArguments))
        setupK2JvmArguments(moduleFile, arguments)
        withCompilerSettings(compilerSettings) {
            try {
                compileWithDaemonOrFallback(arguments, environment, buildMetricReporter)
            } catch (e: Throwable) {
                MessageCollectorUtil.reportException(environment.messageCollector, e)
                reportInternalCompilerError(environment.messageCollector)
            }
        }
    }

    private fun compileWithDaemonOrFallback(
        compilerArgs: CommonCompilerArguments,
        environment: JpsCompilerEnvironment,
        buildMetricReporter: JpsBuilderMetricReporter?,
    ) {
        log.debug("Using kotlin-home = " + environment.kotlinPaths.homePath)

        withDaemonOrFallback(
            withDaemon = { compileWithDaemon(compilerArgs, environment, buildMetricReporter) },
            fallback = { fallbackCompileStrategy(compilerArgs, environment) }
        )
    }

    private fun compileWithDaemon(
        compilerArgs: CommonCompilerArguments,
        environment: JpsCompilerEnvironment,
        buildMetricReporter: JpsBuilderMetricReporter?,
    ): Int? {
        val targetPlatform = CompileService.TargetPlatform.JVM
        val compilerMode = CompilerMode.JPS_COMPILER
        val verbose = compilerArgs.verbose
        val options = CompilationOptions(
            compilerMode,
            targetPlatform,
            reportCategories(verbose),
            reportSeverity(verbose),
            requestedCompilationResults = emptyArray()
        )
        val compilationResult = JpsCompilationResult()

        return doWithDaemon(environment) { sessionId, daemon ->
            environment.withProgressReporter { progress ->
                progress.compilationStarted()
                daemon.compile(
                    sessionId,
                    withAdditionalCompilerArgs(compilerArgs),
                    options,
                    JpsCompilerServicesFacadeImpl(environment),
                    compilationResult
                )
            }.also {
                buildMetricReporter?.addCompilerMetrics(compilationResult)
            }
        }
    }

    private fun <T> withDaemonOrFallback(withDaemon: () -> T?, fallback: () -> T): T =
        if (isDaemonEnabled()) {
            withDaemon() ?: fallback()
        } else {
            fallback()
        }

    private fun <T> doWithDaemon(
        environment: JpsCompilerEnvironment,
        fn: (sessionId: Int, daemon: CompileService) -> CompileService.CallResult<T>,
    ): T? {
        log.debug("Try to connect to daemon")
        val connection = getDaemonConnection(environment)
        if (connection == null) {
            log.info("Could not connect to daemon")
            return null
        }

        val (daemon, sessionId) = connection
        val res = fn(sessionId, daemon)
        // TODO: consider implementing connection retry, instead of fallback here
        return res.takeUnless { it is CompileService.CallResult.Dying }?.get()
    }

    private fun withAdditionalCompilerArgs(compilerArgs: CommonCompilerArguments): Array<String> {
        val allArgs = ArgumentUtils.convertArgumentsToStringList(compilerArgs) +
                (compilerSettings?.additionalArgumentsAsList ?: emptyList())
        return filterDuplicatedCompilerPluginOptions(allArgs).toTypedArray()
    }

    /*
    * This function filters duplicates of -P plugin:<pluginId>:<optionName>=<value> in the compiler arguments
    * */
    private fun filterDuplicatedCompilerPluginOptions(compilerArguments: List<String>): List<String> {
        val filteredArguments = mutableListOf<String>()
        val knownPluginOptions = mutableSetOf<String>()
        val argumentsIterator = compilerArguments.iterator()

        while (argumentsIterator.hasNext()) {
            val argument = argumentsIterator.next()
            // try to find pair -P plugin:<pluginId>:<optionName>=<value>
            if (argument == "-P" && argumentsIterator.hasNext()) {
                val pluginOption = argumentsIterator.next() // expected plugin:<pluginId>:<optionName>=<value>
                val elementIsUnique = knownPluginOptions.add(pluginOption)
                if (elementIsUnique) {
                    filteredArguments.add(argument) // add -P
                    filteredArguments.add(pluginOption) // add the plugin option
                }
            } else {
                // skip filtering for all other arguments
                filteredArguments.add(argument)
            }
        }

        return filteredArguments
    }

    private fun reportCategories(verbose: Boolean): Array<Int> {
        val categories =
            if (!verbose) {
                arrayOf(ReportCategory.COMPILER_MESSAGE, ReportCategory.EXCEPTION)
            } else {
                ReportCategory.values()
            }

        return categories.map { it.code }.toTypedArray()
    }


    private fun reportSeverity(verbose: Boolean): Int =
        if (!verbose) {
            ReportSeverity.INFO.code
        } else {
            ReportSeverity.DEBUG.code
        }

    private fun fallbackCompileStrategy(
        compilerArgs: CommonCompilerArguments,
        environment: JpsCompilerEnvironment,
    ) {
        if ("true" == System.getProperty("kotlin.jps.tests") && "true" == System.getProperty(FAIL_ON_FALLBACK_PROPERTY)) {
            error("Cannot compile with Daemon, see logs bellow. Fallback strategy is disabled in tests")
        }

        // otherwise fallback to in-process
        log.info("Compile in-process")

        val stream = ByteArrayOutputStream()
        val out = PrintStream(stream)

        // the property should be set at least for parallel builds to avoid parallel building problems (racing between destroying and using environment)
        // unfortunately it cannot be currently set by default globally, because it breaks many tests
        // since there is no reliable way so far to detect running under tests, switching it on only for parallel builds
        if (System.getProperty(GlobalOptions.COMPILE_PARALLEL_OPTION, "false").toBoolean())
            CompilerSystemProperties.KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY.value = "true"

        val rc = environment.withProgressReporter { progress ->
            progress.compilationStarted()
            CompilerRunnerUtil.invokeExecMethod(KotlinCompilerClass.JVM, withAdditionalCompilerArgs(compilerArgs), environment, out)
        }

        // exec() returns an ExitCode object, class of which is loaded with a different class loader,
        // so we take it's contents through reflection
        val exitCode = ExitCode.valueOf(getReturnCodeFromObject(rc))
        processCompilerOutput(environment.messageCollector, environment.outputItemsCollector, stream, exitCode)
    }

    private fun setupK2JvmArguments(moduleFile: File, settings: K2JVMCompilerArguments) {
        with(settings) {
            buildFile = moduleFile.absolutePath
            destination = null
            noStdlib = true
            noReflect = true
            noJdk = true
        }
    }

    private fun getReturnCodeFromObject(rc: Any?): String = when {
        rc == null -> ExitCode.INTERNAL_ERROR.toString()
        ExitCode::class.java.name == rc::class.java.name -> rc.toString()
        else -> throw IllegalStateException("Unexpected return: " + rc)
    }

    private fun getDaemonConnection(environment: JpsCompilerEnvironment): CompileServiceSession? =
        getOrCreateDaemonConnection {
            environment.progressReporter.progress("connecting to daemon")
            val libPath = CompilerRunnerUtil.getLibPath(environment.kotlinPaths, environment.messageCollector)
            val compilerPath = File(libPath, "kotlin-compiler.jar")
            val daemonJarPath = File(libPath, "kotlin-daemon.jar")
            val toolsJarPath = CompilerRunnerUtil.jdkToolsJar
            val compilerId = CompilerId.makeCompilerId(listOfNotNull(compilerPath, toolsJarPath, daemonJarPath))
            val daemonOptions = configureDaemonOptions()
            val additionalJvmParams = mutableListOf<String>()

            @Suppress("DEPRECATION")
            IncrementalCompilation.toJvmArgs(additionalJvmParams)

            val clientFlagFile = KotlinCompilerClient.getOrCreateClientFlagFile(daemonOptions)
            val sessionFlagFile = makeAutodeletingFlagFile("compiler-jps-session-", File(daemonOptions.runFilesPathOrDefault))

            environment.withProgressReporter { progress ->
                progress.progress("connecting to daemon")
                KotlinCompilerRunnerUtils.newDaemonConnection(
                    compilerId,
                    clientFlagFile,
                    sessionFlagFile,
                    environment.messageCollector,
                    log.isDebugEnabled,
                    daemonOptions,
                    additionalJvmParams.toTypedArray()
                )
            }
        }

    @TestOnly
    fun filterDuplicatedCompilerPluginOptionsForTest(compilerArguments: List<String>): List<String> =
        filterDuplicatedCompilerPluginOptions(compilerArguments)
}
