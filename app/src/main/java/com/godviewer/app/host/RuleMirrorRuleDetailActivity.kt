package com.godviewer.app.host

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.ActivityRuleMirrorRuleDetailBinding
import com.godviewer.app.host.manage.RuleEdit
import com.godviewer.app.host.manage.RuleMirrorManage
import com.godviewer.app.host.ui.HostActivity
import com.godviewer.app.shared.model.ViewAttrSnapshot
import com.godviewer.app.shared.model.ViewRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Host-side rule detail with the four management operations (read-only before v4.3.6).
 *
 * 每一步都走 [RuleMirrorManage]：先把宿主镜像乐观更新，再把一条小指令发给目标应用；
 * 目标不在前台时进 pending 队列，所以 toast 会区分「已生效」和「待生效」两种说法。
 */
class RuleMirrorRuleDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorRuleDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String
    private var ruleKey: ViewRule.RuleKey? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val rule = resolveRule(intent.getIntExtra(EXTRA_RULE_INDEX, -1))?.also {
            ruleKey = it.key()
        }
        if (rule == null) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnToggleVisibility.setOnClickListener {
            currentRule()?.let { r ->
                val hidden = r.changedVisibility && r.modified.visibility == View.GONE
                apply(r, RuleEdit.visibility(if (hidden) View.VISIBLE else View.GONE))
            }
        }
        binding.btnEditText.setOnClickListener { askText(currentRule() ?: return@setOnClickListener) }
        binding.btnRestore.setOnClickListener {
            currentRule()?.let { r -> apply(r, RuleEdit.restore()) }
        }
        binding.btnDelete.setOnClickListener {
            currentRule()?.let { r ->
                AlertDialog.Builder(this)
                    .setMessage(R.string.delete_rule_confirm_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val result = RuleMirrorManage.apply(
                            this,
                            packageName,
                            listOf(r to RuleEdit.delete()),
                        )
                        if (result.changed == 0) {
                            Toast.makeText(this, R.string.rules_manage_nothing, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                this,
                                if (result.dispatched) {
                                    getString(R.string.rules_manage_deleted, result.changed)
                                } else {
                                    getString(R.string.rules_manage_deleted_queued, result.changed)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        finish()
                    }
                    .show()
            }
        }

        bind(rule)
    }

    override fun onResume() {
        super.onResume()
        val key = ruleKey ?: return
        val fresh = RuleMirror.loadRules(this, packageName).firstOrNull { it.key() == key }
        if (fresh == null) {
            finish()
        } else {
            bind(fresh)
        }
    }

    private fun resolveRule(index: Int): ViewRule? {
        val rules = RuleMirror.loadRules(this, packageName)
            .sortedByDescending { it.timestamp }
        return rules.getOrNull(index)
            ?: intent.getStringExtra(EXTRA_RULE_KEY_ACTIVITY)?.let { activity ->
                val depth = intent.getIntArrayExtra(EXTRA_RULE_KEY_DEPTH)?.toList()
                val viewClass = intent.getStringExtra(EXTRA_RULE_KEY_VIEW)
                if (depth != null && viewClass != null) {
                    rules.firstOrNull {
                        it.activityClass == activity && it.viewClass == viewClass && it.depth == depth
                    }
                } else {
                    null
                }
            }
    }

    private fun currentRule(): ViewRule? {
        val key = ruleKey ?: return null
        return RuleMirror.loadRules(this, packageName).firstOrNull { it.key() == key }
    }

    private fun bind(rule: ViewRule) {
        val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
        binding.toolbar.title =
            (if (hidden) getString(R.string.hidden_status) + " · " else "") +
            rule.viewClass.substringAfterLast('.')

        val thumb = RuleMirror.loadThumbnail(this, packageName, rule)
        if (thumb != null) {
            binding.detailThumb.setImageBitmap(thumb)
            binding.detailThumb.isVisible = true
        } else {
            binding.detailThumb.isVisible = false
        }
        binding.detailText.text = buildDetail(rule)

        // 按钮文案跟着当前状态走：现在是隐藏的就显示「显示」，反之亦然
        binding.btnToggleVisibility.text =
            if (hidden) getString(R.string.rule_action_show) else getString(R.string.rule_action_hide)
        binding.btnEditText.isVisible = true
        binding.btnRestore.isEnabled = rule.changedSize || rule.changedMargin ||
            rule.changedPadding || rule.changedVisibility || rule.changedText || rule.changedImage
    }

    private fun askText(rule: ViewRule) {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(rule.modified.text ?: rule.text ?: "")
            setSingleLine(false)
            maxLines = 4
            setSelection(text.length)
        }
        val frame = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rule_edit_text_title)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                apply(rule, RuleEdit.text(edit.text?.toString().orEmpty()))
            }
            .show()
    }

    private fun apply(rule: ViewRule, edit: RuleEdit) {
        val result = RuleMirrorManage.apply(this, packageName, listOf(rule to edit))
        when {
            result.changed == 0 ->
                Toast.makeText(this, R.string.rules_manage_nothing, Toast.LENGTH_SHORT).show()
            result.dispatched ->
                Toast.makeText(
                    this,
                    getString(R.string.rules_manage_applied, result.changed),
                    Toast.LENGTH_SHORT,
                ).show()
            else ->
                Toast.makeText(
                    this,
                    getString(R.string.rules_manage_queued, result.changed),
                    Toast.LENGTH_SHORT,
                ).show()
        }
        if (result.changed > 0) {
            currentRule()?.let { bind(it) }
        }
    }

    private fun buildDetail(rule: ViewRule): String {
        val lines = ArrayList<String>()
        lines += rule.activityClass.substringAfterLast('.') + " · " + rule.packageName
        rule.resourceName?.let { lines += "res: $it" }
        rule.text?.let { lines += "text: $it" }
        rule.description?.let { lines += "desc: $it" }
        lines += "depth: " + rule.depth.joinToString("/")
        lines += ""
        lines += getString(R.string.modify_time) + ": " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(rule.timestamp))
        lines += ""
        lines += getString(R.string.modified_values) + ":"
        lines += buildProps(rule, rule.modified).ifEmpty { "  —" }
        lines += ""
        lines += getString(R.string.original_values) + ":"
        lines += buildProps(rule, rule.original).ifEmpty { "  —" }
        return lines.joinToString("\n")
    }

    private fun buildProps(rule: ViewRule, snapshot: ViewAttrSnapshot): String {
        val lines = ArrayList<String>()
        if (rule.changedSize) {
            lines += "  ${getString(R.string.size)}: " +
                "${formatDim(snapshot.width)} × ${formatDim(snapshot.height)}"
        }
        if (rule.changedMargin) {
            lines += "  ${getString(R.string.margin)}: ${snapshot.marginLeft}, " +
                "${snapshot.marginTop}, ${snapshot.marginRight}, ${snapshot.marginBottom}"
        }
        if (rule.changedPadding) {
            lines += "  ${getString(R.string.padding)}: ${snapshot.paddingLeft}, " +
                "${snapshot.paddingTop}, ${snapshot.paddingRight}, ${snapshot.paddingBottom}"
        }
        if (rule.changedVisibility) {
            val value = if (snapshot.visibility == View.GONE) {
                getString(R.string.hidden_status)
            } else {
                getString(R.string.visible)
            }
            lines += "  ${getString(R.string.visibility)}: $value"
        }
        if (rule.changedText) {
            lines += "  ${getString(R.string.text_content)}: ${snapshot.text ?: ""}"
        }
        if (rule.changedImage) {
            lines += "  ${getString(R.string.image_url)}: ${snapshot.imageUrl ?: ""}"
            snapshot.scaleType?.let {
                lines += "  ${getString(R.string.scale_type)}: $it"
            }
        }
        return lines.joinToString("\n")
    }

    private fun formatDim(value: Int): String = when (value) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "match_parent"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap_content"
        else -> value.toString()
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_RULE_INDEX = "rule_index"
        const val EXTRA_RULE_KEY_ACTIVITY = "rule_key_activity"
        const val EXTRA_RULE_KEY_VIEW = "rule_key_view"
        const val EXTRA_RULE_KEY_DEPTH = "rule_key_depth"
    }
}
