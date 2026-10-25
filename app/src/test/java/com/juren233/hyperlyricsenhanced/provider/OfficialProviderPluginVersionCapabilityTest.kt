package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialProviderPluginVersionCapabilityTest {
    @Test
    fun pluginVersionCapabilityPreservesBinaryDescriptor() {
        val method = OfficialProviderHost::class.java.getDeclaredMethod("getPluginVersionCode")
        assertEquals(Int::class.javaPrimitiveType, method.returnType)
        assertEquals(0, method.parameterCount)
    }
}
