package com.example.aidetest

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tile cepat: membuka layar Keuangan GITLS (sebelumnya hanya mengubah pref yang tidak dipakai). */
@TargetApi(Build.VERSION_CODES.N)
class MyToolsQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.label = "GITLS Keuangan"; it.updateTile() }
    }

    // API 24-33 hanya punya versi Intent; versi PendingIntent baru ada di API 34 (dipakai di cabang atas).
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("open_finance", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 102, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
