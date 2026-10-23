/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 可复用的 [OfficialProviderCall] 包装：每个 Hook 的每个线程持有一个实例。
 *
 * [dispatch] 会保存并恢复外层调用状态，同名方法递归触发 Hook 时内外层互不污染。
 */
internal class ReusableOfficialProviderCall : OfficialProviderCall {
    private var chain: XposedInterface.Chain? = null
    private var proceedCalled = false
    private var lastResult: Any? = null
    private var lastFailure: Throwable? = null

    override val executable: Executable
        get() = requireChain().executable

    override val thisObject: Any?
        get() = requireChain().thisObject

    override val args: List<Any?>
        get() = requireChain().args

    fun dispatch(
        chain: XposedInterface.Chain,
        invoke: (OfficialProviderCall) -> Any?,
        onFallback: (Throwable) -> Unit = {},
    ): Any? {
        val previousChain = this.chain
        val previousProceedCalled = proceedCalled
        val previousResult = lastResult
        val previousFailure = lastFailure
        this.chain = chain
        proceedCalled = false
        lastResult = null
        lastFailure = null
        return try {
            invoke(this)
        } catch (error: Throwable) {
            onFallback(error)
            if (proceedCalled) {
                // proceed 本身抛错时原样上抛，避免再次执行原方法。
                lastFailure?.let { throw it }
                lastResult
            } else {
                proceed()
            }
        } finally {
            this.chain = previousChain
            proceedCalled = previousProceedCalled
            lastResult = previousResult
            lastFailure = previousFailure
        }
    }

    override fun proceed(): Any? = proceedWithChain { it.proceed() }

    override fun proceed(args: Array<out Any?>): Any? = proceedWithChain { it.proceed(args) }

    override fun proceedWith(thisObject: Any): Any? = proceedWithChain { it.proceedWith(thisObject) }

    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? =
        proceedWithChain { it.proceedWith(thisObject, args) }

    private inline fun proceedWithChain(block: (XposedInterface.Chain) -> Any?): Any? {
        val current = requireChain()
        proceedCalled = true
        return try {
            block(current).also {
                lastResult = it
                lastFailure = null
            }
        } catch (error: Throwable) {
            lastFailure = error
            throw error
        }
    }

    private fun requireChain(): XposedInterface.Chain =
        chain ?: error("OfficialProviderCall 只能在拦截回调期间使用")
}

internal class OfficialProviderInterceptHooker(
    private val interceptor: OfficialProviderInterceptor,
    private val onFirstCallback: () -> Unit = {},
    private val onFallback: (Throwable) -> Unit = {},
) : XposedInterface.Hooker {
    private val calls = ThreadLocal.withInitial { ReusableOfficialProviderCall() }
    private val firstCallbackRecorded = AtomicBoolean(false)
    private val fallbackRecorded = AtomicBoolean(false)

    override fun intercept(chain: XposedInterface.Chain): Any? {
        if (firstCallbackRecorded.compareAndSet(false, true)) onFirstCallback()
        return calls.get().dispatch(
            chain = chain,
            invoke = interceptor::intercept,
            onFallback = { error ->
                if (fallbackRecorded.compareAndSet(false, true)) onFallback(error)
            },
        )
    }
}
