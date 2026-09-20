package com.godviewer.app.target.rule

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.control.RuleCommand
import com.godviewer.app.shared.control.RuleCommandProtocol
import com.godviewer.app.shared.model.ViewRule
import com.godviewer.app.target.mirror.ThumbnailSync
import com.godviewer.app.target.rule.findViewBestMatch
import com.godviewer.app.target.rule.getAttachedActivityFromView
import com.godviewer.app.target.rule.getViewHierarchyDepth
import com.godviewer.app.target.rule.isInActivityWindow
import com.godviewer.app.target.rule.resourceNameOf
import com.godviewer.app.target.rule.versionCode

/**
 * 持久化规则管理（单例，运行在被注入的目标进程内）— 薄 facade + 编排。
 *
 * 实现按职责拆到：
 * - [RuleStore] — JSON 落盘
 * - [ViewRuleThumbnails] — 缩略图内存/磁盘
 * - [ViewRuleApplier] — 快照应用到 View / 还原
 *
 * 对外 API 保持不变。第二阶段迁包时整体预期落入 `target`。
 */
object ViewRuleManager {

    private const val TAG = "Rule"

    /** 撤销栈深度上限（内存态，进程重启即清空） */
    private const val MAX_UNDO_STEPS = 10

    @Volatile
    private var initialized = false
    private var store: RuleStore? = null

    /** 目标进程 context（缩略图回补 / 镜像同步需要，非 UI 用途）。 */
    private var appContext: Context? = null

    @Volatile
    private var rules: List<ViewRule> = emptyList()

    /**
     * 内存撤销栈：每次 [saveRule] / [deleteRule] 前压入当前规则列表，
     * [undoLastOperation] 出栈恢复上一步状态（规则数据 + 持久化 + 视图回放）。
     */
    private val undoStack = ArrayDeque<List<ViewRule>>()

    fun init(application: Application) {
        if (initialized) {
            return
        }
        // 先置位，避免 init 中异常导致反复进入；失败时 rules 保持空列表
        initialized = true
        runCatching {
            appContext = application.applicationContext
            ViewRuleThumbnails.attach(application.applicationContext)
            val ruleStore = RuleStore(application.applicationContext)
            store = ruleStore
            rules = ruleStore.load()
            GvLog.d(TAG, "rules loaded: ${rules.size}")
        }.onFailure {
            rules = emptyList()
            GvLog.e(TAG, "ViewRuleManager.init failed; continue with empty rules", it)
        }
    }

    /** 某 Activity 的全部规则（重放时使用） */
    fun rulesForActivity(activityClass: String): List<ViewRule> =
        rules.filter { it.activityClass == activityClass }

