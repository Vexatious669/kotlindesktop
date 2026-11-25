package me.enivzoy.anilife.app.data

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerKeyBindingsTest {
    @Test
    fun defaultsCoverEveryActionAndThePlayPauseAlias() {
        assertEquals(PlayerAction.entries.size, DefaultPlayerKeyBindings.size)
        assertEquals(Key.Spacebar, DefaultPlayerKeyBindings[PlayerAction.PLAY_PAUSE])
        assertEquals(Key.I, DefaultPlayerKeyBindings[PlayerAction.TOGGLE_PIP])
        assertEquals(PlayerAction.PLAY_PAUSE, DefaultPlayerKeyBindings.playerActionFor(Key.K))
    }

    @Test
    fun serializedBindingsRoundTrip() {
        val rebound = rebindPlayerKey(DefaultPlayerKeyBindings, PlayerAction.MUTE, Key.Q).bindings

        assertEquals(rebound, parsePlayerKeyBindings(serializePlayerKeyBindings(rebound)))
    }

    @Test
    fun missingAndInvalidEntriesFallBackToDefaults() {
        val parsed = parsePlayerKeyBindings("MUTE:nope,PLAY_PAUSE:999999,UNKNOWN:42,SPEED_UP:88")

        assertEquals(Key.Spacebar, parsed[PlayerAction.PLAY_PAUSE])
        assertEquals(Key.M, parsed[PlayerAction.MUTE])
        assertEquals(Key.RightBracket, parsed[PlayerAction.SPEED_UP])
        assertEquals(Key.I, parsed[PlayerAction.TOGGLE_PIP])
        assertEquals(PlayerAction.PLAY_PAUSE, parsed.playerActionFor(Key.K))
    }

    @Test
    fun rebindingAUsedKeySwapsTheTwoActions() {
        val result = rebindPlayerKey(DefaultPlayerKeyBindings, PlayerAction.MUTE, Key.F)

        assertEquals(PlayerAction.FULLSCREEN, result.conflictWith)
        assertEquals(Key.F, result.bindings[PlayerAction.MUTE])
        assertEquals(Key.M, result.bindings[PlayerAction.FULLSCREEN])
    }

    @Test
    fun anUnassignedKeyKeepsTheDefaultBinding() {
        val result = rebindPlayerKey(DefaultPlayerKeyBindings, PlayerAction.MUTE, Key.Unknown)

        assertEquals(Key.M, result.bindings[PlayerAction.MUTE])
    }
}
