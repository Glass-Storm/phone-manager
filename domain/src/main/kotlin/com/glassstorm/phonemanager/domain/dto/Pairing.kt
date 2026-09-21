package com.glassstorm.phonemanager.domain.dto

/** A single-use pairing window: a 6-digit PIN with an absolute expiry. Pure data. */
data class Pairing(
    val pin: String,
    val expiresAtMs: Long,
)
