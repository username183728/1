package com.example.aidetest

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Mobile IDE-style Project Explorer.
 * Kept in a separate file so the editor page itself stays lightweight and build-safe.
 */
private object ProjectExplorerState {
    val expanded = linkedSetOf<String>()
    /** Folders the user explicitly collapsed (survives explorer reopen). */
    val collapsedByUser = linkedSetOf<String>()
    var popup: PopupWindow? = null
    /** Live tree container — refresh without recreating the whole popup (smooth expand). */
    var treeHost: LinearLayout? = null
    var treeRoot: File? = null
    var treeAnchor: android.view.View? = null
}

internal fun MainActivity.showEditorProjectExplorer(anchor: View? = null) {
    val root = editorProjectDir
        ?: editorFile?.parentFile
        ?: prefs.getString("last_editor_project", null)?.let { File(it) }
        ?: prefs.getString("last_workspace", null)?.let { File(it) }

    if (root == null || !root.isDirectory) {
        AlertDialog.Builder(this)
            .setTitle("Project Explorer")
            .setMessage("Belum ada folder project yang aktif.")
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Buka Folder") { _, _ -> pickAndOpenProjectFolder() }
            .setPositiveButton("Buat Project") { _, _ -> createEditorWebProject() }
            .show()
        return
    }

    editorProjectDir = root
    prefs.edit().putString("last_editor_project", root.absolutePath).apply()
    // Expand root only if user has not collapsed it
    val rootKey = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    if (rootKey !in ProjectExplorerState.collapsedByUser) {
        ProjectExplorerState.expanded.add(rootKey)
    }
    ProjectExplorerState.popup?.dismiss()

    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(if (isDarkTheme) Color.rgb(31, 34, 39) else Color.rgb(250, 250, 251))
        elevation = dp(10).toFloat()
    }

    val titleRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Small top inset for status bar, still flush to top edge of panel
        val statusPad = maxOf((resources.displayMetrics.density * 28).toInt().coerceAtLeast(dp(12)), sysTopInset + dp(8))
        setPadding(dp(14), statusPad, dp(8), dp(6))
    }
    titleRow.addView(MdiIconView(this).apply {
        setIconName("folder-open-outline")
        setIconSize(22f)
        setTextColor(textMain)
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(38))
    })
    titleRow.addView(TextView(this).apply {
        text = root.name
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        gravity = Gravity.CENTER_VERTICAL
    })
    val close = TextView(this).apply {
        text = "×"
        textSize = 27f
        gravity = Gravity.CENTER
        setTextColor(textMuted)
        contentDescription = "Tutup Project Explorer"
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        setOnClickListener { ProjectExplorerState.popup?.dismiss() }
    }
    titleRow.addView(close)
    panel.addView(titleRow)

    val actionRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), dp(8))
    }
    fun actionButton(icon: String, label: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = bg(if (isDarkTheme) Color.rgb(48, 52, 59) else Color.rgb(238, 240, 243), 12, Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(MdiIconView(this@showEditorProjectExplorer).apply {
                setIconName(icon)
                setIconSize(17f)
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(30))
            })
            addView(TextView(this@showEditorProjectExplorer).apply {
                text = label
                textSize = 11f
                setTextColor(textMain)
                layoutParams = LinearLayout.LayoutParams(-2, dp(30))
                gravity = Gravity.CENTER_VERTICAL
            })
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
        }
    }
    actionRow.addView(actionButton("folder-open-outline", "Buka") { pickAndOpenProjectFolder() })
    actionRow.addView(actionButton("file-plus-outline", "File") { createProjectFileFromExplorer(root) })
    actionRow.addView(actionButton("folder-plus-outline", "Folder") { createProjectFolderFromExplorer(root) })
    actionRow.addView(actionButton("refresh", "Refresh") { showEditorProjectExplorer(anchor) })
    panel.addView(actionRow)

    val divider = View(this).apply {
        setBackgroundColor(if (isDarkTheme) Color.rgb(62, 66, 73) else Color.rgb(224, 226, 230))
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }
    panel.addView(divider)

    val scroll = ScrollView(this).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    val tree = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Ruang bawah ekstra supaya item terakhir tidak tertutup navigation bar.
        setPadding(dp(7), dp(7), dp(7), dp(20) + sysBottomInset.coerceAtLeast(0))
    }
    scroll.addView(tree, ViewGroup.LayoutParams(-1, -2))
    panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    ProjectExplorerState.treeHost = tree
    ProjectExplorerState.treeRoot = root
    ProjectExplorerState.treeAnchor = anchor
    renderProjectTree(root, tree, 0, anchor)

    // Full-height panel from top of screen (no gap under status area)
    val width = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.92f).toInt())
    // Tinggi layar SEBENARNYA (termasuk area navigation bar). heightPixels biasa tidak menghitung
    // navigation bar, sehingga panel berhenti di atas toolbar bawah editor dan bagian bawah tidak tertutup.
    val realScreenH = runCatching {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        dm.heightPixels
    }.getOrDefault(0)
    val screenH = if (realScreenH > 0) realScreenH
        else resources.displayMetrics.heightPixels + sysBottomInset.coerceAtLeast(0)
    // Solid edge-to-edge background so panel fills vertically
    panel.setPadding(0, 0, 0, 0)
    val popup = PopupWindow(panel, width, screenH, true).apply {
        setBackgroundDrawable(ColorDrawable(
            if (isDarkTheme) Color.rgb(31, 34, 39) else Color.rgb(250, 250, 251)
        ))
        isOutsideTouchable = true
        isFocusable = true
        elevation = dp(14).toFloat()
        // overlap status bar / cutout when possible
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            @Suppress("DEPRECATION")
            isClippingEnabled = false
        }
        setOnDismissListener {
            if (ProjectExplorerState.popup === this) {
                ProjectExplorerState.popup = null
                ProjectExplorerState.treeHost = null
                ProjectExplorerState.treeRoot = null
                ProjectExplorerState.treeAnchor = null
            }
        }
    }
    ProjectExplorerState.popup = popup
    // y=0 → flush to top of window
    popup.showAtLocation(window.decorView, Gravity.START or Gravity.TOP, 0, 0)
}

