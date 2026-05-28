package tech.codingzen.resultkit.context

import tech.codingzen.resultkit.Res
import tech.codingzen.resultkit.errorOrNull
import tech.codingzen.resultkit.getOrNull
import tech.codingzen.resultkit.getOrThrow
import tech.codingzen.resultkit.map
import tech.codingzen.resultkit.mapError
import tech.codingzen.resultkit.rail
import kotlin.test.*

class ContextTest {

    // -- context {} on Res --

    @Test
    fun `context on Ok returns same Ok, lambda never invoked`() {
        var invoked = false
        val res: Res<Int, String> = Res.ok(42)
        val result = res.context { invoked = true; "msg" }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
        assertFalse(invoked, "message lambda must not be invoked on Ok")
    }

    @Test
    fun `context on Fail appends frame, error unchanged`() {
        val res: Res<Int, String> = Res.failure("boom")
        val result = res.context { "outer op" }
        assertTrue(result.isFail)
        assertEquals("boom", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("outer op", frames[0].message)
    }

    @Test
    fun `context with location on Ok does not invoke either lambda`() {
        var msgInvoked = false
        var locInvoked = false
        val res: Res<Int, String> = Res.ok(1)
        res.context(
            message = { msgInvoked = true; "msg" },
            location = { locInvoked = true; SourceLocation("F.kt", 1) },
        )
        assertFalse(msgInvoked)
        assertFalse(locInvoked)
    }

    @Test
    fun `context with location on Fail attaches location`() {
        val res: Res<Int, String> = Res.failure("err")
        val result = res.context(
            message = { "fetching data" },
            location = { SourceLocation("Repo.kt", 10, "fetchData") },
        )
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("fetching data", frames[0].message)
        assertEquals(SourceLocation("Repo.kt", 10, "fetchData"), frames[0].location)
    }

    @Test
    fun `chained context accumulates frames innermost-first`() {
        val res: Res<Int, String> = Res.failure("err")
        val result = res
            .context { "specific op" }   // added first — index 0
            .context { "broad op" }       // added second — index 1
        val frames = result.contextChain()
        assertEquals(2, frames.size)
        assertEquals("specific op", frames[0].message)
        assertEquals("broad op", frames[1].message)
    }

    @Test
    fun `context on Ok preserves value through chain`() {
        val res: Res<String, String> = Res.ok("value")
        val result = res.context { "ignored" }.context { "also ignored" }
        assertTrue(result.isOk)
        assertEquals("value", result.getOrNull())
    }

    // -- withFrame inside rail {} --

    @Test
    fun `withFrame inside rail appends frame on short-circuit`() {
        val result = rail<Int, String> {
            withFrame("loading user") {
                Res.failure<String>("not found").orFail()
            }
        }
        assertTrue(result.isFail)
        assertEquals("not found", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("loading user", frames[0].message)
    }

    @Test
    fun `withFrame on Ok path returns value without frame`() {
        val result = rail<Int, String> {
            withFrame("no error here") {
                Res.ok<Int>(99).orFail()
            }
        }
        assertTrue(result.isOk)
        assertEquals(99, result.getOrNull())
        assertEquals(emptyList(), result.contextChain())
    }

    @Test
    fun `nested withFrame stacks frames innermost-first`() {
        val result = rail<Int, String> {
            withFrame("outer") {
                withFrame("inner") {
                    Res.failure<String>("err").orFail()
                }
            }
        }
        val frames = result.contextChain()
        assertEquals(2, frames.size)
        assertEquals("inner", frames[0].message)
        assertEquals("outer", frames[1].message)
    }

    @Test
    fun `withFrame with location only invokes location lambda on fail`() {
        var locInvoked = false
        val result = rail<Int, String> {
            withFrame("op", location = { locInvoked = true; SourceLocation("X.kt", 5) }) {
                Res.failure<String>("err").orFail()
            }
        }
        assertTrue(locInvoked)
        val frames = result.contextChain()
        assertEquals(SourceLocation("X.kt", 5), frames[0].location)
    }

    @Test
    fun `withFrame with location does not invoke location lambda on success`() {
        var locInvoked = false
        val result = rail<Int, String> {
            withFrame("op", location = { locInvoked = true; SourceLocation("X.kt", 5) }) {
                42
            }
        }
        assertFalse(locInvoked)
        assertTrue(result.isOk)
    }

    // -- orFailContext { } inside rail {} --

    @Test
    fun `orFailContext attaches frame on failure`() {
        val result = rail<Int, String> {
            Res.failure<String>("db error").orFailContext { "fetching record" }
        }
        assertTrue(result.isFail)
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("fetching record", frames[0].message)
    }

    @Test
    fun `orFailContext on Ok returns value without frame`() {
        val result = rail<Int, String> {
            Res.ok<Int>(7).orFailContext { "fetching record" }
        }
        assertTrue(result.isOk)
        assertEquals(7, result.getOrNull())
    }

    @Test
    fun `orFailContext with location attaches location on failure`() {
        val result = rail<Int, String> {
            Res.failure<String>("err")
                .orFailContext({ "query" }, { SourceLocation("Repo.kt", 20, "findAll") })
        }
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("query", frames[0].message)
        assertEquals(SourceLocation("Repo.kt", 20, "findAll"), frames[0].location)
    }

    @Test
    fun `orFailContext and withFrame combine correctly`() {
        val result = rail<Int, String> {
            withFrame("outer scope") {
                Res.failure<String>("err").orFailContext { "inner op" }
            }
        }
        val frames = result.contextChain()
        assertEquals(2, frames.size)
        assertEquals("inner op", frames[0].message)
        assertEquals("outer scope", frames[1].message)
    }

    // -- mapError preserves frames --

    @Test
    fun `mapError preserves frames from original Fail`() {
        val res: Res<Int, String> = Res.failure("original")
            .context { "some context" }
        val mapped: Res<Int, Int> = res.mapError { it.length }
        assertEquals(8, mapped.errorOrNull()) // "original".length == 8
        val frames = mapped.contextChain()
        assertEquals(1, frames.size)
        assertEquals("some context", frames[0].message)
    }

    // -- map preserves frames on Fail path --

    @Test
    fun `map on Fail passes through frames unchanged`() {
        val res: Res<Int, String> = Res.failure("err")
            .context { "ctx" }
        val mapped: Res<String, String> = res.map { it.toString() }
        assertTrue(mapped.isFail)
        val frames = mapped.contextChain()
        assertEquals(1, frames.size)
        assertEquals("ctx", frames[0].message)
    }

    // -- extended fold --

    @Test
    fun `extended fold receives error and frame list`() {
        val res: Res<Int, String> = Res.failure("err")
            .context { "ctx1" }
            .context { "ctx2" }

        val (error, frames) = res.fold(
            onOk = { error("should not be ok") },
            onFail = { e, f -> Pair(e, f) },
        )
        assertEquals("err", error)
        assertEquals(2, frames.size)
        assertEquals("ctx1", frames[0].message)
        assertEquals("ctx2", frames[1].message)
    }

    @Test
    fun `extended fold on Ok receives value`() {
        val res: Res<Int, String> = Res.ok(42)
        val result = res.fold(
            onOk = { it * 2 },
            onFail = { _, _ -> -1 },
        )
        assertEquals(84, result)
    }

    // -- rendering utilities --

    @Test
    fun `contextChain returns empty list on Ok`() {
        val res: Res<Int, String> = Res.ok(1)
        assertEquals(emptyList(), res.contextChain())
    }

    @Test
    fun `contextChain returns empty list on Fail with no frames`() {
        val res: Res<Int, String> = Res.failure("err")
        assertEquals(emptyList(), res.contextChain())
    }

    @Test
    fun `renderContext returns empty string on Ok`() {
        assertEquals("", Res.ok<Int>(1).renderContext())
    }

    @Test
    fun `renderContext formats error and frames`() {
        val res: Res<Int, String> = Res.failure("connection refused")
            .context { "MetricsRepository.findByTeam(teamId=7)" }
            .context { "building dashboard for user 42" }
        val output = res.renderContext()
        assertTrue(output.contains("connection refused"))
        assertTrue(output.contains("0: MetricsRepository.findByTeam(teamId=7)"))
        assertTrue(output.contains("1: building dashboard for user 42"))
    }

    @Test
    fun `renderContext exact format with two frames no location`() {
        val res: Res<Int, String> = Res.failure("connection refused")
            .context { "MetricsRepository.findByTeam(teamId=7)" }
            .context { "building dashboard for user 42" }
        val expected =
            "connection refused\n" +
            "\n" +
            "  0: MetricsRepository.findByTeam(teamId=7)\n" +
            "  1: building dashboard for user 42"
        assertEquals(expected, res.renderContext())
    }

    @Test
    fun `renderContext exact format with location`() {
        val res: Res<Int, String> = Res.failure("err")
            .contextFrame { Frame("op", location = SourceLocation("File.kt", 42, "doIt")) }
        val expected =
            "err\n" +
            "\n" +
            "  0: op\n" +
            "     at File.kt:42 in doIt"
        assertEquals(expected, res.renderContext())
    }

    @Test
    fun `renderContext exact format with attachment`() {
        val res: Res<Int, String> = Res.failure("err")
            .contextFrame { Frame("op", attachment = "req-123") }
        val expected =
            "err\n" +
            "\n" +
            "  0: op\n" +
            "     attachment=req-123"
        assertEquals(expected, res.renderContext())
    }

    @Test
    fun `contextSummary returns empty string on Ok`() {
        assertEquals("", Res.ok<Int>(1).contextSummary())
    }

    @Test
    fun `contextSummary formats breadcrumb trail`() {
        val res: Res<Int, String> = Res.failure("db error")
            .context { "findUser" }
            .context { "buildDashboard" }
        val summary = res.contextSummary()
        // frames: [findUser, buildDashboard]; reversed for summary: buildDashboard → findUser → error
        assertTrue(summary.contains("→"))
        assertTrue(summary.contains("db error"))
    }

    @Test
    fun `contextSummary exact format`() {
        val res: Res<Int, String> = Res.failure("db error")
            .context { "findUser" }
            .context { "buildDashboard" }
        assertEquals("buildDashboard → findUser → db error", res.contextSummary())
    }

    @Test
    fun `contextSummary with no frames returns error toString`() {
        val res: Res<Int, String> = Res.failure("bare error")
        assertEquals("bare error", res.contextSummary())
    }

    @Test
    fun `contextMap returns empty map on Ok`() {
        assertEquals(emptyMap<String, Any?>(), Res.ok<Int>(1).contextMap())
    }

    @Test
    fun `contextMap returns structured map on Fail`() {
        val res: Res<Int, String> = Res.failure("err").context { "ctx" }
        val map = res.contextMap()
        assertEquals("err", map["error"])
        val frames = map["frames"] as List<*>
        assertEquals(1, frames.size)
        val frame = frames[0] as Map<*, *>
        assertEquals("ctx", frame["message"])
    }

    @Test
    fun `contextMap exact structure with location and attachment`() {
        val res: Res<Int, String> = Res.failure("err")
            .contextFrame { Frame("op", attachment = 7, location = SourceLocation("F.kt", 10, "fn")) }
        val map = res.contextMap()
        val frames = map["frames"] as List<*>
        val frame = frames[0] as Map<*, *>
        assertEquals("op", frame["message"])
        assertEquals("F.kt:10 in fn", frame["location"])
        assertEquals(7, frame["attachment"])
        assertEquals(setOf("message", "location", "attachment"), frame.keys)
    }

    // -- findAttachment --

    @Test
    fun `findAttachment returns typed attachment from frame chain`() {
        data class RequestId(val value: String)
        val requestId = RequestId("req-123")
        val res: Res<Int, String> = Res.failure("err")
            .contextFrame { Frame("op", attachment = requestId) }
        val found = res.contextChain().findAttachment<RequestId>()
        assertEquals(requestId, found)
    }

    @Test
    fun `findAttachment returns null when no matching attachment`() {
        val res: Res<Int, String> = Res.failure("err").context { "no attachment" }
        val found = res.contextChain().findAttachment<Int>()
        assertNull(found)
    }

    @Test
    fun `findAttachment returns first matching type`() {
        val res: Res<Int, String> = Res.failure("err")
            .contextFrame { Frame("first", attachment = 1) }
            .contextFrame { Frame("second", attachment = 2) }
        val found = res.contextChain().findAttachment<Int>()
        assertEquals(1, found)
    }

    // -- Frame ordering verification --

    @Test
    fun `frame ordering index 0 is innermost context`() {
        val res = rail<Int, String> {
            withFrame("level2") {          // outermost
                withFrame("level1") {      // middle
                    Res.failure<String>("err").orFailContext { "level0" }   // innermost
                }
            }
        }
        val frames = res.contextChain()
        assertEquals(3, frames.size)
        assertEquals("level0", frames[0].message)
        assertEquals("level1", frames[1].message)
        assertEquals("level2", frames[2].message)
    }

    @Test
    fun `withFrame does not append frame for FailException from foreign scope`() {
        // FailException and Rail are internal to the result-kit module, accessible here.
        val foreignScope = tech.codingzen.resultkit.Rail<String>()
        val foreignException = tech.codingzen.resultkit.FailException("foreign error", foreignScope)

        var escaped: tech.codingzen.resultkit.FailException? = null
        try {
            rail<Int, String> {
                withFrame("should not be added") {
                    // Conditional so the block's return type is Int, not Nothing
                    if (true) throw foreignException
                    0
                }
            }
        } catch (e: tech.codingzen.resultkit.FailException) {
            escaped = e
        }

        assertNotNull(escaped, "FailException from foreign scope should escape rail{}")
        assertSame(foreignException, escaped, "Exception should be rethrown unmodified")
        assertTrue(escaped.frames.isEmpty(), "withFrame must not add a frame: ${escaped.frames}")
    }

    // -- catching inside withFrame --

    @Test
    fun `catching inside withFrame catches exception, context applied on propagation`() {
        val result = rail<Int, String> {
            withFrame("outer operation") {
                val fm = catching { e: Exception -> "mapped: ${e.message}" }
                fm { throw RuntimeException("kaboom") }
            }
        }
        assertTrue(result.isFail)
        assertEquals("mapped: kaboom", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("outer operation", frames[0].message)
    }

    // -- catching mapper throws inside withFrame --

    @Test
    fun `catching mapper that throws inside withFrame raises ErrorMapperException`() {
        val ex = assertFailsWith<tech.codingzen.resultkit.ErrorMapperException> {
            rail<Int, String> {
                withFrame("outer") {
                    val fm = catching { _: Exception -> throw IllegalStateException("mapper broke") }
                    fm<Int> { throw RuntimeException("original") }
                }
            }
        }
        assertTrue(ex.message!!.contains("mapper broke"), "Got: ${ex.message}")
    }

    // -- contextMap null handling --

    @Test
    fun `contextMap omits null location and attachment`() {
        val res: Res<Int, String> = Res.failure("err").context { "msg" }
        val frames = res.contextMap()["frames"] as List<*>
        val frame = frames[0] as Map<*, *>
        assertEquals("msg", frame["message"])
        assertFalse(frame.containsKey("location"), "null location should be omitted")
        assertFalse(frame.containsKey("attachment"), "null attachment should be omitted")
    }

    @Test
    fun `contextMap includes location and attachment when present`() {
        val res: Res<Int, String> = Res.failure("err")
            .contextFrame { Frame("msg", attachment = 42, location = SourceLocation("X.kt", 1)) }
        val frames = res.contextMap()["frames"] as List<*>
        val frame = frames[0] as Map<*, *>
        assertEquals("msg", frame["message"])
        assertEquals("X.kt:1", frame["location"])
        assertEquals(42, frame["attachment"])
    }

    // -- SourceLocation toString --

    @Test
    fun `SourceLocation toString without function`() {
        assertEquals("Foo.kt:42", SourceLocation("Foo.kt", 42).toString())
    }

    @Test
    fun `SourceLocation toString with function`() {
        assertEquals("Foo.kt:42 in myFun", SourceLocation("Foo.kt", 42, "myFun").toString())
    }

    // -- orFail preserves context frames --

    @Test
    fun `orFail preserves existing context frames`() {
        val res: Res<Int, String> = Res.failure("err")
            .context { "inner context" }
        val result = rail<Int, String> {
            res.orFail()
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("inner context", frames[0].message)
    }

    @Test
    fun `orFail with mapError preserves existing context frames`() {
        val res: Res<Int, Int> = Res.failure(404)
            .context { "from http layer" }
        val result = rail<Int, String> {
            res.orFail { code -> "HTTP $code" }
        }
        assertTrue(result.isFail)
        assertEquals("HTTP 404", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("from http layer", frames[0].message)
    }

    @Test
    fun `orFail with ErrorMappingRail preserves existing context frames`() {
        val res: Res<Int, Int> = Res.failure(500)
            .context { "api call" }
        val result = rail<Int, String> {
            val http = mapping<Int> { code -> "Error $code" }
            res.orFail(http)
        }
        assertTrue(result.isFail)
        assertEquals("Error 500", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("api call", frames[0].message)
    }

    // -- getOrThrow attaches frames as suppressed --

    @Test
    fun `getOrThrow attaches frames as suppressed FrameTrace entries`() {
        val err = RuntimeException("boom")
        val res: Res<Int, RuntimeException> = Res.failure(err)
            .context { "outer" }
            .context(
                { "inner" },
                { SourceLocation("X.kt", 10, "foo") },
            )
        val caught = try {
            res.getOrThrow()
            null
        } catch (e: RuntimeException) { e }
        assertNotNull(caught)
        assertSame(err, caught)
        val suppressed = caught.suppressed
        assertEquals(2, suppressed.size)
        assertTrue(suppressed[0] is FrameTrace)
        assertEquals("outer", (suppressed[0] as FrameTrace).frame.message)
        assertTrue(suppressed[1] is FrameTrace)
        assertEquals("inner", (suppressed[1] as FrameTrace).frame.message)
        assertEquals("X.kt:10 in foo", (suppressed[1] as FrameTrace).frame.location.toString())
    }

    @Test
    fun `getOrThrow with transform attaches frames as suppressed FrameTrace entries`() {
        val res: Res<Int, String> = Res.failure("boom")
            .context { "step1" }
            .context { "step2" }
        val caught = try {
            res.getOrThrow { IllegalStateException("transformed: $it") }
            null
        } catch (e: IllegalStateException) { e }
        assertNotNull(caught)
        assertEquals("transformed: boom", caught.message)
        val suppressed = caught.suppressed
        assertEquals(2, suppressed.size)
        assertEquals("step1", (suppressed[0] as FrameTrace).frame.message)
        assertEquals("step2", (suppressed[1] as FrameTrace).frame.message)
    }

    @Test
    fun `getOrThrow on Fail with no frames adds no suppressed`() {
        val err = RuntimeException("boom")
        val res: Res<Int, RuntimeException> = Res.failure(err)
        val caught = try {
            res.getOrThrow()
            null
        } catch (e: RuntimeException) { e }
        assertNotNull(caught)
        assertSame(err, caught)
        assertEquals(0, caught.suppressed.size)
    }

    // -- recover overload that exposes frames --

    @Test
    fun `recover with frames lambda observes frames on Fail, returns Ok with no frames`() {
        val seenFrames = mutableListOf<String>()
        val res: Res<Int, String> = Res.failure("boom")
            .context { "outer" }
            .context { "inner" }
        val recovered = res.recover { _, frames ->
            seenFrames += frames.map { it.message }
            -1
        }
        assertTrue(recovered.isOk)
        assertEquals(-1, recovered.getOrNull())
        assertEquals(listOf("outer", "inner"), seenFrames)
        assertEquals(emptyList(), recovered.contextChain())
    }

    @Test
    fun `recover with frames lambda passes through Ok without invoking transform`() {
        var invoked = false
        val res: Res<Int, String> = Res.ok(42)
        val recovered = res.recover { _, _ ->
            invoked = true
            -1
        }
        assertTrue(recovered.isOk)
        assertEquals(42, recovered.getOrNull())
        assertFalse(invoked, "transform must not run on Ok")
    }

    @Test
    fun `recover with frames lambda receives empty list when no frames attached`() {
        val res: Res<Int, String> = Res.failure("boom")
        var seenSize = -1
        val recovered = res.recover { _, frames ->
            seenSize = frames.size
            0
        }
        assertTrue(recovered.isOk)
        assertEquals(0, seenSize)
    }

    @Test
    fun `orFail preserves frames from KSP-style chained context`() {
        // Simulates what a @TraceContext wrapper produces
        val res: Res<Int, String> = Res.failure("not found")
            .context(
                { "UserRepo.findById(id)" },
                { SourceLocation("UserRepo.kt", 10, "findById") },
            )
        val result = rail<Int, String> {
            withFrame("loading dashboard") {
                res.orFail()
            }
        }
        assertTrue(result.isFail)
        val frames = result.contextChain()
        assertEquals(2, frames.size)
        assertEquals("UserRepo.findById(id)", frames[0].message)
        assertNotNull(frames[0].location)
        assertEquals("loading dashboard", frames[1].message)
    }
}
