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

    private fun syncState() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
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
        val svc = Intent(this, SnapperService::class.java).apply {
            action = SnapperService.ACTION_CAPTURE
            putExtra(SnapperService.EXTRA_QS_TRIGGERED, true)
        }
        startForegroundService(svc)
    }
}