/**
 * List existing project folders (app web_projects) and open the chosen one.
 * All subfolders are expanded so the full structure appears in the explorer.
 */
private fun MainActivity.pickAndOpenProjectFolder() {
    val base = File(filesDir, "web_projects").apply { mkdirs() }
    val projects = base.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
        .orEmpty()

    val items = mutableListOf<Triple<String, String, () -> Unit>>()
    items.add(Triple("folder-open-outline", "Dari penyimpanan HP…", {
        // SAF: pilih folder di penyimpanan perangkat
        ProjectExplorerState.popup?.dismiss()
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 9820)
    }))
    projects.forEach { dir ->
        val count = runCatching {
            dir.walkTopDown().maxDepth(4).count { it.isFile && it.name != "workspace.json" }
        }.getOrDefault(0)
        items.add(Triple("folder-outline", "${dir.name}  ($count file)", { openProjectFolderInExplorer(dir) }))
    }
    items.add(Triple("folder-plus-outline", "Buat project baru", {
        ProjectExplorerState.popup?.dismiss()
        createEditorWebProject()
    }))
    showIconListDialog("Buka Folder Project", items)
}

/**
 * Dialog daftar dengan ikon MDI di setiap baris (pengganti setItems berbasis emoji/teks),
 * supaya gaya ikon konsisten di seluruh UI.
 */
internal fun MainActivity.showIconListDialog(
    title: String,
    items: List<Triple<String, String, () -> Unit>>,
    message: String? = null,
    negative: String = "Batal"
) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    var dialog: android.content.DialogInterface? = null
    items.forEach { (iconName, label, action) ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rippleBg(Color.TRANSPARENT, 12, Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener {
                dialog?.dismiss()
                action()
            }
        }
        row.addView(MdiIconView(this).apply {
            setIconName(iconName)
            setIconSize(22f)
            setTextColor(textMain)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) }
        })
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(textMain)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        box.addView(row)
    }
    val builder = themedDialog().setTitle(title)
    if (message != null) builder.setMessage(message)
    dialog = builder
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(negative, null)
        .show()
}

