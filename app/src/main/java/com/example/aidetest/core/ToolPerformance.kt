package com.example.aidetest

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared limits & executor for tool background work.
 * Keeps UI responsive and bounds CPU/RAM usage across all tools.
 */
object ToolPerformance {
    /** Max file/folder rows rendered in File Manager at once. */
    const val FILE_LIST_UI_CAP = 250

    /** Max files collected in walk/scan helpers. */
    const val FILE_WALK_CAP = 8_000

    /** Max search / large-file hits shown. */
    const val SEARCH_RESULT_CAP = 200

    /** Max duplicate groups rendered. */
    const val DUPLICATE_GROUP_CAP = 80

    /** Network port-scan worker threads (LAN). */
    const val NET_SCAN_POOL = 6

    /** Connect timeout for LAN port probe (ms). */
    const val NET_PROBE_TIMEOUT_MS = 220

    /** Max hosts in /24 scan (1..254). */
    const val NET_SCAN_HOST_MAX = 254

    /** Buffer size for stream copy / zip. */
    const val IO_BUFFER = 64 * 1024

    private val threadSeq = AtomicInteger(1)

    /**
     * Bounded pool for toolThread work. Avoids unbounded thread creation
     * when users open many tools quickly.
     */
    val toolExecutor: ThreadPoolExecutor by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        ThreadPoolExecutor(
            cores,
            cores,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(64),
            ThreadFactory { r ->
                Thread(r, "gitls-tool-${threadSeq.getAndIncrement()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            },
            ThreadPoolExecutor.CallerRunsPolicy()
        ).also { it.allowCoreThreadTimeOut(true) }
    }

    fun listFilesCapped(dir: File, filter: (File) -> Boolean = { true }): Pair<List<File>, Boolean> {
        val raw = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList<File>() to false
        val all = raw.filter(filter)
        val truncated = all.size > FILE_LIST_UI_CAP
        return all to truncated
    }

    fun walkFilesCapped(root: File, cap: Int = FILE_WALK_CAP): List<File> {
        if (root.isFile) return listOf(root)
        val out = ArrayList<File>(minOf(cap, 256))
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < cap) {
            val d = stack.removeLast()
            val children = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (c in children) {
                if (out.size >= cap) break
                if (c.isFile) out.add(c)
                else if (c.isDirectory) stack.add(c)
            }
        }
        return out
    }
}
