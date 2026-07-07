package com.phillipchin.webrtctunnel.data

/**
 * Thrown (wrapped in a `Result.failure`) by [TunnelRepository.start] when native JNI
 * reports success but the post-start status verification could not confirm an
 * active-or-starting runtime state — either because the status refresh itself failed,
 * or because it returned a terminal/error state instead of an active one (P0-002).
 */
class StartStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
