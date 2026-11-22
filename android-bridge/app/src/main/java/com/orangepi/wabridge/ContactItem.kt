package com.orangepi.wabridge

/** An item in the list of who can call (contact or group). */
data class ContactItem(
    val name: String,
    /** number (contact) or JID (group) */
    val value: String,
    /** unix timestamp in seconds of the last activity (0 if none) */
    val lastActivity: Long = 0L,
)
