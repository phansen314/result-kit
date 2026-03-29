package tech.codingzen.resultkit.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(!src.contains("\$id"), "Param value should not appear by default, got: $src")
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
        assertTrue(src.contains("loading user \$id"), "Got: $src")
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
        assertTrue(src.contains("id=\$id"), "TraceInclude param should emit value, got: $src")
        assertTrue(!src.contains("\$version"), "Non-annotated param should not emit value, got: $src")
        assertTrue(src.contains("version"), "Non-annotated param name should still appear, got: $src")
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
