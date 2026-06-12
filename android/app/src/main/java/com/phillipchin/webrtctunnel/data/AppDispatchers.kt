package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Single, test-overridable source of coroutine dispatchers for the app.
 *
 * ViewModels and controllers take dispatchers from [AppDependencies.dispatchers] rather
 * than referencing [Dispatchers] directly, so tests can substitute deterministic
 * dispatchers and so the `InjectDispatcher` rule is satisfied (the only `Dispatchers.*`
 * references live here as parameter defaults).
 */
data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)
