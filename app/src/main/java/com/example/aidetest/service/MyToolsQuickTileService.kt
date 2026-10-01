package com.example.aidetest

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class MyToolsQuickTileService : TileService() {
    override fun onStartListening(){super.onStartListening();sync()}
    override fun onClick(){super.onClick();val p=getSharedPreferences("mytools_prefs",MODE_PRIVATE);val enabled=!p.getBoolean("finance_reader_enabled",true);p.edit().putBoolean("finance_reader_enabled",enabled).apply();sync()}
    private fun sync(){val tile=qsTile?:return; val enabled=getSharedPreferences("mytools_prefs",MODE_PRIVATE).getBoolean("finance_reader_enabled",true); tile.state=if(enabled) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE; tile.label="Pencatatan Keuangan";tile.updateTile()}
}
