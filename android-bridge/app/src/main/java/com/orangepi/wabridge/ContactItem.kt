package com.orangepi.wabridge

/** Um item da lista de quem pode ligar (contato ou grupo). */
data class ContactItem(
    val name: String,
    /** número (contato) ou JID (grupo) */
    val value: String,
)
