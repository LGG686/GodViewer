package com.godviewer.app.host.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.godviewer.app.R
import com.godviewer.app.data.RuleMirror
import com.godviewer.app.host.RuleMirrorDetailActivity
import com.godviewer.app.host.backup.ImportPreview
import com.godviewer.app.host.backup.RuleBackupExporter
import com.godviewer.app.host.backup.RuleBackupImporter
import com.godviewer.app.host.prefs.HostPrefs
import com.godviewer.app.shared.backup.RuleBackupSource
import com.godviewer.app.shared.mirror.MirroredPackage
import java.text.DateFormat
import java.util.Date
import com.godviewer.app.databinding.FragmentRulesBinding
import com.godviewer.app.databinding.ItemMirroredPackageBinding

class RulesFragment : Fragment() {
    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private var syncTipDialog: AlertDialog? = null

    private val adapter = PackageAdapter { item ->
        startActivity(
            Intent(requireContext(), RuleMirrorDetailActivity::class.java)
                .putExtra(RuleMirrorDetailActivity.EXTRA_PACKAGE, item.packageName)
                .putExtra(RuleMirrorDetailActivity.EXTRA_LABEL, item.label),
        )
    }

    /** 导出：用户选择保存位置（SAF，不需要任何存储权限） */
    private val exportLauncher = registerForActivityResult(
        // 依赖的 androidx.activity 版本里 CreateDocument 还不支持传 mime，
        // 默认文件名的 .zip 后缀已经够用；导入侧用 */* 兜住各种文件管理器
        ActivityResultContracts.CreateDocument(),
    ) { uri ->
        if (uri != null) doExport(uri)
    }

