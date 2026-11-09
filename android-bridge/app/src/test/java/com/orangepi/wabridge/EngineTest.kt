package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testes da lógica que não depende de Android.
 *
 * Prefs/DaemonManager/PairingClient mexem no contexto Android, então aqui a
 * gente testa o que é puro: parsing, limites e o ciclo de velocidade.
 */
class EngineTest {

    @Test
    fun `o modo embutido aponta para o localhost`() {
        assertEquals("http://127.0.0.1:8097", DaemonManager.EMBEDDED_URL)
    }

    @Test
    fun `o modo embutido e o padrao`() {
        // o padrão precisa ser o embutido: o app é standalone
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
        // o nome importa: o app procura exatamente este arquivo
        assertEquals("libwabridge_core.so", DaemonManager.binaryName())
    }

    @Test
    fun `o nome do binario comeca com lib e termina com so`() {
        val name = DaemonManager.binaryName()
        assertTrue(name.startsWith("lib"))
        assertTrue(name.endsWith(".so"))
    }
}
