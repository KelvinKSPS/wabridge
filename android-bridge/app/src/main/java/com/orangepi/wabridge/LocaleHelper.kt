package com.orangepi.wabridge

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Aplica o idioma escolhido pelo usuário (ou o do sistema) ao Context.
 *
 * O idioma só muda de verdade quando o Context carrega as strings com o
 * locale certo. Por isso embrulhamos o base context da Activity: assim
 * getString() passa a devolver os textos no idioma selecionado.
 */
object LocaleHelper {

    /** Devolve um Context com o locale do idioma salvo aplicado. */
    fun wrap(context: Context): Context {
        val lang = Prefs.language(context)
        val locale = localeFor(lang) ?: return context // SYSTEM: não força nada

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun localeFor(lang: Language): Locale? = when (lang) {
        Language.SYSTEM -> null
        Language.PORTUGUESE -> Locale("pt", "BR")
        Language.ENGLISH -> Locale("en", "US")
        Language.SPANISH -> Locale("es", "ES")
        Language.CHINESE -> Locale("zh", "CN")
    }
}