/** Open a project folder: set as active, expand full tree, open main file. */
internal fun MainActivity.openProjectFolderInExplorer(dir: File) {
    if (!dir.isDirectory) {
        toast("Folder tidak valid")
        return
    }
    editorProjectDir = dir
    prefs.edit()
        .putString("last_workspace", dir.absolutePath)
        .putString("last_editor_project", dir.absolutePath)
        .apply()

    // Expand entire tree so all structure is visible
    ProjectExplorerState.collapsedByUser.clear()
    ProjectExplorerState.expanded.clear()
    expandAllProjectDirs(dir)

    // Preload common web files into buffers
    dir.walkTopDown().maxDepth(6).forEach { f ->
        if (!f.isFile || f.name.startsWith(".") || f.name == "workspace.json") return@forEach
        val mode = detectEditorMode(f.name)
        if (mode in listOf("html", "css", "js", "json", "xml", "text")) {
            val key = editorBufferKey(f, mode)
            if (!editorBuffers.containsKey(key)) {
                val textContent = runCatching { f.readText() }.getOrDefault("")
                editorBuffers[key] = MainActivity.EditorBuffer(
                    text = textContent,
                    filePath = f.absolutePath,
                    mode = mode,
                    dirty = false
                )
            }
        }
    }

    val htmlFile = File(dir, "index.html").takeIf { it.isFile }
        ?: dir.listFiles()?.firstOrNull {
            it.isFile && (it.extension.equals("html", true) || it.extension.equals("htm", true))
        }
        ?: dir.listFiles()?.firstOrNull { it.isFile && !it.name.startsWith(".") }

    ProjectExplorerState.popup?.dismiss()
    // Reset tabs so old project buffers don't leak into the new session
    editorOpenTabs.clear()
    editorActiveTabKey = ""
    if (htmlFile != null) {
        editor(htmlFile, detectEditorMode(htmlFile.name))
    } else {
        editor(null, "html")
    }
    content.post { showEditorProjectExplorer() }
    toast("Project dibuka: ${dir.name}")
}

private fun expandAllProjectDirs(dir: File, depth: Int = 0) {
    if (depth > 12 || !dir.isDirectory) return
    val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
    ProjectExplorerState.expanded.add(key)
    ProjectExplorerState.collapsedByUser.remove(key)
    dir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.forEach { expandAllProjectDirs(it, depth + 1) }
}


/** Re-draw tree only (no popup recreate) — used for expand/collapse. */
private fun MainActivity.softRefreshProjectTree() {
    val tree = ProjectExplorerState.treeHost ?: return
    val root = ProjectExplorerState.treeRoot ?: return
    val anchor = ProjectExplorerState.treeAnchor
    tree.removeAllViews()
    addProjectNode(root, tree, 0, true, anchor)
}

private fun MainActivity.renderProjectTree(root: File, tree: LinearLayout, depth: Int, anchor: View?) {
    tree.removeAllViews()
    addProjectNode(root, tree, 0, true, anchor)
}

private fun MainActivity.addProjectNode(file: File, tree: LinearLayout, depth: Int, isRoot: Boolean, anchor: View?) {
    if (file.name == "workspace.json") return
    val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    val isDir = file.isDirectory
    // Root and nested folders use the same expand state (chevron can collapse everything)
    val expanded = isDir && ProjectExplorerState.expanded.contains(canonical)

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6 + depth * 16), 0, dp(4), 0)
        minimumHeight = dp(42)
        isClickable = true
        isFocusable = true
        val active = !isDir && editorFile?.canonicalPath == canonical
        background = bg(
            when {
                active && isDarkTheme -> Color.rgb(49, 57, 69)
                active -> Color.rgb(232, 237, 244)
                else -> Color.TRANSPARENT
            },
            11,
            Color.TRANSPARENT
        )
    }

    if (isDir) {
        row.addView(MdiIconView(this).apply {
            setIconName(if (expanded) "chevron-down" else "chevron-right")
            setIconSize(17f)
            setTextColor(textMuted)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(38))
        })
    } else {
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(22), dp(38)) })
    }

    row.addView(MdiIconView(this).apply {
        setIconName(projectFileIcon(file))
        setIconSize(if (isDir) 20f else 18f)
        setTextColor(if (isDir) textMain else textMuted)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(38)).apply { rightMargin = dp(2) }
    })
    row.addView(TextView(this).apply {
        text = file.name
        textSize = 13.5f
        setTextColor(textMain)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
    })

    row.setOnClickListener {
        if (isDir) {
            // Toggle fold smoothly — only re-render the tree, keep popup open
            if (expanded) {
                ProjectExplorerState.expanded.remove(canonical)
                ProjectExplorerState.collapsedByUser.add(canonical)
            } else {
                ProjectExplorerState.expanded.add(canonical)
                ProjectExplorerState.collapsedByUser.remove(canonical)
            }
            softRefreshProjectTree()
        } else {
            // Open file with correct mode; flush current buffer first
            flushEditorCurrentToBuffer(writeDisk = true)
            editorProjectDir = rootProjectFor(file)
            val mode = detectEditorMode(file.name)
            ProjectExplorerState.popup?.dismiss()
            editor(file, mode)
        }
    }
    row.setOnLongClickListener {
        showProjectNodeMenu(file, row)
        true
    }
    tree.addView(row)

    if (isDir && expanded) {
        val children = runCatching {
            file.listFiles()
                ?.filter { it.name != "workspace.json" && !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
                .orEmpty()
        }.getOrDefault(emptyList())
        children.forEach { child -> addProjectNode(child, tree, depth + 1, false, anchor) }
    }
}