    /** 导入：用户选择备份文件 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) doImport(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.btnExport.setOnClickListener {
            runCatching {
                exportLauncher.launch(RuleBackupExporter.suggestedFileName())
            }.onFailure {
                toast(R.string.rules_backup_export_failed)
            }
        }
        binding.btnImport.setOnClickListener {
            runCatching { importLauncher.launch(arrayOf("*/*")) }
                .onFailure { toast(R.string.rules_backup_import_failed) }
        }
        binding.btnUndoImport.setOnClickListener { confirmUndoImport() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        maybeShowSyncTip()
    }

    private fun refresh() {
        if (_binding == null) return
        val items = RuleMirror.listPackages(requireContext())
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnUndoImport.visibility =
            if (RuleBackupImporter.hasLastImport(requireContext())) View.VISIBLE else View.GONE
    }

    // ---------- 规则备份 / 恢复 ----------

    private fun doExport(uri: Uri) {
        val app = context?.applicationContext ?: return
        Thread {
            val items = RuleMirror.listPackages(app)
            if (items.isEmpty()) {
                toastOnUi(R.string.rules_backup_export_empty)
                return@Thread
            }
            val pkgCount = items.size
            val ruleCount = items.sumOf { it.ruleCount }
            val ok = RuleBackupExporter.writeTo(app, uri)
            if (ok) {
                toastOnUi(R.string.rules_backup_export_done, pkgCount, ruleCount)
            } else {
                toastOnUi(R.string.rules_backup_export_failed)
            }
        }.start()
    }

    private fun doImport(uri: Uri) {
        val app = context?.applicationContext ?: return
        Thread {
            // 备份文件可能是手改坏的：解析 / 预览都兜住，不能让子线程把应用带崩
            val backup = runCatching { RuleBackupImporter.read(app, uri) }.getOrNull()
            if (backup == null) {
                toastOnUi(R.string.rules_backup_import_invalid)
                return@Thread
            }
            val preview = runCatching { RuleBackupImporter.preview(app, backup) }.getOrNull()
            if (preview == null || preview.packages.isEmpty()) {
                toastOnUi(R.string.rules_backup_import_empty)
                return@Thread
            }
            runOnUi { showPreviewDialog(backup, preview) }
        }.start()
    }

    private fun showPreviewDialog(backup: RuleBackupSource, preview: ImportPreview) {
        val ctx = context ?: return
        val message = buildString {
            append(
                getString(
                    R.string.rules_backup_import_summary,
                    preview.packageCount,
                    preview.addedTotal,
                    preview.replacedTotal,
                ),
            )
            if (preview.invalidTotal > 0) {
                append('\n')
                append(getString(R.string.rules_backup_import_invalid_entries, preview.invalidTotal))
            }
            if (preview.uninstalled > 0) {
                append('\n')
                append(getString(R.string.rules_backup_import_uninstalled, preview.uninstalled))
            }
            if (preview.unlocatableTotal > 0) {
                append('\n')
                append(
                    getString(
                        R.string.rules_backup_import_unlocatable,
                        preview.unlocatableTotal,
                    ),
                )
            }
            // 这个功能还是半成品，用户在点「恢复」之前就该知道风险
            append("\n\n")
            append(getString(R.string.rules_backup_beta_notice))
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.rules_backup_import_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.rules_backup_import_apply) { _, _ -> applyImport(backup) }
            .show()
    }

    private fun applyImport(backup: RuleBackupSource) {
        val app = context?.applicationContext ?: return
        Thread {
            val result = runCatching { RuleBackupImporter.apply(app, backup) }.getOrNull()
            runOnUi {
                if (result == null) {
                    toast(R.string.rules_backup_import_failed)
                } else {
                    toast(R.string.rules_backup_import_done, result.packages, result.rules)
                }
                refresh()
            }
        }.start()
    }

    /** 撤销上次导入：整批移除，用于导入后发现有控件被误隐藏时快速回滚。 */
    private fun confirmUndoImport() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.rules_backup_undo_title)
            .setMessage(R.string.rules_backup_undo_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.rules_backup_undo) { _, _ -> doUndoImport() }
            .show()
    }

    private fun doUndoImport() {
        val app = context?.applicationContext ?: return
        Thread {
            val removed = runCatching { RuleBackupImporter.undoLast(app) }.getOrNull()
            runOnUi {
                if (removed == null || removed == 0) {
                    toast(R.string.rules_backup_undo_none)
                } else {
                    toast(R.string.rules_backup_undo_done, removed)
                }
                refresh()
            }
        }.start()
    }

    private fun runOnUi(block: () -> Unit) {
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                block()
            }
        }
    }

    private fun toast(resId: Int, vararg args: Any) {
        val ctx = context ?: return
        Toast.makeText(ctx, ctx.getString(resId, *args), Toast.LENGTH_LONG).show()
    }

    private fun toastOnUi(resId: Int, vararg args: Any) {
        runOnUi { toast(resId, *args) }
    }

    override fun onPause() {
        syncTipDialog?.dismiss()
        syncTipDialog = null
        super.onPause()
    }

    override fun onDestroyView() {
        syncTipDialog?.dismiss()
        syncTipDialog = null
        super.onDestroyView()
        _binding = null
    }

    private fun maybeShowSyncTip() {
        val ctx = context ?: return
        if (HostPrefs.isRulesSyncTipDismissed(ctx)) return
        if (syncTipDialog?.isShowing == true) return
        syncTipDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.rules_sync_tip_title)
            .setMessage(R.string.rules_sync_tip_message)
            .setPositiveButton(R.string.rules_sync_tip_got_it, null)
            .setNeutralButton(R.string.rules_sync_tip_dont_show) { _, _ ->
                HostPrefs.setRulesSyncTipDismissed(ctx, true)
            }
            .setOnDismissListener { syncTipDialog = null }
            .show()
    }

    private class PackageAdapter(
        private val onClick: (MirroredPackage) -> Unit,
    ) : RecyclerView.Adapter<PackageAdapter.Holder>() {
        private val items = ArrayList<MirroredPackage>()
        private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        fun submit(list: List<MirroredPackage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemMirroredPackageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(
            private val binding: ItemMirroredPackageBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: MirroredPackage) {
                if (item.icon != null) {
                    binding.appIcon.setImageDrawable(item.icon)
                } else {
                    binding.appIcon.setImageResource(R.mipmap.ic_launcher)
                }
                binding.appLabel.text = item.label
                binding.appPackage.text = item.packageName
                binding.appMeta.text = binding.root.context.getString(
                    R.string.mirror_package_meta,
                    item.ruleCount,
                    timeFormat.format(Date(item.updatedAt)),
                )
                binding.root.setOnClickListener { onClick(item) }
            }
        }
    }
}
