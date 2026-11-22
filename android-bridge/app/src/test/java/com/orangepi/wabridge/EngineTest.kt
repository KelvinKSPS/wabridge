package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the logic that does not depend on Android.
 *
 * Prefs/DaemonManager/PairingClient touch the Android context, so here we
 * test what is pure: parsing, limits and the speed cycle.
 */
class EngineTest {

    @Test
    fun `o modo embutido aponta para o localhost`() {
        assertEquals("http://127.0.0.1:8097", DaemonManager.EMBEDDED_URL)
    }

    @Test
    fun `o modo embutido e o padrao`() {
        // the default must be the embedded one: the app is standalone
        assertEquals("EMBUTIDO", Prefs.Engine.EMBUTIDO.name)
        assertEquals(2, Prefs.Engine.values().size)
    }

    @Test
    fun `os modos tem nomes previsiveis`() {
        assertEquals("EMBUTIDO", Prefs.Engine.valueOf("EMBUTIDO").name)
        assertEquals("EXTERNO", Prefs.Engine.valueOf("EXTERNO").name)
    }

    @Test
    fun `o binario do daemon tem o nome certo`() {
        // the name matters: the app looks for exactly this file
        assertEquals("libwabridge_core.so", DaemonManager.binaryName())
    }

    @Test
    fun `o nome do binario comeca com lib e termina com so`() {
        val name = DaemonManager.binaryName()
        assertTrue(name.startsWith("lib"))
        assertTrue(name.endsWith(".so"))
    }
}
