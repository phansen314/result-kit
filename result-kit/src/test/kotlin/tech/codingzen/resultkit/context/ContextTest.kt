package tech.codingzen.resultkit.context

import tech.codingzen.resultkit.Res
import tech.codingzen.resultkit.errorOrNull
import tech.codingzen.resultkit.getOrNull
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

    // -- withContext inside rail {} --

    @Test
    fun `withContext inside rail appends frame on short-circuit`() {
        val result = rail<Int, String> {
            withContext("loading user") {
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
    fun `withContext on Ok path returns value without frame`() {
        val result = rail<Int, String> {
            withContext("no error here") {
                Res.ok<Int>(99).orFail()
            }
        }
        assertTrue(result.isOk)
        assertEquals(99, result.getOrNull())
        assertEquals(emptyList(), result.contextChain())
    }

    @Test
    fun `nested withContext stacks frames innermost-first`() {
        val result = rail<Int, String> {
            withContext("outer") {
                withContext("inner") {
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
    fun `withContext with location only invokes location lambda on fail`() {
        var locInvoked = false
        val result = rail<Int, String> {
            withContext("op", location = { locInvoked = true; SourceLocation("X.kt", 5) }) {
                Res.failure<String>("err").orFail()
            }
        }
        assertTrue(locInvoked)
        val frames = result.contextChain()
        assertEquals(SourceLocation("X.kt", 5), frames[0].location)
    }

    @Test
    fun `withContext with location does not invoke location lambda on success`() {
        var locInvoked = false
        val result = rail<Int, String> {
            withContext("op", location = { locInvoked = true; SourceLocation("X.kt", 5) }) {
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
    fun `orFailContext and withContext combine correctly`() {
        val result = rail<Int, String> {
            withContext("outer scope") {
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
            withContext("level2") {          // outermost
                withContext("level1") {      // middle
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
    fun `withContext does not append frame for FailException from foreign scope`() {
        // FailException and Rail are internal to the result-kit module, accessible here.
        val foreignScope = tech.codingzen.resultkit.Rail<String>()
        val foreignException = tech.codingzen.resultkit.FailException("foreign error", foreignScope)

        var escaped: tech.codingzen.resultkit.FailException? = null
        try {
            rail<Int, String> {
                withContext("should not be added") {
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
        assertTrue(escaped.frames.isEmpty(), "withContext must not add a frame: ${escaped.frames}")
    }

    // -- failMapping inside withContext --

    @Test
    fun `failMapping inside withContext catches exception, context applied on propagation`() {
        val result = rail<Int, String> {
            withContext("outer operation") {
                val fm = failMapping { e: Exception -> "mapped: ${e.message}" }
                fm { throw RuntimeException("kaboom") }
            }
        }
        assertTrue(result.isFail)
        assertEquals("mapped: kaboom", result.errorOrNull())
        val frames = result.contextChain()
        assertEquals(1, frames.size)
        assertEquals("outer operation", frames[0].message)
    }

    // -- failMapping mapper throws inside withContext --

    @Test
    fun `failMapping mapper that throws inside withContext raises ErrorMapperException`() {
        val ex = assertFailsWith<tech.codingzen.resultkit.ErrorMapperException> {
            rail<Int, String> {
                withContext("outer") {
                    val fm = failMapping { _: Exception -> throw IllegalStateException("mapper broke") }
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
}
