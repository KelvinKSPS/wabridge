package com.orangepi.wabridge

/** Um item da lista de quem pode ligar (contato ou grupo). */
data class ContactItem(
    val name: String,
    /** número (contato) ou JID (grupo) */
    val value: String,
    /** timestamp unix em segundos da última atividade (0 se não existe) */
    val lastActivity: Long = 0L,
)