private fun MainActivity.rootProjectFor(file: File): File {
    return editorProjectDir ?: file.parentFile ?: file
}

private fun MainActivity.projectFileIcon(file: File): String {
    if (file.isDirectory) return "folder-outline"
    return when (file.extension.lowercase(Locale.getDefault())) {
        "html", "htm" -> "language-html5"
        "css" -> "language-css3"
        "js", "mjs", "cjs" -> "language-javascript"
        "json" -> "code-json"
        "kt", "kts", "java" -> "code-tags"
        "py" -> "language-python"
        "xml" -> "xml"
        "md" -> "language-markdown-outline"
        "txt" -> "file-document-outline"
        "png", "jpg", "jpeg", "webp", "gif", "svg" -> "file-document-outline"
        "mp3", "wav", "ogg" -> "file-document-outline"
        "zip", "rar", "7z" -> "folder-zip-outline"
        else -> "file-outline"
    }
}

private fun MainActivity.safeProjectFile(root: File, name: String): File? {
    val clean = name.trim().replace('\\', '/')
    if (clean.isBlank() || clean == "." || clean.contains("..")) return null
    val target = File(root, clean)
    val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
    val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return null
    return target.takeIf { targetPath == rootPath || targetPath.startsWith(rootPath + File.separator) }
}

private fun MainActivity.createProjectFileFromExplorer(root: File) {
    val input = EditText(this).apply {
        hint = "misalnya src/main.js"
        setSingleLine(true)
    }
    AlertDialog.Builder(this)
        .setTitle("File baru")
        .setView(input)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Buat") { _, _ ->
            val target = safeProjectFile(root, input.text.toString())
            if (target == null) { toast("Nama/path file tidak valid"); return@setPositiveButton }
            if (target.exists()) { toast("File sudah ada"); return@setPositiveButton }
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText("")
                editorProjectDir = root
                editor(target, detectEditorMode(target.name))
                ProjectExplorerState.popup?.dismiss()
            }.onFailure { toast("Gagal membuat file: ${it.message}") }
        }.show()
}

private fun MainActivity.createProjectFolderFromExplorer(root: File) {
    val input = EditText(this).apply {
        hint = "misalnya components"
        setSingleLine(true)
    }
    AlertDialog.Builder(this)
        .setTitle("Folder baru")
        .setView(input)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Buat") { _, _ ->
            val target = safeProjectFile(root, input.text.toString())
            if (target == null) { toast("Nama/path folder tidak valid"); return@setPositiveButton }
            if (target.exists()) { toast("Folder sudah ada"); return@setPositiveButton }
            runCatching {
                target.mkdirs()
                ProjectExplorerState.expanded.add(root.canonicalPath)
                showEditorProjectExplorer()
            }.onFailure { toast("Gagal membuat folder: ${it.message}") }
        }.show()
}

