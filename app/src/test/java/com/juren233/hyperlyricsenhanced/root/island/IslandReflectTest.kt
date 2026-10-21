/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IslandReflectTest {
    class Sample {
        fun value(): Int = 42
        fun overloaded(value: Int): Int = value
        fun overloaded(value: String): Int = value.length
    }

    @Test fun `no-arg method lookup is cached`() {
        val first = IslandReflect.noArgMethod(Sample::class.java, "value")
        val second = IslandReflect.noArgMethod(Sample::class.java, "value")
        assertSame(first, second)
        assertEquals(42, first?.invoke(Sample()))
    }

    @Test fun `missing method is cached as null`() {
        assertNull(IslandReflect.noArgMethod(Sample::class.java, "missing"))
        assertNull(IslandReflect.noArgMethod(Sample::class.java, "missing"))
    }

    @Test fun `exact signature lookup ignores same-arity overloads`() {
        val method = IslandReflect.method(
            Sample::class.java,
            "overloaded",
            Int::class.javaPrimitiveType!!,
        )
        assertEquals(Int::class.javaPrimitiveType, method?.parameterTypes?.single())
    }

    @Test fun `arity lookup is cached`() {
        val first = IslandReflect.methodByArity(Sample::class.java, "overloaded", 1)
        val second = IslandReflect.methodByArity(Sample::class.java, "overloaded", 1)
        assertSame(first, second)
    }
}
