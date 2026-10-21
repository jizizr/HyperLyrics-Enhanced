/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.island

import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存宿主类的公开方法查找结果，避免热路径反复调用 `Class.getMethods()`。
 * 未找到的方法同样缓存，避免每次重复抛异常。宿主类与 SystemUI 进程同寿命，
 * 强引用缓存在进程内不会额外泄漏；热重载时清空。
 */
internal object IslandReflect {
    private val noArgMethods =
        ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Optional<Method>>>()
    private val exactMethods =
        ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Optional<Method>>>()
    private val arityMethods =
        ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Optional<Method>>>()

    fun noArgMethod(type: Class<*>, name: String): Method? =
        noArgMethods.getOrPut(type) { ConcurrentHashMap() }
            .getOrPut(name) {
                Optional.ofNullable(runCatching { type.getMethod(name) }.getOrNull())
            }
            .orElse(null)

    fun method(type: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        if (parameterTypes.isEmpty()) return noArgMethod(type, name)
        val key = buildString {
            append(name)
            parameterTypes.forEach { append('\u001F').append(it.name) }
        }
        return exactMethods.getOrPut(type) { ConcurrentHashMap() }
            .getOrPut(key) {
                Optional.ofNullable(
                    runCatching { type.getMethod(name, *parameterTypes) }.getOrNull()
                )
            }
            .orElse(null)
    }

    fun methodByArity(type: Class<*>, name: String, parameterCount: Int): Method? {
        val key = "$name\u001F$parameterCount"
        return arityMethods.getOrPut(type) { ConcurrentHashMap() }
            .getOrPut(key) {
                Optional.ofNullable(
                    type.methods.firstOrNull {
                        it.name == name && it.parameterTypes.size == parameterCount
                    }
                )
            }
            .orElse(null)
    }

    fun clear() {
        noArgMethods.clear()
        exactMethods.clear()
        arityMethods.clear()
    }
}