private fun MainActivity.showProjectNodeMenu(file: File, anchor: View?) {
    val menu = PopupMenu(this, anchor ?: window.decorView)
    menu.menu.add("Buka")
    menu.menu.add("Rename")
    if (file.isDirectory) {
        menu.menu.add("File baru di sini")
        menu.menu.add("Folder baru di sini")
    }
    menu.menu.add("Hapus")
    menu.setOnMenuItemClickListener { item: MenuItem ->
        when (item.title.toString()) {
            "Buka" -> {
                if (file.isFile) {
                    editorProjectDir = rootProjectFor(file)
                    editor(file, detectEditorMode(file.name))
                    ProjectExplorerState.popup?.dismiss()
                } else {
                    ProjectExplorerState.expanded.add(file.canonicalPath)
                    showEditorProjectExplorer(anchor)
                }
            }
            "Rename" -> renameProjectNode(file, anchor)
            "File baru di sini" -> if (file.isDirectory) createProjectFileFromExplorer(file)
            "Folder baru di sini" -> if (file.isDirectory) createProjectFolderFromExplorer(file)
            "Hapus" -> confirmDeleteProjectNode(file, anchor)
        }
        true
    }
    menu.show()
}

private fun MainActivity.renameProjectNode(file: File, anchor: View?) {
    val input = EditText(this).apply {
        setSingleLine(true)
        setText(file.name)
        setSelection(0, length())
    }
    AlertDialog.Builder(this)
        .setTitle("Rename")
        .setView(input)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Simpan") { _, _ ->
            val name = input.text.toString().trim()
            val target = file.parentFile?.let { safeProjectFile(it, name) }
            if (target == null || target.exists()) { toast("Nama tidak valid atau sudah dipakai"); return@setPositiveButton }
            runCatching {
                if (!file.renameTo(target)) error("rename gagal")
                // Keep editor buffers/tabs in sync with Explorer rename. Without this,
                // a subsequent Save could recreate the old path.
                val oldPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                val newPath = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
                val affected = editorBuffers.entries.filter { (_, b) ->
                    b.filePath == oldPath || (file.isDirectory && b.filePath?.startsWith(oldPath + java.io.File.separator) == true)
                }.toList()
                affected.forEach { (key, b) ->
                    val nextPath = b.filePath?.let { path ->
                        if (path == oldPath) newPath else newPath + path.removePrefix(oldPath)
                    }
                    val nextKey = if (nextPath != null) editorBufferKey(File(nextPath), b.mode) else key
                    editorBuffers.remove(key)
                    b.filePath = nextPath
                    editorBuffers[nextKey] = b
                    persistEditorBuffer(nextKey, b)
                    runCatching { draftFileForKey(key).delete() }
                }
                for (i in editorOpenTabs.indices) {
                    val tab = editorOpenTabs[i]
                    val tf = tab.file ?: continue
                    val tp = runCatching { tf.canonicalPath }.getOrDefault(tf.absolutePath)
                    if (tp == oldPath || (file.isDirectory && tp.startsWith(oldPath + java.io.File.separator))) {
                        val nextFile = File(newPath + tp.removePrefix(oldPath))
                        editorOpenTabs[i] = EditorTabBar.Tab(tab.key, nextFile.name, nextFile, tab.mode, tab.sourceUri)
                    }
                }
                if (editorFile?.absolutePath == file.absolutePath || (file.isDirectory && editorFile?.absolutePath?.startsWith(oldPath + java.io.File.separator) == true)) {
                    editorFile = editorFile?.let { ef ->
                        val ep = runCatching { ef.canonicalPath }.getOrDefault(ef.absolutePath)
                        File(newPath + ep.removePrefix(oldPath))
                    }
                    editorNameLabel?.text = editorFile?.name ?: target.name
                    editorCurrentBufferKey = editorFile?.let { editorBufferKey(it, editorMode) }
                }
                if (editorProjectDir?.absolutePath == file.absolutePath) editorProjectDir = target
                showEditorProjectExplorer(anchor)
            }.onFailure { toast("Gagal rename: ${it.message}") }
        }.show()
}