    /** 查找已存在的规则（对话框打开时判断是否已有规则） */
    fun findRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        val key = ViewRule.RuleKey(
            activity.componentName.className,
            getViewHierarchyDepth(view),
            view.javaClass.name
        )
        return rules.firstOrNull { it.key() == key }
    }

    /**
     * 为视图创建一个规则外壳（原始值 = 当前值，修改值 = 当前值，changed* 全 false）。
     * 视图不在 Activity 窗口（如对话框 / Popup 内部）或找不到 Activity 时返回 null。
     */
    fun createRule(view: View): ViewRule? {
        val activity = getAttachedActivityFromView(view) ?: return null
        if (!isInActivityWindow(view, activity)) {
            return null
        }
        val snapshot = ViewRuleApplier.captureSnapshot(view)
        val rule = ViewRule(
            packageName = activity.packageName,
            matchVersionCode = versionCode(activity),
            activityClass = activity.componentName.className,
            viewClass = view.javaClass.name,
            depth = getViewHierarchyDepth(view),
            resourceName = resourceNameOf(view),
            text = (view as? TextView)?.text?.toString(),
            description = view.contentDescription?.toString(),
            original = snapshot,
            modified = snapshot,
            timestamp = System.currentTimeMillis()
        )
        // 视图此刻仍可见，截取缩略图供规则管理列表使用（与编辑弹窗预览一致），并持久化
        ViewRuleThumbnails.capture(view, rule)
        return rule
    }

    fun thumbnailFor(rule: ViewRule): Bitmap? = ViewRuleThumbnails.thumbnailFor(rule)

    /** @return 本次是否新抓到缩略图 */
    fun captureThumbnail(view: View, rule: ViewRule): Boolean =
        ViewRuleThumbnails.capture(view, rule)

    /** 保存（或更新）一条规则 */
    fun saveRule(rule: ViewRule) {
        pushUndoState()
        rule.timestamp = System.currentTimeMillis()
        // 在本机重新保存 = 用户已确认这条规则对应的控件：解除外来规则的严格匹配，
        // 同时退出导入批次，避免「撤销本次导入」误删用户确认过的规则
        rule.imported = false
        rule.batchId = null
        ViewRuleApplier.forgetAppliedImage(rule)
        val index = rules.indexOfFirst { it.key() == rule.key() }
        rules = if (index >= 0) {
            rules.toMutableList().apply { set(index, rule) }
        } else {
            rules + rule
        }
        store?.save(rules)
        GvLog.d(TAG, "rule saved: $rule")
    }

    /** 删除一条规则 */
    fun deleteRule(rule: ViewRule) {
        pushUndoState()
        ViewRuleApplier.forgetAppliedImage(rule)
        ViewRuleThumbnails.remove(rule)
        rules = rules.filterNot { it.key() == rule.key() }
        store?.save(rules)
        // 删除后回推一次全量 key 列表：宿主从镜像里清掉已删规则的缩略图
        appContext?.let { ThumbnailSync.syncKeySetAfterDelete(it, rules) }
        GvLog.d(TAG, "rule deleted: ${rule.key()}")
    }

    /**
     * 删除一条规则；先在给定各 Activity 中还原该规则关联的视图（找不到则跳过）。
     */
    fun deleteRule(rule: ViewRule, restoreIn: Collection<Activity>) {
        for (activity in restoreIn) {
            findViewBestMatch(activity, rule)?.let { view ->
                ViewRuleApplier.restoreView(view, rule)
            }
        }
        deleteRule(rule)
    }

    /**
     * 备份导入：与现有规则按键（activityClass + viewClass + depth）合并，
     * 同键取 `timestamp` 较新的那条，本地独有的规则保留。
     *
     * 导入的规则统一置 [ViewRule.imported] 并打上 [batchId]：原设备的布局未必与本机
     * 一致，匹配时只认身份锚点（见 [findViewBestMatch]），且整批可撤销。
     *
     * @return (新增条数, 覆盖条数)
     */
    fun importRules(incoming: List<ViewRule>, batchId: String? = null): Pair<Int, Int> {
        if (incoming.isEmpty()) return 0 to 0
        pushUndoState()
        val merged = rules.toMutableList()
        var added = 0
        var replaced = 0
        for (rule in incoming) {
            rule.imported = true
            rule.batchId = batchId
            val index = merged.indexOfFirst { it.key() == rule.key() }
            if (index < 0) {
                merged.add(rule)
                added++
            } else if (rule.timestamp >= merged[index].timestamp) {
                merged[index] = rule
                replaced++
            }
        }
        rules = merged
        store?.save(rules)
        GvLog.i(TAG, "rules imported: added=$added replaced=$replaced total=${rules.size}")
        return added to replaced
    }

    /**
     * 撤销一整批导入：移除该批次全部规则并持久化（宿主侧镜像由宿主自行重写）。
     *
     * [restoreIn] 里的 Activity 会先把这些规则改过的视图还原（被隐藏的重新出现）。
     *
     * @return 移除的条数
     */
    fun undoImport(batchId: String?, restoreIn: Collection<Activity> = emptyList()): Int {
        if (batchId.isNullOrBlank()) return 0
        val batch = rules.filter { it.batchId == batchId }
        if (batch.isEmpty()) return 0
        pushUndoState()
        for (rule in batch) {
            for (activity in restoreIn) {
                runCatching {
                    findViewBestMatch(activity, rule)?.let { ViewRuleApplier.restoreView(it, rule) }
                }.onFailure {
                    GvLog.w(TAG, "restore on undo import failed key=${rule.key()}", it)
                }
            }
            ViewRuleApplier.forgetAppliedImage(rule)
            ViewRuleThumbnails.remove(rule)
        }
        rules = rules.filterNot { it.batchId == batchId }
        store?.save(rules)
        // 回推全量 key 列表：宿主从镜像里清掉这批规则的缩略图
        appContext?.let { ThumbnailSync.syncKeySetAfterDelete(it, rules) }
        GvLog.i(TAG, "import undone: batch=$batchId removed=${batch.size}")
        return batch.size
    }

    /** 当前全部规则（规则管理列表使用） */
    fun allRules(): List<ViewRule> = rules

    /**
     * 执行宿主下发的管理指令（删除 / 显示隐藏 / 改文字 / 还原）。
     *
     * 与本机编辑的区别有两点，都不能省：
     * - **时间戳守卫**：宿主手里是镜像，可能过期。[RuleCommand.expectedTimestamp] 对不上就跳过，
     *   并在没有任何改动时把本地真相推回去，让宿主的 UI 自己纠偏。
     * - **保留 [ViewRule.imported] / [ViewRule.batchId]**：宿主编的是列表不是界面，它没有替用户
     *   「确认过这个控件」，所以不能像 [saveRule] 那样把外来规则降级成本机规则。
     *
     * @return 各计数：applied 实际改动、stale 时间戳不匹配、missing 本地已无此规则
     */
    fun applyHostCommands(
        commands: List<RuleCommand>,
        restoreIn: Collection<Activity>,
    ): RuleCommandResult {
        if (commands.isEmpty()) return RuleCommandResult(0, 0, 0)
        val before = rules
        var working = rules
        var applied = 0
        var stale = 0
        var missing = 0
        val appliedKeys = LinkedHashSet<ViewRule.RuleKey>()
        val now = System.currentTimeMillis()

        for (cmd in commands) {
            val op = cmd.op?.takeIf { it.isNotBlank() } ?: continue
            val key = cmd.ruleKey() ?: continue
            val rule = working.firstOrNull { it.key() == key }
            if (rule == null) {
                missing++
                continue
            }
            val expected = cmd.expectedTimestamp
            if (expected > 0L && rule.timestamp != expected) {
                stale++
                continue
            }
            when (op) {
                RuleCommandProtocol.OP_DELETE -> {
                    for (activity in restoreIn) {
                        runCatching {
                            findViewBestMatch(activity, rule)?.let {
                                ViewRuleApplier.restoreView(it, rule)
                            }
                        }.onFailure {
                            GvLog.w(TAG, "restore before host delete failed key=$key", it)
                        }
                    }
                    ViewRuleApplier.forgetAppliedImage(rule)
                    ViewRuleThumbnails.remove(rule)
                    working = working.filterNot { it.key() == key }
                    applied++
                }
                else -> {
                    val updated = rule.copy()
                    val changed = when (op) {
                        RuleCommandProtocol.OP_VISIBILITY -> {
                            val target = cmd.visibility ?: continue
                            updated.modified = rule.modified.copy(visibility = target)
                            updated.changedVisibility = true
                            true
                        }
                        RuleCommandProtocol.OP_TEXT -> {
                            val target = cmd.text ?: continue
                            updated.modified = rule.modified.copy(text = target)
                            updated.changedText = true
                            true
                        }
                        RuleCommandProtocol.OP_RESTORE -> {
                            for (activity in restoreIn) {
                                runCatching {
                                    findViewBestMatch(activity, rule)?.let {
                                        ViewRuleApplier.restoreView(it, rule)
                                    }
                                }.onFailure {
                                    GvLog.w(TAG, "host restore view failed key=$key", it)
                                }
                            }
                            ViewRuleApplier.forgetAppliedImage(updated)
                            // 图片 URL 本来就不可还原，只退回 original 快照（含 scaleType）
                            updated.modified = rule.original.copy()
                            updated.changedSize = false
                            updated.changedMargin = false
                            updated.changedPadding = false
                            updated.changedVisibility = false
                            updated.changedText = false
                            updated.changedImage = false
                            true
                        }
                        else -> false
                    }
                    if (!changed) continue
                    updated.timestamp = if (cmd.stamp > 0L) cmd.stamp else now
                    working = working.map { if (it.key() == key) updated else it }
                    appliedKeys.add(key)
                    applied++
                }
            }
        }

        if (applied == 0) {
            // 全是过期指令：把本地真相推回去，宿主的列表会自己纠偏，不用等下一次 replay
            if (stale > 0 || missing > 0) pushCurrentToHost()
            return RuleCommandResult(applied, stale, missing, emptySet())
        }

        pushUndoState(before)
        rules = working
        store?.save(rules)
        // 回推全量 key 列表：删除过的规则缩略图由宿主清掉（宿主也会自己清理，双保险）
        appContext?.let { ThumbnailSync.syncKeySetAfterDelete(it, rules) }
        GvLog.i(
            TAG,
            "host commands applied=$applied stale=$stale missing=$missing total=${rules.size}",
        )
        return RuleCommandResult(applied, stale, missing, appliedKeys)
    }

    /**
     * 重抓规则的缩略图（先丢旧图再抓），让宿主列表看到**改完之后**的样子。
     *
     * 隐藏类规则跳过：视图已经是 GONE，[ViewSnapshot] 抓不到东西，旧图（控件原本的样子）
     * 反而更有用。抓到的图由调用方 [ThumbnailSync.flushNewThumbnails] 立刻回推。
     *
     * @return 本次新抓到的张数
     */
    fun refreshThumbnails(keys: Collection<ViewRule.RuleKey>, activities: Collection<Activity>): Int {
        if (keys.isEmpty() || activities.isEmpty()) return 0
        var captured = 0
        for (key in keys) {
            val rule = rules.firstOrNull { it.key() == key } ?: continue
            if (rule.modified.visibility == View.GONE) continue
            for (activity in activities) {
                val view = runCatching { findViewBestMatch(activity, rule) }.getOrNull()
                    ?: continue
                ViewRuleThumbnails.remove(rule)
                if (ViewRuleThumbnails.capture(view, rule)) captured++
                break
            }
        }
        return captured
    }

    /** 把当前规则推给宿主镜像（宿主 UI 与本地不一致时的纠偏手段）。 */
    private fun pushCurrentToHost() {
        val ctx = appContext ?: return
        runCatching {
            com.godviewer.app.data.RuleMirror.pushFromTarget(
                ctx,
                rules.firstOrNull()?.packageName ?: ctx.packageName,
                rules,
            )
        }.onFailure {
            GvLog.w(TAG, "push truth to host failed", it)
        }
    }

    /** 一次管理指令批次的执行结果。 */
    data class RuleCommandResult(
        val applied: Int,
        val stale: Int,
        val missing: Int,
        /** 实际改动过的规则键（已删除的不在内），调用方据此重抓缩略图。 */
        val appliedKeys: Set<ViewRule.RuleKey> = emptySet(),
    )

    private fun RuleCommand.ruleKey(): ViewRule.RuleKey? {
        val activity = activityClass?.takeIf { it.isNotBlank() } ?: return null
        val view = viewClass?.takeIf { it.isNotBlank() } ?: return null
        val path = depth?.takeIf { it.isNotEmpty() } ?: return null
        return ViewRule.RuleKey(activity, path, view)
    }

    /** 是否有可撤销的操作 */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    private fun pushUndoState(state: List<ViewRule> = rules) {
        undoStack.addLast(state)
        if (undoStack.size > MAX_UNDO_STEPS) {
            undoStack.removeFirst()
        }
    }

    /**
     * 撤销上一个规则操作：恢复上一步的规则列表并持久化；[activity] 非空时
     * 对当前界面的视图做精确回放。
     *
     * @return 是否成功撤销（撤销栈为空时返回 false）
     */
    fun undoLastOperation(activity: Activity?): Boolean {
        if (undoStack.isEmpty()) {
            return false
        }
        val previous = undoStack.removeLast()
        val current = rules
        rules = previous
        ViewRuleApplier.clearAppliedImages()
        store?.save(rules)
        activity?.let { act ->
            for (rule in previous) {
                findViewBestMatch(act, rule)?.let { view ->
                    ViewRuleApplier.restoreView(view, rule)
                    ViewRuleApplier.applyRuleToView(view, rule)
                }
            }
            for (rule in current) {
                if (previous.none { it.key() == rule.key() }) {
                    findViewBestMatch(act, rule)?.let { view ->
                        ViewRuleApplier.restoreView(view, rule)
                    }
                }
            }
        }
        GvLog.d(TAG, "rule undone: ${previous.size} rules restored")
        return true
    }

    fun applyRuleToView(view: View, rule: ViewRule): Boolean =
        ViewRuleApplier.applyRuleToView(view, rule)

    fun restoreView(view: View, rule: ViewRule) {
        ViewRuleApplier.restoreView(view, rule)
    }
}
