package com.example.aidetest

/**
 * Safe runtime metadata for Bit.
 * Never stores tokens, passwords, cookies or other credentials.
 */
object BitRuntimeContext {
    var activeToolId: String? = null
        private set
    var activeToolName: String? = null
        private set
    var lastCommand: String? = null
        private set
    var lastToolId: String? = null
        private set
    var lastToolName: String? = null
        private set
    var lastStatus: String? = null
        private set
    var lastResultSummary: String? = null
        private set
    var lastAction: String? = null
        private set

    // Real-time process context. Only safe metadata is stored.
    var activeScreen: String? = null
        private set
    var activeOperation: String? = null
        private set
    var progressPercent: Int? = null
        private set
    var progressCurrent: Int? = null
        private set
    var progressTotal: Int? = null
        private set
    var progressDetail: String? = null
        private set
    var processErrorTitle: String? = null
        private set
    var processErrorSummary: String? = null
        private set

    fun onProcessStart(screen: String, operation: String) {
        activeScreen = screen.trim().take(80)
        activeOperation = operation.trim().take(80)
        progressPercent = 0
        progressCurrent = null
        progressTotal = null
        progressDetail = null
        processErrorTitle = null
        processErrorSummary = null
        lastStatus = "running"
        lastAction = "process_start"
    }

    fun onProcessProgress(percent: Int, current: Int? = null, total: Int? = null, detail: String? = null) {
        progressPercent = percent.coerceIn(0, 100)
        progressCurrent = current
        progressTotal = total
        progressDetail = detail?.trim()?.take(180)
        lastStatus = "running"
        lastAction = "process_progress"
    }

    fun onProcessSuccess(summary: String? = null) {
        progressPercent = 100
        progressDetail = summary?.trim()?.take(180)
        processErrorTitle = null
        processErrorSummary = null
        lastStatus = "success"
        lastResultSummary = summary?.trim()?.take(300)
        lastAction = "process_success"
    }

    fun onProcessError(title: String, summary: String? = null) {
        processErrorTitle = sanitize(title, 100)
        processErrorSummary = summary?.let { sanitize(it, 300) }
        lastStatus = "error"
        lastResultSummary = processErrorSummary
        lastAction = "process_error"
    }

    fun onProcessCancelled() {
        lastStatus = "cancelled"
        lastAction = "process_cancelled"
    }

    /** Safe text for opening Bit from a process error. No credentials are exposed. */
    fun errorContextForBit(): String? {
        val title = processErrorTitle ?: return null
        val summary = processErrorSummary.orEmpty()
        val progress = progressPercent?.let { " Progress terakhir $it%." } ?: ""
        val files = if (progressCurrent != null && progressTotal != null) {
            " File ${progressCurrent}/${progressTotal}."
        } else ""
        return "Proses ${activeOperation ?: "tidak diketahui"} gagal: $title.$progress$files ${summary.trim()}".trim()
    }

    private fun sanitize(value: String, max: Int): String {
        var s = value.replace("\n", " ").replace("\r", " ").trim()
        // Never pass common GitHub token formats or Authorization headers to Bit.
        s = s.replace(Regex("(?i)(bearer\\s+|token\\s+|authorization\\s*[:=]\\s*)[A-Za-z0-9_\\-\\.]+"), "[credential disembunyikan]")
        s = s.replace(Regex("(?i)\\b(ghp_|github_pat_|gho_|ghu_|ghs_|ghr_)[A-Za-z0-9_\\-]+\\b"), "[credential disembunyikan]")
        return s.take(max)
    }

    fun processSummary(): String {
        val screen = activeScreen ?: "tidak ada"
        val operation = activeOperation ?: "tidak ada"
        val pct = progressPercent?.let { " $it%" } ?: ""
        val progress = if (progressCurrent != null && progressTotal != null) {
            " File ${progressCurrent}/${progressTotal}."
        } else ""
        val detail = progressDetail?.let { " $it" } ?: ""
        val error = processErrorTitle?.let { title ->
            " Error: $title.${processErrorSummary?.let { " $it" } ?: ""}"
        } ?: ""
        return "Layar: $screen. Operasi: $operation. Status: ${lastStatus ?: "belum ada"}.$pct$progress$detail$error"
    }

    fun onCommand(command: String) {
        lastCommand = command.trim().take(500)
        lastAction = "command"
    }

    fun onToolOpened(id: String, name: String) {
        activeToolId = id
        activeToolName = name
        lastToolId = id
        lastToolName = name
        lastStatus = "opened"
        lastResultSummary = null
        lastAction = "open_tool"
    }

    /** Record only a short, non-secret result/status from a tool. */
    fun onToolResult(status: String, summary: String? = null) {
        lastStatus = status.trim().take(80).ifBlank { "completed" }
        lastResultSummary = summary?.trim()?.take(300)
        lastAction = "tool_result"
    }

    fun onToolError(summary: String? = null) {
        onToolResult("error", summary)
    }

    /** Safe snapshot for Bit's language router. */
    fun summary(): String {
        val tool = activeToolName ?: lastToolName ?: "tidak ada"
        val status = lastStatus ?: "belum ada status"
        val result = lastResultSummary?.let { " Hasil: $it" } ?: ""
        return "Tool: $tool. Status: $status.$result ${processSummary()}"
    }

    fun clearActiveTool() {
        activeToolId = null
        activeToolName = null
        lastAction = "close_tool"
    }
}
