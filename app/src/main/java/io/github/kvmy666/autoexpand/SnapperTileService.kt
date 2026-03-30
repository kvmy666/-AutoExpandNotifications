package io.github.kvmy666.autoexpand

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

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
        val svc = Intent(this, SnapperService::class.java).apply {
            action = SnapperService.ACTION_CAPTURE
        }
        startForegroundService(svc)
    }
}
