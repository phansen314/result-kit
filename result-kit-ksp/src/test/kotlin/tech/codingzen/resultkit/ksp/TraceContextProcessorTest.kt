package tech.codingzen.resultkit.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class TraceContextProcessorTest {

    // -- helpers --

    private data class CompileResult(
        val result: KotlinCompilation.Result,     // 2nd pass: has compiled classes
        val kspCompilation: KotlinCompilation,    // 1st pass: has generated sources on disk
    )

    private fun compile(vararg sources: SourceFile): CompileResult {
        // Pass 1: run KSP to generate sources
        val kspComp = KotlinCompilation().apply {
            this.sources = sources.toList()
            symbolProcessorProviders = listOf(TraceContextProcessorProvider())
            inheritClassPath = true
            messageOutputStream = System.out
        }
        kspComp.compile()

        // Pass 2: compile original + KSP-generated sources together so classes land in classesDir
        val generatedSources = kspComp.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { SourceFile.fromPath(it) }
            .toList()
        val comp2 = KotlinCompilation().apply {
            this.sources = sources.toList() + generatedSources
            inheritClassPath = true
            messageOutputStream = System.out
        }
        return CompileResult(comp2.compile(), kspComp)
    }

    private fun CompileResult.generatedSource(name: String): String {
        val workDir = kspCompilation.workingDir
        val file = workDir.walkTopDown().firstOrNull { it.name == "$name.kt" }
            ?: error("Generated file $name.kt not found under ${workDir.absolutePath}. Files: ${workDir.walkTopDown().filter { it.isFile }.map { it.name }.toList()}")
        return file.readText()
    }

    private val CompileResult.classLoader get() = result.classLoader
    private val CompileResult.exitCode get() = result.exitCode

    // -- compilation and source tests --

    @Test
    fun `compiles successfully for simple interface`() {
        val result = compile(simpleInterfaceSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
    }

    @Test
    fun `generates Traced class with correct name`() {
        val result = compile(simpleInterfaceSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("SimpleRepoTraced")
        assertTrue(src.contains("class SimpleRepoTraced"))
    }

    @Test
    fun `generated class implements the interface`() {
        val result = compile(simpleInterfaceSource)
        val src = result.generatedSource("SimpleRepoTraced")
        assertTrue(src.contains(": tech.codingzen.resultkit.ksp.test.SimpleRepo"))
    }

    @Test
    fun `generated class has delegate constructor parameter`() {
        val result = compile(simpleInterfaceSource)
        val src = result.generatedSource("SimpleRepoTraced")
        assertTrue(src.contains("private val delegate:"))
    }

    @Test
    fun `Res-returning method wrapped with context`() {
        val result = compile(simpleInterfaceSource)
        val src = result.generatedSource("SimpleRepoTraced")
        assertTrue(src.contains(".context("), "Expected .context( in: $src")
        assertTrue(src.contains("SourceLocation("), "Expected SourceLocation in: $src")
    }

    @Test
    fun `non-Res method delegated without context`() {
        val result = compile(simpleInterfaceSource)
        val src = result.generatedSource("SimpleRepoTraced")
        // count() is not Res-returning — should be plain delegation with no .context
        val countBlock = src.substringAfter("fun count()")
            .substringBefore("override")
            .substringBefore("}")
        assertTrue(!countBlock.contains(".context("),
            "count() should not have .context(), got: $countBlock")
    }

    @Test
    fun `auto-generated message includes class name, method name, and param names (no values)`() {
        val result = compile(simpleInterfaceSource)
        val src = result.generatedSource("SimpleRepoTraced")
        // findById(id: Int) should produce "SimpleRepo.findById(id)" — name only, no value by default
        assertTrue(src.contains("SimpleRepo.findById(id)"), "Got: $src")
        assertTrue(!src.contains("\${id}"), "Param value should not appear by default, got: $src")
    }

    @Test
    fun `custom suffix generates correct class name`() {
        val source = SourceFile.kotlin("CustomSuffix.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext(suffix = "Wrapped")
            interface MyService {
                fun doSomething(): Res<Int, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("MyServiceWrapped")
        assertTrue(src.contains("class MyServiceWrapped"))
    }

    @Test
    fun `TraceMessage override replaces auto-generated message`() {
        val source = SourceFile.kotlin("TracedMessage.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceMessage

            @TraceContext
            interface Svc {
                @TraceMessage("loading user {id}")
                fun find(id: Int): Res<Int, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("SvcTraced")
        assertTrue(src.contains("loading user \${id}"), "Got: $src")
        assertTrue(!src.contains("Svc.find("), "Auto-generated message should be replaced, got: $src")
    }

    @Test
    fun `TraceInclude opts a parameter value into the message`() {
        val source = SourceFile.kotlin("IncludeParam.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceInclude

            @TraceContext
            interface UserRepo {
                fun findById(@TraceInclude id: Int, version: Int): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("UserRepoTraced")
        assertTrue(src.contains("id=\${id}"), "TraceInclude param should emit value, got: $src")
        assertTrue(!src.contains("\${version}"), "Non-annotated param should not emit value, got: $src")
        assertTrue(src.contains("version"), "Non-annotated param name should still appear, got: $src")
    }

    @Test
    fun `method bounded type parameter bound is preserved in generated signature`() {
        val source = SourceFile.kotlin("BoundedMethod.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface Sorter {
                fun <T : Comparable<T>> sort(items: List<T>): Res<List<T>, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("SorterTraced")
        // KSP resolves bounds to fully-qualified names
        assertTrue(src.contains("<T : kotlin.Comparable<T>>"), "Expected bounded type param, got: $src")
    }

    @Test
    fun `interface type parameter is forwarded to class declaration delegate and supertype`() {
        val source = SourceFile.kotlin("GenericRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface GenericRepo<T> {
                fun findById(id: Int): Res<T, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("GenericRepoTraced")
        // Unbounded T: implicit kotlin.Any? is filtered out, so just <T>
        assertTrue(src.contains("class GenericRepoTraced<T>("), "Expected class with <T>, got: $src")
        assertTrue(src.contains("delegate: tech.codingzen.resultkit.ksp.test.GenericRepo<T>"), "Expected delegate<T>, got: $src")
        assertTrue(src.contains(") : tech.codingzen.resultkit.ksp.test.GenericRepo<T>"), "Expected supertype<T>, got: $src")
    }

    @Test
    fun `interface bounded type parameter is preserved on class and supertype`() {
        val source = SourceFile.kotlin("BoundedRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface BoundedRepo<T : Comparable<T>> {
                fun findMin(): Res<T, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("BoundedRepoTraced")
        // KSP resolves bounds to fully-qualified names
        assertTrue(src.contains("class BoundedRepoTraced<T : kotlin.Comparable<T>>("), "Expected bounded class param, got: $src")
        // Supertype and delegate use args only (no bounds)
        assertTrue(src.contains(": tech.codingzen.resultkit.ksp.test.BoundedRepo<T>"), "Expected supertype with arg only, got: $src")
    }

    @Test
    fun `zero-parameter Res-returning method generates empty param list in message`() {
        val source = SourceFile.kotlin("NoParams.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface HealthCheck {
                fun ping(): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("HealthCheckTraced")
        assertTrue(src.contains("\"HealthCheck.ping()\""), "Expected empty parens in message, got: $src")
    }

    @Test
    fun `suspend function preserves suspend modifier`() {
        val source = SourceFile.kotlin("SuspendFn.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface AsyncRepo {
                suspend fun fetch(id: Int): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("AsyncRepoTraced")
        assertTrue(src.contains("suspend fun fetch"), "Got: $src")
    }

    @Test
    fun `TraceContext on non-interface logs error and generates nothing`() {
        val source = SourceFile.kotlin("NotAnInterface.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            class NotAnInterface
        """.trimIndent())
        val result = compile(source)
        // KSP should succeed (error is logged, not a compilation failure)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        // No generated file should exist
        val workDir = result.kspCompilation.workingDir
        val generatedFiles = workDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "NotAnInterface.kt" }
            .filter { it.readText().contains("class NotAnInterface") }
            .toList()
        assertTrue(generatedFiles.isEmpty(), "Expected no generated wrapper, found: ${generatedFiles.map { it.name }}")
    }

    // -- runtime behaviour tests --
    // Res is an @JvmInline value class; reflection would only see the underlying Any?.
    // Instead, compile a typed runner function inside the same compilation so normal Kotlin
    // typing works end-to-end, then invoke that function via plain reflection.

    @Test
    fun `Res Fail result carries context frame with method message`() {
        val runnerSource = SourceFile.kotlin("FailRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.contextChain

            fun runFailTest(): String {
                val delegate = object : SimpleRepo {
                    override fun findById(id: Int): Res<String, String> = Res.failure("not found")
                    override fun count(): Int = 0
                }
                val traced = SimpleRepoTraced(delegate)
                val res = traced.findById(42)
                if (!res.isFail) return "FAIL: expected Res.fail"
                val frames = res.contextChain()
                if (frames.isEmpty()) return "FAIL: expected non-empty frames"
                val msg = frames.first().message
                if (!msg.contains("SimpleRepo")) return "FAIL: SimpleRepo not in message: ${'$'}msg"
                if (!msg.contains("findById")) return "FAIL: findById not in message: ${'$'}msg"
                if (frames.first().location == null) return "FAIL: expected source location"
                return "OK"
            }
        """.trimIndent())
        val result = compile(simpleInterfaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.FailRunnerKt")
            .getMethod("runFailTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    @Test
    fun `Res Ok result has no context frames`() {
        val runnerSource = SourceFile.kotlin("OkRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.contextChain

            fun runOkTest(): String {
                val delegate = object : SimpleRepo {
                    override fun findById(id: Int): Res<String, String> = Res.ok("found it")
                    override fun count(): Int = 0
                }
                val traced = SimpleRepoTraced(delegate)
                val res = traced.findById(1)
                if (!res.isOk) return "FAIL: expected Res.ok"
                val frames = res.contextChain()
                if (frames.isNotEmpty()) return "FAIL: expected empty frames, got: ${'$'}frames"
                return "OK"
            }
        """.trimIndent())
        val result = compile(simpleInterfaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.OkRunnerKt")
            .getMethod("runOkTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    @Test
    fun `non-Res method returns correct value without wrapping`() {
        val runnerSource = SourceFile.kotlin("CountRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res

            fun runCountTest(): Int {
                val delegate = object : SimpleRepo {
                    override fun findById(id: Int): Res<String, String> = Res.ok("x")
                    override fun count(): Int = 99
                }
                return SimpleRepoTraced(delegate).count()
            }
        """.trimIndent())
        val result = compile(simpleInterfaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val count = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.CountRunnerKt")
            .getMethod("runCountTest")
            .invoke(null)
        assertEquals(99, count)
    }

    @Test
    fun `vararg parameter generates correct override and spread in delegation`() {
        val source = SourceFile.kotlin("VarargMethod.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface BatchRepo {
                fun findByIds(vararg ids: Int): Res<List<String>, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("BatchRepoTraced")
        assertTrue(src.contains("vararg ids:"), "Expected vararg in param list, got: $src")
        assertTrue(src.contains("*ids"), "Expected spread operator in delegation, got: $src")
    }

    @Test
    fun `TraceMessage with double quotes generates valid string literal`() {
        val source = SourceFile.kotlin("QuotedMessage.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceMessage

            @TraceContext
            interface QuotedRepo {
                @TraceMessage("loading user \"{id}\"")
                fun findById(id: Int): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("QuotedRepoTraced")
        assertTrue(src.contains("\\\""), "Expected escaped quotes in generated source, got: $src")
    }

    @Test
    fun `abstract property on interface is delegated`() {
        val source = SourceFile.kotlin("PropertyRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface PropertyRepo {
                val name: String
                var version: Int
                fun fetch(): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("PropertyRepoTraced")
        assertTrue(src.contains("override val name:"), "Expected val name override, got: $src")
        assertTrue(src.contains("get() = delegate.name"))
        assertTrue(src.contains("override var version:"), "Expected var version override, got: $src")
        assertTrue(src.contains("set(value) { delegate.version = value }"))
    }

    @Test
    fun `interface with multiple type-parameter bounds emits where clause`() {
        val source = SourceFile.kotlin("MultiBoundRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface MultiBoundRepo<T> where T : Comparable<T>, T : CharSequence {
                fun first(): Res<T, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("MultiBoundRepoTraced")
        assertTrue(src.contains("where T :"), "Expected where clause, got: $src")
    }

    @Test
    fun `method with multiple type-parameter bounds emits where clause`() {
        val source = SourceFile.kotlin("MethodMultiBound.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface MethodMultiBoundRepo {
                fun <T> sort(items: List<T>): Res<List<T>, String> where T : Comparable<T>, T : CharSequence
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("MethodMultiBoundRepoTraced")
        assertTrue(src.contains("where T :"), "Expected where clause on method, got: $src")
    }

    @Test
    fun `interface with contravariant type parameter preserves variance`() {
        val source = SourceFile.kotlin("Sink.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface Sink<in T> {
                fun accept(item: T): Res<Unit, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("SinkTraced")
        assertTrue(src.contains("class SinkTraced<in T>"), "Expected 'in T', got: $src")
    }

    @Test
    fun `interface with covariant type parameter preserves variance`() {
        val source = SourceFile.kotlin("Source.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface Source<out T> {
                fun produce(): Res<T, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("SourceTraced")
        assertTrue(src.contains("class SourceTraced<out T>"), "Expected 'out T', got: $src")
    }

    @Test
    fun `non-Any equals overload is not skipped`() {
        val source = SourceFile.kotlin("CustomEquals.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            data class User(val id: Int)

            @TraceContext
            interface CustomEqualsRepo {
                fun equals(other: User): Res<Boolean, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("CustomEqualsRepoTraced")
        assertTrue(src.contains("override"), "Expected an override of equals(User), got: $src")
        assertTrue(src.contains("equals(other: tech.codingzen.resultkit.ksp.test.User)"), "Expected user-defined equals to be implemented, got: $src")
    }

    @Test
    fun `interface in default package compiles`() {
        val source = SourceFile.kotlin("DefaultPkgRepo.kt", """
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface DefaultPkgRepo {
                fun fetch(): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("DefaultPkgRepoTraced")
        assertFalse(src.lineSequence().any { it.trim() == "package" || it.startsWith("package ") }, "Expected no package directive in default-package output, got: $src")
    }

    @Test
    fun `TraceMessage with literal dollar is escaped`() {
        val source = SourceFile.kotlin("DollarMessage.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceMessage

            @TraceContext
            interface DollarRepo {
                @TraceMessage("price ${'$'}5 for {id}")
                fun findById(id: Int): Res<String, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("DollarRepoTraced")
        // Escaped literal dollar in generated string literal
        assertTrue(src.contains("\\\$5"), "Expected escaped literal dollar, got: $src")
        // Live interpolation for the param ref (now in `${name}` form)
        assertTrue(src.contains("\${id}"), "Expected interpolated id, got: $src")
    }

    @Test
    fun `TraceMessage referencing undefined parameter fails processor with clear error`() {
        val source = SourceFile.kotlin("BadMessage.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceMessage

            @TraceContext
            interface BadRepo {
                @TraceMessage("loading {userId}")
                fun findById(id: Int): Res<String, String>
            }
        """.trimIndent())
        // KSP runs in pass 1; the processor logs an error and the compilation fails there.
        val kspComp = KotlinCompilation().apply {
            this.sources = listOf(source)
            symbolProcessorProviders = listOf(TraceContextProcessorProvider())
            inheritClassPath = true
            messageOutputStream = System.out
        }
        val res = kspComp.compile()
        assertTrue(
            res.exitCode != KotlinCompilation.ExitCode.OK,
            "Expected compilation to fail when @TraceMessage references undefined parameter; got ${res.exitCode}",
        )
        assertTrue(
            res.messages.contains("undefined parameter") && res.messages.contains("userId"),
            "Expected error to mention undefined parameter 'userId', got: ${res.messages}",
        )
    }

    // -- runtime tests covering TraceMessage interpolation, suspend, nullable, generics, extension --

    @Test
    fun `TraceMessage interpolates param value into frame message at runtime`() {
        val ifaceSource = SourceFile.kotlin("InterpolatedRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceMessage

            @TraceContext
            interface InterpolatedRepo {
                @TraceMessage("loading user {id}")
                fun load(id: Int): Res<String, String>
            }
        """.trimIndent())
        val runnerSource = SourceFile.kotlin("InterpolatedRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.contextChain

            fun runInterpolatedTest(): String {
                val delegate = object : InterpolatedRepo {
                    override fun load(id: Int): Res<String, String> = Res.failure("nope")
                }
                val res = InterpolatedRepoTraced(delegate).load(42)
                val msg = res.contextChain().firstOrNull()?.message
                    ?: return "FAIL: no frame attached"
                return if (msg == "loading user 42") "OK" else "FAIL: got ${'$'}msg"
            }
        """.trimIndent())
        val result = compile(ifaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.InterpolatedRunnerKt")
            .getMethod("runInterpolatedTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    @Test
    fun `suspend method that fails after suspension carries context frame`() {
        val ifaceSource = SourceFile.kotlin("AsyncFailRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface AsyncFailRepo {
                suspend fun fetch(id: Int): Res<String, String>
            }
        """.trimIndent())
        val runnerSource = SourceFile.kotlin("AsyncFailRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.contextChain
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.runBlocking

            fun runAsyncFailTest(): String = runBlocking {
                val delegate = object : AsyncFailRepo {
                    override suspend fun fetch(id: Int): Res<String, String> {
                        delay(1) // real suspension
                        return Res.failure("network down")
                    }
                }
                val res = AsyncFailRepoTraced(delegate).fetch(7)
                if (!res.isFail) return@runBlocking "FAIL: expected Res.fail"
                val frames = res.contextChain()
                if (frames.isEmpty()) return@runBlocking "FAIL: expected frames"
                val msg = frames.first().message
                if (!msg.contains("AsyncFailRepo.fetch")) return@runBlocking "FAIL: bad msg ${'$'}msg"
                "OK"
            }
        """.trimIndent())
        val result = compile(ifaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.AsyncFailRunnerKt")
            .getMethod("runAsyncFailTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    @Test
    fun `nullable param interpolates as null in message at runtime`() {
        val ifaceSource = SourceFile.kotlin("NullableRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceInclude

            @TraceContext
            interface NullableRepo {
                fun find(@TraceInclude name: String?): Res<Int, String>
            }
        """.trimIndent())
        val runnerSource = SourceFile.kotlin("NullableRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.contextChain

            fun runNullableTest(): String {
                val delegate = object : NullableRepo {
                    override fun find(name: String?): Res<Int, String> = Res.failure("nope")
                }
                val res = NullableRepoTraced(delegate).find(null)
                val msg = res.contextChain().firstOrNull()?.message
                    ?: return "FAIL: no frame"
                return if (msg.contains("name=null")) "OK" else "FAIL: ${'$'}msg"
            }
        """.trimIndent())
        val result = compile(ifaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.NullableRunnerKt")
            .getMethod("runNullableTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    @Test
    fun `generic method with bounds runs end-to-end through wrapper`() {
        val ifaceSource = SourceFile.kotlin("BoundedRunRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface BoundedRunRepo {
                fun <T : Comparable<T>> max(items: List<T>): Res<T, String>
            }
        """.trimIndent())
        val runnerSource = SourceFile.kotlin("BoundedRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.getOrNull

            fun runBoundedTest(): String {
                val delegate = object : BoundedRunRepo {
                    override fun <T : Comparable<T>> max(items: List<T>): Res<T, String> =
                        Res.ok(items.max())
                }
                val res = BoundedRunRepoTraced(delegate).max(listOf(3, 1, 4, 1, 5, 9, 2, 6))
                return if (res.getOrNull() == 9) "OK" else "FAIL: ${'$'}{res.getOrNull()}"
            }
        """.trimIndent())
        val result = compile(ifaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.BoundedRunnerKt")
            .getMethod("runBoundedTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    @Test
    fun `extension receiver method runs end-to-end and attaches frame`() {
        val ifaceSource = SourceFile.kotlin("ExtRunRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface ExtRunRepo {
                fun String.parseAsInt(): Res<Int, String>
            }
        """.trimIndent())
        val runnerSource = SourceFile.kotlin("ExtRunRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.contextChain

            fun runExtTest(): String {
                val delegate = object : ExtRunRepo {
                    override fun String.parseAsInt(): Res<Int, String> =
                        this.toIntOrNull()?.let { Res.ok(it) } ?: Res.failure("not a number: ${'$'}this")
                }
                val traced: ExtRunRepo = ExtRunRepoTraced(delegate)
                // Ok path
                val okRes = with(traced) { "42".parseAsInt() }
                if (!okRes.isOk) return "FAIL: expected Ok, got ${'$'}okRes"
                // Fail path with frame
                val failRes = with(traced) { "nope".parseAsInt() }
                if (!failRes.isFail) return "FAIL: expected Fail"
                val msg = failRes.contextChain().firstOrNull()?.message
                    ?: return "FAIL: no frame on extension call"
                if (!msg.contains("parseAsInt")) return "FAIL: ${'$'}msg"
                return "OK"
            }
        """.trimIndent())
        val result = compile(ifaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.ExtRunRunnerKt")
            .getMethod("runExtTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    // -- #9 interface default parameter values --

    @Test
    fun `interface method with default param value compiles and traced wrapper resolves default`() {
        val ifaceSource = SourceFile.kotlin("DefaultRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface DefaultRepo {
                fun find(id: Int, limit: Int = 10): Res<List<Int>, String>
            }
        """.trimIndent())
        val runnerSource = SourceFile.kotlin("DefaultRunner.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.getOrNull

            fun runDefaultTest(): String {
                val delegate = object : DefaultRepo {
                    override fun find(id: Int, limit: Int): Res<List<Int>, String> = Res.ok(List(limit) { it })
                }
                val traced: DefaultRepo = DefaultRepoTraced(delegate)
                // Calling without `limit` argument exercises the interface-level default
                val res = traced.find(id = 1)
                val list = res.getOrNull() ?: return "FAIL: expected Ok"
                if (list.size != 10) return "FAIL: default should be 10, got ${'$'}{list.size}"
                return "OK"
            }
        """.trimIndent())
        val result = compile(ifaceSource, runnerSource)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val outcome = result.classLoader
            .loadClass("tech.codingzen.resultkit.ksp.test.DefaultRunnerKt")
            .getMethod("runDefaultTest")
            .invoke(null) as String
        assertEquals("OK", outcome)
    }

    // -- #5 extension-receiver methods --

    @Test
    fun `extension receiver method emits override with receiver and delegates via with`() {
        val source = SourceFile.kotlin("ExtRepo.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface ExtRepo {
                fun String.parse(): Res<Int, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("ExtRepoTraced")
        // Override carries the receiver type
        assertTrue(src.contains("override fun kotlin.String.parse()"), "Expected extension override, got: $src")
        // Body routes through `with(delegate) { this@parse.parse() }`
        assertTrue(src.contains("with(delegate) { this@parse.parse() }"), "Expected with-delegate body, got: $src")
        // Context still applied
        assertTrue(src.contains(".context("), "Expected .context on extension method, got: $src")
    }

    @Test
    fun `non-Res extension receiver method delegates without context`() {
        val source = SourceFile.kotlin("ExtNonRes.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface ExtNonResRepo {
                fun String.length2(): Int
                fun fetch(): Res<Int, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("ExtNonResRepoTraced")
        assertTrue(src.contains("override fun kotlin.String.length2()"), "Expected extension override, got: $src")
        // Non-Res extension still uses with(delegate) but no .context
        assertTrue(src.contains("with(delegate) { this@length2.length2() }"), "Expected with-delegate body, got: $src")
        val length2Block = src.substringAfter("fun kotlin.String.length2()").substringBefore("override").substringBefore("}")
        assertTrue(!length2Block.contains(".context("), "Non-Res extension should not have .context(), got: $length2Block")
    }

    @Test
    fun `extension receiver method with params delegates all args`() {
        val source = SourceFile.kotlin("ExtRepoArgs.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface ExtRepoArgs {
                fun String.parseAt(offset: Int, base: Int): Res<Int, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("ExtRepoArgsTraced")
        assertTrue(src.contains("with(delegate) { this@parseAt.parseAt(offset, base) }"), "Expected args forwarded, got: $src")
    }

    // -- #6 reserved-keyword parameter names --

    @Test
    fun `parameter named after a Kotlin keyword is backtick-escaped`() {
        val source = SourceFile.kotlin("KeywordParam.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext

            @TraceContext
            interface KeywordRepo {
                fun handle(`fun`: Int, `class`: String): Res<Unit, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("KeywordRepoTraced")
        assertTrue(src.contains("`fun`: kotlin.Int"), "Expected backticked `fun` param, got: $src")
        assertTrue(src.contains("`class`: kotlin.String"), "Expected backticked `class` param, got: $src")
        assertTrue(src.contains("delegate.handle(`fun`, `class`)"), "Expected backticked args in delegate call, got: $src")
    }

    @Test
    fun `keyword parameter with TraceInclude interpolates correctly`() {
        val source = SourceFile.kotlin("KeywordInclude.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceInclude

            @TraceContext
            interface KwIncludeRepo {
                fun call(@TraceInclude `fun`: String): Res<Unit, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("KwIncludeRepoTraced")
        // Auto-message uses `${`fun`}` so the keyword resolves
        assertTrue(src.contains("fun=\${`fun`}"), "Expected keyword interpolation, got: $src")
    }

    @Test
    fun `TraceMessage referencing a keyword parameter uses backticks in interpolation`() {
        val source = SourceFile.kotlin("KeywordTraceMsg.kt", """
            package tech.codingzen.resultkit.ksp.test
            import tech.codingzen.resultkit.Res
            import tech.codingzen.resultkit.context.TraceContext
            import tech.codingzen.resultkit.context.TraceMessage

            @TraceContext
            interface KwMsgRepo {
                @TraceMessage("calling with {fun}")
                fun call(`fun`: String): Res<Unit, String>
            }
        """.trimIndent())
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        val src = result.generatedSource("KwMsgRepoTraced")
        assertTrue(src.contains("calling with \${`fun`}"), "Expected backticked keyword in TraceMessage interpolation, got: $src")
    }

    // -- #10 unresolvable return type --
    // A well-formed program should always have resolvable return types — so the error
    // path is exercised by deleting the import of Res after the processor sees it.
    // We can't easily simulate that; instead, the change from `?: "Unit"` to logger.error
    // is verified by inspection. A regression test for the common case is unchanged: any
    // missing-type situation should now fail processor compilation rather than silently
    // generate a method that returns Unit.

    // -- test fixture --

    private val simpleInterfaceSource = SourceFile.kotlin("SimpleRepo.kt", """
        package tech.codingzen.resultkit.ksp.test
        import tech.codingzen.resultkit.Res
        import tech.codingzen.resultkit.context.TraceContext

        @TraceContext
        interface SimpleRepo {
            fun findById(id: Int): Res<String, String>
            fun count(): Int
        }
    """.trimIndent())
}
