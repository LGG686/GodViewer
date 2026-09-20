package com.godviewer.app.host.manage

import android.content.Context
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.shared.control.RuleCommand
import com.godviewer.app.shared.control.RuleCommandProtocol
import com.godviewer.app.shared.model.ViewRule

/** 对一条规则的一次管理操作。op 取值见 [RuleCommandProtocol]。 */
data class RuleEdit(
    val op: String,
    val text: String? = null,
    val visibility: Int? = null,
) {
    companion object {
        fun delete() = RuleEdit(RuleCommandProtocol.OP_DELETE)
        fun visibility(value: Int) = RuleEdit(RuleCommandProtocol.OP_VISIBILITY, visibility = value)
        fun text(value: String) = RuleEdit(RuleCommandProtocol.OP_TEXT, text = value)
        fun restore() = RuleEdit(RuleCommandProtocol.OP_RESTORE)
    }
}

data class ManageResult(
    /** 实际改动 / 删除的规则条数 */
    val changed: Int,
    /** 指令是否发得出去（false = 目标大概率没在跑，改动留在 pending 里等补发） */
    val dispatched: Boolean,
)

/**
 * 宿主侧规则管理的编排：改镜像 → 生成指令 → 下发目标。
 *
 * 两侧镜像 / 真相的关系：宿主这份是 best-effort 副本，所以
 * - 指令带 [RuleCommand.expectedTimestamp]，目标对不上就拒绝并把真相推回来纠偏
 * - 修改时间戳 [RuleCommand.stamp] 由宿主任命，两边同值，下一次操作才校验得过
 * - 镜像**乐观更新**：用户点了删除，列表就该少一条；目标没在线时改动排队等下一次前台
 * - 外来规则的 `imported` / `batchId` 一律不动 —— 宿主编的是列表，它没有替用户确认控件，
 *   不能把严格匹配降级成「信位置」
 */
internal object RuleMirrorManage {

    fun apply(
        context: Context,
        packageName: String,
        edits: List<Pair<ViewRule, RuleEdit>>,
    ): ManageResult {
        val app = context.applicationContext
        if (edits.isEmpty()) return ManageResult(0, false)
        val current = RuleMirror.loadRules(app, packageName)
        if (current.isEmpty()) return ManageResult(0, false)

        val byKey = LinkedHashMap<ViewRule.RuleKey, ViewRule>()
        for (rule in current) {
            byKey[rule.key()] = rule
        }
        val stamp = System.currentTimeMillis()
        val commands = ArrayList<RuleCommand>()
        var changed = 0

        for ((source, edit) in edits) {
            val key = source.key()
            val live = byKey[key] ?: continue
            val expected = live.timestamp
            when (edit.op) {
                RuleCommandProtocol.OP_DELETE -> {
                    byKey.remove(key)
                    commands.add(command(live, edit, expected, stamp))
                    changed++
                }
                RuleCommandProtocol.OP_VISIBILITY -> {
                    val visibility = edit.visibility ?: continue
                    val updated = live.copy()
                    updated.modified = live.modified.copy(visibility = visibility)
                    updated.changedVisibility = true
                    updated.timestamp = stamp
                    byKey[key] = updated
                    commands.add(command(live, edit, expected, stamp))
                    changed++
                }
                RuleCommandProtocol.OP_TEXT -> {
                    val text = edit.text ?: continue
                    val updated = live.copy()
                    updated.modified = live.modified.copy(text = text)
                    updated.changedText = true
                    updated.timestamp = stamp
                    byKey[key] = updated
                    commands.add(command(live, edit, expected, stamp))
                    changed++
                }
                RuleCommandProtocol.OP_RESTORE -> {
                    // 图片 URL 本来就不可还原，只退回 original 快照（含 scaleType）
                    val updated = live.copy()
                    updated.modified = live.original.copy()
                    updated.changedSize = false
                    updated.changedMargin = false
                    updated.changedPadding = false
                    updated.changedVisibility = false
                    updated.changedText = false
                    updated.changedImage = false
                    updated.timestamp = stamp
                    byKey[key] = updated
                    commands.add(command(live, edit, expected, stamp))
                    changed++
                }
            }
        }

        if (changed == 0) return ManageResult(0, false)
        RuleMirror.saveManagedRules(app, packageName, byKey.values.toList())
        val dispatched = RuleCommandDelivery.deliver(app, packageName, commands)
        return ManageResult(changed, dispatched)
    }

    /** 该包还有多少条改动没送到目标（UI 提示「待生效」用）。 */
    fun pendingCount(context: Context, packageName: String): Int =
        RuleCommandStore.pendingCount(context.applicationContext, packageName)

    private fun command(
        rule: ViewRule,
        edit: RuleEdit,
        expectedTimestamp: Long,
        stamp: Long,
    ): RuleCommand = RuleCommand(
        activityClass = rule.activityClass,
        viewClass = rule.viewClass,
        depth = rule.depth,
        op = edit.op,
        expectedTimestamp = expectedTimestamp,
        stamp = stamp,
        text = edit.text,
        visibility = edit.visibility,
    )
}
