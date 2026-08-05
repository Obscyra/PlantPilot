package com.plantpilot.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Shared connection-state semantics so every ViewModel and UI layer derives
 * "can I send a command?" and "can I show last-known data?" from the live
 * connection state the same way.
 */
object ConnectionStateHelper {

    /** Strict: only true when WebSocket is confirmed alive. Guards outbound commands. */
    fun canSendCommands(state: ConnectionState): Boolean =
        state == ConnectionState.Connected

    /** Permissive: true when Connected or Reconnecting. Fine for displaying
     *  last-known data (telemetry, pump states) — the data isn't stale-looking,
     *  just not live. */
    fun canDisplayLastKnownData(state: ConnectionState): Boolean =
        state == ConnectionState.Connected || state == ConnectionState.Reconnecting

    /**
     * Delayed-reveal variant of [real] for display-layer consumers only.
     * Transient states (Connecting, Reconnecting) are held back briefly so
     * short-lived transitions (e.g. a 100ms handshake or a quick retry) don't
     * flash in the UI.
     */
    fun debouncedConnectionState(
        real: StateFlow<ConnectionState>,
        scope: CoroutineScope,
        wateringInProgress: StateFlow<Boolean>? = null,
    ): StateFlow<ConnectionState> {
        val display = MutableStateFlow(real.value)
        var revealJob: Job? = null
        scope.launch {
            real.collect { state ->
                when (state) {
                    ConnectionState.Connecting -> {
                        revealJob?.cancel()
                        revealJob = scope.launch {
                            delay(400) // Debounce initial connection attempt
                            display.value = ConnectionState.Connecting
                        }
                    }
                    ConnectionState.Reconnecting -> {
                        revealJob?.cancel()
                        revealJob = scope.launch {
                            val debounceMs = if (wateringInProgress?.value == true) 15000L else 600L
                            delay(debounceMs) // Debounce reconnection attempts
                            display.value = ConnectionState.Reconnecting
                        }
                    }
                    else -> {
                        revealJob?.cancel()
                        revealJob = null
                        display.value = state
                    }
                }
            }
        }
        return display.asStateFlow()
    }
}
