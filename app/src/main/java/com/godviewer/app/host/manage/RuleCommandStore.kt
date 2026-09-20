package com.godviewer.app.host.manage

import android.content.Context
import com.godviewer.app.shared.control.RuleCommand
import com.godviewer.app.shared.mirror.sanitizeMirrorPackageName
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/**
 * 宿主侧管理指令的待办队列（`filesDir/godviewer/pending_cmds/<pkg>.json`）。
 *
 * 目标进程的 receiver 是动态注册的：进程不在，广播就石沉大海而且无从得知。
 * 所以每条管理指令都先落一份本地待办，目标下次上报前台时补发。
 * 补发是幂等的——指令带着 [RuleCommand.expectedTimestamp]，目标发现规则已经变了就跳过。
 */
internal object RuleCommandStore {
    private const val MAX_PER_PACKAGE = 200
    private val gson = Gson()

    private data class PendingCommands(
        val commands: List<RuleCommand>? = null,
    )

    private fun pendingDir(context: Context): File =
        File(File(context.applicationContext.filesDir, "godviewer"), "pending_cmds")

    /**
     * 入队：同键同操作的新指令顶掉旧的（保序到队尾），超上限时丢最老的。
     *
     * @return 入队后的完整待办列表
     */
    fun enqueue(
        context: Context,
        packageName: String,
        commands: List<RuleCommand>,
    ): List<RuleCommand> {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return emptyList()
        if (commands.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, RuleCommand>()
        for (cmd in pending(context, safePkg)) {
            merged[identity(cmd)] = cmd
        }
        for (cmd in commands) {
            val id = identity(cmd)
            merged.remove(id)
            merged[id] = cmd
        }
        while (merged.size > MAX_PER_PACKAGE) {
            val first = merged.keys.firstOrNull() ?: break
            merged.remove(first)
        }
        val result = merged.values.toList()
        write(context, safePkg, result)
        return result
    }

    fun pending(context: Context, packageName: String): List<RuleCommand> {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return emptyList()
        val file = File(pendingDir(context), "$safePkg.json")
        if (!file.exists()) return emptyList()
        val parsed = runCatching {
            gson.fromJson(file.readText(), PendingCommands::class.java)
        }.getOrNull() ?: return emptyList()
        return parsed.commands.orEmpty()
    }

    fun pendingCount(context: Context, packageName: String): Int =
        pending(context, packageName).size

    fun clear(context: Context, packageName: String) {
        val safePkg = sanitizeMirrorPackageName(packageName) ?: return
        File(pendingDir(context), "$safePkg.json").delete()
    }

    private fun identity(cmd: RuleCommand): String =
        "${cmd.activityClass}|${cmd.viewClass}|${cmd.depth?.joinToString(",")}|${cmd.op}"

    private fun write(context: Context, packageName: String, commands: List<RuleCommand>) {
        runCatching {
            val dir = pendingDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$packageName.json")
            val tmp = File(dir, "$packageName.json.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(gson.toJson(PendingCommands(commands)).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
