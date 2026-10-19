package com.homepodplus.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Android 控制中心快捷开关磁贴 (Quick Settings Tile)
 * 允许用户在手机下拉通知栏/控制中心一键启动或停止投送。
 */
class HomePodTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val streaming = CastController.state.value.streaming
        if (streaming) {
            CastController.stop(this)
            updateTileState()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_start", true)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val streaming = CastController.state.value.streaming
        tile.state = if (streaming) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_name)
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_airplay)
        tile.contentDescription = "HomePod + 音频投送"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (streaming) "正在投送" else "未投送"
        }
        tile.updateTile()
    }
}
