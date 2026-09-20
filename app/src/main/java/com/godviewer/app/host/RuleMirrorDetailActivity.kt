package com.godviewer.app.host

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.databinding.ActivityRuleMirrorDetailBinding
import com.godviewer.app.databinding.ItemMirroredRuleBinding
import com.godviewer.app.host.manage.RuleEdit
import com.godviewer.app.host.manage.RuleMirrorManage
import com.godviewer.app.host.ui.HostActivity
import com.godviewer.app.shared.model.ViewRule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Host-side package rule list, styled like the in-target [com.godviewer.app.target.ui.RuleManagerDialog] rows.
 *
 * Besides read-only browsing it now supports the four management operations the target dialog exposes —
 * delete / visibility / text / restore — through [RuleMirrorManage]: the mirror here is updated
 * optimistically and the real edit is delivered to the target as a small command, queued when the
 * target process is not running.
 */
class RuleMirrorDetailActivity : HostActivity() {
    private val binding by lazy { ActivityRuleMirrorDetailBinding.inflate(layoutInflater) }
    private lateinit var packageName: String
    private val selected = LinkedHashSet<ViewRule.RuleKey>()
    private val adapter = RuleAdapter(
        onItemClick = { _, index ->
            startActivity(
                Intent(this, RuleMirrorRuleDetailActivity::class.java)
                    .putExtra(RuleMirrorRuleDetailActivity.EXTRA_PACKAGE, packageName)
                    .putExtra(RuleMirrorRuleDetailActivity.EXTRA_RULE_INDEX, index),
            )
        },
        onDeleteClick = { rule -> confirmDelete(listOf(rule)) },
        onCheckedChange = { rule, checked ->
            if (checked) selected.add(rule.key()) else selected.remove(rule.key())
            syncSelectionBar()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: RuleMirror.loadAppLabel(this, packageName)

        binding.toolbar.title = getString(R.string.manage_rules)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.appLabelView.text = label
        binding.packageNameView.text = packageName
        val icon = RuleMirror.loadAppIcon(this, packageName)
        if (icon != null) {
            binding.appIcon.setImageDrawable(icon)
        } else {
            binding.appIcon.setImageResource(R.mipmap.ic_launcher)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.batchDelete.setOnClickListener {
            val picked = adapter.items.filter { selected.contains(it.key()) }
            confirmDelete(picked)
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val rules = RuleMirror.loadRules(this, packageName)
            .sortedByDescending { it.timestamp }
        adapter.submit(packageName, rules)
        binding.emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

        // 目标进程没在跑时指令会留在队列里，如实告诉用户「还没生效」而不是谎称已完成
        val pending = RuleMirrorManage.pendingCount(this, packageName)
        binding.pendingHint.isVisible = pending > 0
        if (pending > 0) {
            binding.pendingHint.text = getString(R.string.rules_manage_pending, pending)
        }

        // 列表变了就丢掉已经不存在的选中项
        val alive = rules.mapTo(HashSet()) { it.key() }
        if (selected.retainAll(alive)) {
            adapter.notifyDataSetChanged()
        }
        syncSelectionBar()
    }

    private fun syncSelectionBar() {
        val count = adapter.items.count { selected.contains(it.key()) }
        binding.selectionBar.isVisible = count > 0
        binding.selectedCount.text = getString(R.string.selected_count, count)
        val all = adapter.items.isNotEmpty() && count == adapter.items.size
        binding.selectAll.setOnCheckedChangeListener(null)
        binding.selectAll.isChecked = all
        binding.selectAll.setOnCheckedChangeListener(selectAllListener)
    }

    /** 只响应真实点击：syncSelectionBar() 里程序化设置 checked 时不能回调回来递归。 */
    private val selectAllListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (!binding.selectAll.isPressed) return@OnCheckedChangeListener
        selected.clear()
        if (checked) {
            adapter.items.forEach { selected.add(it.key()) }
        }
        adapter.notifyDataSetChanged()
        syncSelectionBar()
    }

    private fun confirmDelete(rules: List<ViewRule>) {
        if (rules.isEmpty()) return
        val message = if (rules.size == 1) {
            getString(R.string.delete_rule_confirm_message)
        } else {
            getString(R.string.delete_selected_confirm_message, rules.size)
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> applyDelete(rules) }
            .show()
    }

    private fun applyDelete(rules: List<ViewRule>) {
        val result = RuleMirrorManage.apply(
            this,
            packageName,
            rules.map { it to RuleEdit.delete() },
        )
        if (result.changed == 0) {
            Toast.makeText(this, R.string.rules_manage_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        selected.clear()
        val text = if (result.dispatched) {
            getString(R.string.rules_manage_deleted, result.changed)
        } else {
            getString(R.string.rules_manage_deleted_queued, result.changed)
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        reload()
    }

    private inner class RuleAdapter(
        private val onItemClick: (ViewRule, Int) -> Unit,
        private val onDeleteClick: (ViewRule) -> Unit,
        private val onCheckedChange: (ViewRule, Boolean) -> Unit,
    ) : RecyclerView.Adapter<RuleAdapter.Holder>() {
        private var pkg: String = ""
        val items = ArrayList<ViewRule>()

        fun submit(packageName: String, list: List<ViewRule>) {
            pkg = packageName
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemMirroredRuleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            private val binding: ItemMirroredRuleBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: ViewRule, index: Int) {
                val hidden = rule.changedVisibility && rule.modified.visibility == View.GONE
                val prefix = if (hidden) getString(R.string.hidden_status) + " · " else ""
                val shortView = rule.viewClass.substringAfterLast('.')
                binding.ruleTitle.text = prefix + shortView.ifBlank { rule.viewClass }
                binding.ruleSubtitle.text = formatTimestamp(rule.timestamp)

                val thumb = RuleMirror.loadThumbnail(this@RuleMirrorDetailActivity, pkg, rule)
                if (thumb != null) {
                    binding.ruleThumb.setImageBitmap(thumb)
                } else {
                    binding.ruleThumb.setImageResource(android.R.drawable.ic_menu_gallery)
                }

                binding.ruleCheck.setOnCheckedChangeListener(null)
                binding.ruleCheck.isChecked = selected.contains(rule.key())
                binding.ruleCheck.setOnCheckedChangeListener { _, checked ->
                    onCheckedChange(rule, checked)
                }

                binding.ruleDelete.setOnClickListener { onDeleteClick(rule) }
                binding.root.setOnClickListener { onItemClick(rule, index) }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_LABEL = "label"

        fun formatTimestamp(timestamp: Long): String {
            val date = Date(timestamp)
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            cal.time = date
            val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
            val pattern = if (today == that) "HH:mm:ss" else "yyyy-MM-dd"
            return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        }
    }
}
