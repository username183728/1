package com.example.aidetest

/**
 * Jembatan perintah Bit -> APK.
 *
 * Bit hanya menghasilkan aksi aman (ID tool), bukan credential.
 * Bridge ini sengaja tidak menerima token, password, cookie, URI credential,
 * atau nilai rahasia lain. Tool tujuan mengambil credentialnya sendiri bila memang diperlukan.
 */
object BitActionBridge {
    data class Action(
        val id: String,
        val toolId: String? = null,
        /** Teks tombol bila aksi ditampilkan sebagai chip di chat Bit. */
        val label: String? = null
    )

    fun forTool(toolId: String): Action = when (toolId) {
        "githubzip" -> Action("GITHUB_UPLOAD", toolId, "Buka GitHub Publisher")
        "zip" -> Action("ZIP", toolId, "Buka ZIP / UNZIP")
        "qr" -> Action("SCAN_QR", toolId, "Buka QR Scanner")
        else -> Action("OPEN_TOOL", toolId, "Buka ${toolId}")
    }

    fun retryLast(): Action = Action("RETRY_LAST_TOOL")

    /** Step 19: diagnosis & perbaikan kode di Text Editor. Tidak membawa isi kode. */
    fun editorDiagnose(): Action = Action("EDITOR_DIAGNOSE", "editor", "Lihat semua masalah")
    fun editorFixPreview(): Action = Action("EDITOR_FIX_PREVIEW", "editor", "Pratinjau perbaikan")

    /** Menjalankan hanya aksi yang sudah dikenal APK. */
    fun execute(activity: MainActivity, action: Action): Boolean {
        return when (action.id) {
            "RETRY_LAST_TOOL" -> {
                val id = BitRuntimeContext.lastToolId ?: return false
                activity.openTool(id)
                true
            }
            "EDITOR_DIAGNOSE" -> {
                activity.showEditorDiagnostics()
                true
            }
            "EDITOR_FIX_PREVIEW" -> {
                activity.showEditorFixPreview()
                true
            }
            "GITHUB_CONFIRM_UPLOAD" -> {
                activity.bitConfirmGithubBotUpload()
                true
            }
            "GITHUB_EDIT_UPLOAD" -> {
                activity.bitEditGithubBotUpload()
                true
            }
            "GITHUB_UPLOAD", "ZIP", "SCAN_QR", "OPEN_TOOL" -> {
                val id = action.toolId ?: return false
                activity.openTool(id)
                true
            }
            else -> false
        }
    }
}
