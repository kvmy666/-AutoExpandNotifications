package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Quick Settings tile that triggers a screen capture.
 * Users add it from the QS editor (swipe down → edit → drag "Screen Snapper" tile).
 */
class SnapperTileService : TileService() {

    override fun onTileAdded()       { syncState() }
    override fun onStartListening()  { syncState() }
    override fun onStopListening()   {}

    /**
     * The tile is a momentary action, not a toggle, so it never shows as "on". It does
     * grey out when the Screen Snapper master switch is off, which is the only state a
     * user can act on - a live tile that silently does nothing is worse than a dim one.
     */
    private fun syncState() {
        val masterOn = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("enable_snapper_entirely", true)
        qsTile?.apply {
            state = if (masterOn) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        // Master switch: when Screen Snapper is disabled the tile is inert. Gate here so we
        // never start the foreground service while disabled.
        val masterOn = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("enable_snapper_entirely", true)
        if (!masterOn) {
            Toast.makeText(this, "Screen Snapper is turned off", Toast.LENGTH_SHORT).show()
            return
        }
        // EXTRA_QS_TRIGGERED is what tells the service to wait for this panel to finish
        // collapsing before it grabs the frame. Without it the snap is of Quick Settings.
        val svc = Intent(this, SnapperService::class.java).apply {
            action = SnapperService.ACTION_CAPTURE
            putExtra(SnapperService.EXTRA_QS_TRIGGERED, true)
        }
        startForegroundService(svc)
    }
}
