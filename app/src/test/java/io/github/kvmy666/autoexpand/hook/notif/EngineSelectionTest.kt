package io.github.kvmy666.autoexpand.hook.notif

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine-mode migration rules.
 *
 * The legacy boolean must keep deciding for installs that never wrote a mode (so an old-ROM
 * user who opted into v2 is not moved onto Auto), while an explicit mode always wins.
 */
class EngineSelectionTest {

    @Test
    fun explicitModeWinsOverLegacy() {
        assertEquals(EngineSelection.Mode.V1, EngineSelection.resolveMode("v1", true))
        assertEquals(EngineSelection.Mode.V2, EngineSelection.resolveMode("v2", false))
        assertEquals(EngineSelection.Mode.AUTO, EngineSelection.resolveMode("auto", true))
    }

    @Test
    fun legacyBooleanIsMigrationFallback() {
        assertEquals(EngineSelection.Mode.V2, EngineSelection.resolveMode(null, true))
        assertEquals(EngineSelection.Mode.V1, EngineSelection.resolveMode(null, false))
        assertEquals(EngineSelection.Mode.V2, EngineSelection.resolveMode("  ", true))
    }

    @Test
    fun absentEverythingMeansAuto() {
        assertEquals(EngineSelection.Mode.AUTO, EngineSelection.resolveMode(null, null))
        assertEquals(EngineSelection.Mode.AUTO, EngineSelection.resolveMode("", null))
    }

    @Test
    fun modeParsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(EngineSelection.Mode.V2, EngineSelection.resolveMode(" V2 ", null))
        assertEquals(EngineSelection.Mode.V1, EngineSelection.resolveMode("V1", null))
        assertEquals(EngineSelection.Mode.AUTO, EngineSelection.resolveMode("AUTO", null))
    }

    @Test
    fun unknownModeFallsBackToAutoNotToLegacy() {
        assertEquals(EngineSelection.Mode.AUTO, EngineSelection.resolveMode("engine3", null))
    }
}
