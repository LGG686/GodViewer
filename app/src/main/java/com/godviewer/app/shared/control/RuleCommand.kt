package com.godviewer.app.shared.control

import com.google.gson.annotations.SerializedName

/**
 * 规则管理指令（宿主 → 目标）。
 *
 * 与备份导入不同：管理指令只描述「对某条已存在规则做什么」，不搬运整条规则。
 * 目标是 autoritative 一侧，宿主手里那份只是 best-effort 镜像，所以每条指令都要带
 * [RuleCommand.expectedTimestamp] —— 目标发现本地规则时间戳对不上就拒绝执行并回推真相，
 * 避免宿主编删 rash 改错一条。
 *
 * 字段一律可空：Gson 走 Unsafe 实例化，手工构造的坏广播不可能保证非空。
 */
object RuleCommandProtocol {
    /** 删除该规则，先把视图还原成原始样子。 */
    const val OP_DELETE = "delete"

    /** 显示 / 隐藏该控件（改 modified.visibility + changedVisibility）。 */
    const val OP_VISIBILITY = "visibility"

    /** 改文字（改 modified.text + changedText）。 */
    const val OP_TEXT = "text"

    /** 还原：把 modified 退回 original 并清掉所有 changed* 标志（规则还在，只是不再生效）。 */
    const val OP_RESTORE = "restore"
}

/**
 * @param expectedTimestamp 宿主认为这条规则当前的时间戳；目标对不上就跳过（0 表示不校验）
 * @param stamp 本次修改后双方统一使用的时间戳。由宿主任命而不是各自 `System.currentTimeMillis()`，
 *   否则下一次指令的 [expectedTimestamp] 会与目标侧的时区 / 时钟抖动错位，导致自己改自己失败
 */
data class RuleCommand(
    @SerializedName("activity_class") val activityClass: String? = null,
    @SerializedName("view_class") val viewClass: String? = null,
    @SerializedName("depth") val depth: List<Int>? = null,
    @SerializedName("op") val op: String? = null,
    @SerializedName("expected_timestamp") val expectedTimestamp: Long = 0L,
    @SerializedName("stamp") val stamp: Long = 0L,
    @SerializedName("text") val text: String? = null,
    @SerializedName("visibility") val visibility: Int? = null,
)
