package com.godviewer.app.host.manage

import android.content.Context
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.HostControlBridge
import com.godviewer.app.shared.control.RuleCommand
import com.google.gson.Gson

/**
 * 宿主 → 目标：下发规则管理指令（删除 / 显示隐藏 / 改文字 / 还原）。
 *
 * 和备份导入同一套路数：先落 pending（[RuleCommandStore]），再广播；目标在线立刻生效，
 * 不在线则等它下次上报前台时由 [flush] 补发。分批是为了不撞 Binder 事务上限。
 */
internal object RuleCommandDelivery {
    private const val TAG = "Manage"
    private const val MAX_BATCH_BYTES = 200 * 1024
    private val gson = Gson()

    fun deliver(context: Context, packageName: String, commands: List<RuleCommand>): Boolean {
        if (commands.isEmpty()) return false
        val queued = RuleCommandStore.enqueue(context, packageName, commands)
        return sendBatches(context, packageName, queued)
    }

    /** 目标已上报前台：补发 pending 指令（幂等，重复下发无害）。 */
    fun flush(context: Context, packageName: String): Boolean {
        val pending = RuleCommandStore.pending(context, packageName)
        if (pending.isEmpty()) return false
        val sent = sendBatches(context, packageName, pending)
        if (sent) {
            RuleCommandStore.clear(context, packageName)
        }
        return sent
    }

    private fun sendBatches(
        context: Context,
        packageName: String,
        commands: List<RuleCommand>,
    ): Boolean {
        if (commands.isEmpty()) return false
        var sent = false
        var batch = ArrayList<RuleCommand>()
        var bytes = 0

        fun flushBatch() {
            if (batch.isEmpty()) return
            val json = gson.toJson(batch)
            if (HostControlBridge.dispatchRuleCommands(context, packageName, json)) {
                sent = true
            }
            batch = ArrayList()
            bytes = 0
        }

        for (cmd in commands) {
            val size = runCatching { gson.toJson(cmd).toByteArray(Charsets.UTF_8).size }
                .getOrDefault(0)
            if (bytes + size > MAX_BATCH_BYTES && batch.isNotEmpty()) {
                flushBatch()
            }
            batch.add(cmd)
            bytes += size
        }
        flushBatch()
        GvLog.d(TAG, "rule commands dispatched: $packageName count=${commands.size}")
        return sent
    }
}
