package com.godviewer.app.host.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.godviewer.app.R
import com.godviewer.app.host.entry.EntryControlUi
import com.godviewer.app.shared.entry.EntryMode

/**
 * 快捷设置磁贴：编辑模式入口的一键开关。
 *
 * - 开 → 关：隐藏入口通知（所有活着的目标进程一并退出编辑模式）
 * - 关 → 开：按最后一个可见入口恢复（目标应用通知 / 本体通知），
 *   并默认同时进入最近一个目标应用的编辑模式（设置里可关）
 *
 * 磁贴只存在于 API 24+，低版本设备系统不会绑定这个 service。
 */
@RequiresApi(Build.VERSION_CODES.N)
class EntryModeTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        val hidden = EntryMode.current(this) == EntryMode.NONE
        if (hidden) {
            val result = EntryControlUi.restoreEntry(this)
            updateTile()
            val msg = when {
                result.fellBackToTarget -> R.string.qs_tile_toast_host_no_permission
                result.enterEditDisabledByPrefs -> R.string.qs_tile_toast_restored_no_edit
                !result.editDispatched -> R.string.qs_tile_toast_no_target
                else -> R.string.qs_tile_toast_restored
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else {
            EntryControlUi.hideEntry(this)
            updateTile()
            Toast.makeText(this, R.string.qs_tile_toast_hidden, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val mode = EntryMode.current(this)
        val hidden = mode == EntryMode.NONE
        tile.state = if (hidden) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        runCatching {
            tile.icon = Icon.createWithResource(
                this,
                if (hidden) R.drawable.ic_qs_entry_off else R.drawable.ic_qs_entry,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            applySubtitle(tile, mode)
        }
        tile.updateTile()
    }

    /** 副标题显示当前入口形态（本体通知 / 目标应用通知 / 已隐藏） */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun applySubtitle(tile: Tile, mode: String) {
        tile.subtitle = getString(
            when (mode) {
                EntryMode.HOST -> R.string.qs_tile_subtitle_host
                EntryMode.NONE -> R.string.qs_tile_subtitle_none
                else -> R.string.qs_tile_subtitle_target
            },
        )
    }
}
