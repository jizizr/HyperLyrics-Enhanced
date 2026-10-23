/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Executable

class OfficialProviderInterceptHookTest {

    @Test
    fun interceptCanRewriteTheResultAfterProceed() {
        val chain = FakeChain(result = "original")
        val hooker = OfficialProviderInterceptHooker(
            OfficialProviderInterceptor { call -> "${call.proceed()}-hooked" },
        )

        assertEquals("original-hooked", hooker.intercept(chain))
        assertEquals(1, chain.proceedCalls)
    }

    @Test
    fun interceptCanSkipTheOriginalMethod() {
        val chain = FakeChain(result = "original")
        val hooker = OfficialProviderInterceptHooker(
            OfficialProviderInterceptor { "replacement" },
        )

        assertEquals("replacement", hooker.intercept(chain))
        assertEquals(0, chain.proceedCalls)
    }

    @Test
    fun fallsBackToOriginalWhenInterceptorThrowsBeforeProceed() {
        val chain = FakeChain(result = "original")
        var fallback: Throwable? = null
        val hooker = OfficialProviderInterceptHooker(
            interceptor = OfficialProviderInterceptor { error("boom") },
            onFallback = { fallback = it },
        )

        assertEquals("original", hooker.intercept(chain))
        assertEquals(1, chain.proceedCalls)
        assertNotNull(fallback)
    }

    @Test
    fun replaysProceedResultWhenInterceptorThrowsAfterProceed() {
        val chain = FakeChain(result = "original")
        val hooker = OfficialProviderInterceptHooker(
            OfficialProviderInterceptor { call ->
                call.proceed()
                error("boom")
            },
        )

        assertEquals("original", hooker.intercept(chain))
        assertEquals(1, chain.proceedCalls)
    }

    @Test
    fun rethrowsProceedFailureWithoutCallingOriginalTwice() {
        val chain = FakeChain(failure = IllegalStateException("original failure"))
        val hooker = OfficialProviderInterceptHooker(
            OfficialProviderInterceptor { call -> call.proceed() },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            hooker.intercept(chain)
        }
        assertEquals("original failure", error.message)
        assertEquals(1, chain.proceedCalls)
    }

    @Test
    fun forwardsRewrittenArgumentsToProceed() {
        val chain = FakeChain(result = "original")
        val hooker = OfficialProviderInterceptHooker(
            OfficialProviderInterceptor { call -> call.proceed(arrayOf("rewritten")) },
        )

        hooker.intercept(chain)

        assertArrayEquals(arrayOf<Any?>("rewritten"), chain.lastProceedArgs)
    }

    @Test
    fun reportsTheFirstCallbackOnlyOnce() {
        var firstCallbacks = 0
        val hooker = OfficialProviderInterceptHooker(
            interceptor = OfficialProviderInterceptor { call -> call.proceed() },
            onFirstCallback = { firstCallbacks += 1 },
        )

        hooker.intercept(FakeChain(result = "first"))
        hooker.intercept(FakeChain(result = "second"))

        assertEquals(1, firstCallbacks)
    }

    @Test
    fun restoresCallStateAfterNestedInvocation() {
        lateinit var hooker: OfficialProviderInterceptHooker
        var nestedResult: Any? = null
        val outer = FakeChain(
            receiver = "outer",
            result = "outer-result",
            onProceed = {
                nestedResult = hooker.intercept(
                    FakeChain(receiver = "inner", result = "inner-result"),
                )
            },
        )
        hooker = OfficialProviderInterceptHooker(
            OfficialProviderInterceptor { call ->
                val receiver = call.thisObject
                "${receiver}:${call.proceed()}"
            },
        )

        assertEquals("outer:outer-result", hooker.intercept(outer))
        assertEquals("inner:inner-result", nestedResult)
    }

    private class FakeChain(
        private val receiver: Any? = "receiver",
        private val argumentList: List<Any?> = listOf("arg"),
        var result: Any? = "original",
        var failure: Throwable? = null,
        private val onProceed: (() -> Unit)? = null,
    ) : XposedInterface.Chain {
        var proceedCalls = 0
            private set
        var lastProceedArgs: Array<out Any?>? = null
            private set

        override fun getExecutable(): Executable = Any::class.java.getMethod("toString")

        override fun getThisObject(): Any? = receiver

        override fun getArgs(): List<Any?> = argumentList

        override fun getArg(index: Int): Any? = argumentList[index]

        override fun proceed(): Any? {
            onProceed?.invoke()
            proceedCalls += 1
            failure?.let { throw it }
            return result
        }

        override fun proceed(args: Array<out Any?>): Any? {
            proceedCalls += 1
            lastProceedArgs = args
            failure?.let { throw it }
            return result
        }

        override fun proceedWith(thisObject: Any): Any? {
            proceedCalls += 1
            failure?.let { throw it }
            return result
        }

        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? {
            proceedCalls += 1
            lastProceedArgs = args
            failure?.let { throw it }
            return result
        }
    }
}