private fun MainActivity.confirmDeleteProjectNode(file: File, anchor: View?) {
    val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    val openPath = editorFile?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    val isOpenFile = openPath == path
    val isOpenInside = file.isDirectory && openPath != null && openPath.startsWith(path + java.io.File.separator)
    val extra = when {
        isOpenFile -> "\n\nFile ini sedang dibuka di editor. Tab & buffer terkait akan ditutup."
        isOpenInside -> "\n\nAda file di dalam folder ini yang sedang dibuka di editor. Editor akan dikosongkan."
        else -> ""
    }
    themedDialog()
        .setTitle("Hapus ${file.name}?")
        .setMessage(
            (if (file.isDirectory) "Semua isi folder ini juga akan dihapus." else "File ini akan dihapus dari project.") + extra
        )
        .setNegativeButton("Batal", null)
        .setPositiveButton("Hapus") { _, _ ->
            // Flush dulu jika file terbuka supaya tidak kehilangan ketikan tak tersimpan
            if (isOpenFile || isOpenInside) {
                runCatching { flushEditorCurrentToBuffer(writeDisk = true) }
            }
            runCatching {
                if (!file.deleteRecursively()) error("hapus gagal")
                // Bersihkan buffer/tab yang mengarah ke path terhapus
                val doomedKeys = editorBuffers.keys.filter { k ->
                    k == path || k.startsWith(path + "/") || k.startsWith(path + java.io.File.separator)
                }.toList()
                doomedKeys.forEach { k ->
                    editorBuffers.remove(k)
                    runCatching { draftFileForKey(k).delete() }
                }
                editorOpenTabs.removeAll { tab ->
                    val p = tab.file?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
                    p == path || (p != null && file.isDirectory && p.startsWith(path + java.io.File.separator)) ||
                        tab.key in doomedKeys
                }
                if (isOpenFile || isOpenInside) {
                    editorFile = null
                    editorExternalTarget = null
                    editorCurrentBufferKey = null
                    editorActiveTabKey = ""
                    editorNameLabel?.text = "Tanpa judul"
                    editorBox?.setText("")
                }
                if (editorProjectDir?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) } == path) {
                    editorProjectDir = null
                }
                softRefreshProjectTree()
                // Jika treeHost null (popup tutup), buka ulang
                if (ProjectExplorerState.treeHost == null) {
                    showEditorProjectExplorer(anchor)
                }
                toast("Dihapus: ${file.name}")
            }.onFailure { toast("Gagal menghapus: ${it.message}") }
        }.show()
}


/**
 * Copy a folder tree picked via SAF into app web_projects, then open it.
 * Uses DocumentFile so it works on Android 10+ without raw storage paths.
 */
internal fun MainActivity.importDeviceFolderAsProject(treeUri: android.net.Uri) {
    toast("Mengimpor folder…")
    toolThread {
        val result = runCatching {
            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
                ?: error("Folder tidak bisa dibaca")
            val name = (doc.name ?: "project").replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
            val base = File(filesDir, "web_projects").apply { mkdirs() }
            var target = File(base, name)
            var n = 2
            while (target.exists()) {
                target = File(base, "${name}_$n")
                n++
            }
            target.mkdirs()
            var importedBytes = 0L
            val maxImportBytes = 512L * 1024L * 1024L
            fun copyRec(src: androidx.documentfile.provider.DocumentFile, dst: File) {
                if (src.isDirectory) {
                    if (!dst.exists() && !dst.mkdirs()) error("Tidak bisa membuat folder ${dst.name}")
                    src.listFiles().forEach { child ->
                        val childName = child.name ?: return@forEach
                        if (childName.startsWith(".") || childName == "__MACOSX") return@forEach
                        copyRec(child, File(dst, childName))
                    }
                } else if (src.isFile) {
                    val declared = src.length().coerceAtLeast(0L)
                    if (declared > 256L * 1024L * 1024L) error("File terlalu besar: ${src.name}")
                    if (importedBytes + declared > maxImportBytes) error("Project terlalu besar (maks 512 MB)")
                    contentResolver.openInputStream(src.uri)?.use { input ->
                        dst.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                importedBytes += n
                                if (importedBytes > maxImportBytes) error("Project terlalu besar (maks 512 MB)")
                                output.write(buffer, 0, n)
                            }
                        }
                    } ?: error("Tidak bisa membaca ${src.name}")
                }
            }
            try {
                doc.listFiles().forEach { child ->
                    val childName = child.name ?: return@forEach
                    if (childName.startsWith(".") || childName == "__MACOSX") return@forEach
                    copyRec(child, File(target, childName))
                }
            } catch (t: Throwable) {
                runCatching { target.deleteRecursively() }
                throw t
            }
            target
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || currentPage != "Editor") return@runOnUiThread
            result.onSuccess { dir ->
                openProjectFolderInExplorer(dir)
            }.onFailure {
                // Import failures are reported; the copy routine removes partial output below.
                toast("Gagal impor folder: ${it.message}")
            }
        }
    }
}
