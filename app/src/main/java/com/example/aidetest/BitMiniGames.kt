package com.example.aidetest

import android.os.CountDownTimer
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Small offline mini-games. No network and no extra dependency. */
internal object BitMiniGames {
    fun showPicker(act: MainActivity) {
        val games = arrayOf("⚽ Ball Tap", "🍎 Food Catch", "🧠 Memory")
        AlertDialog.Builder(act).setTitle("Mini-game Bit")
            .setItems(games) { _, which -> when (which) { 0 -> ballTap(act); 1 -> foodCatch(act); 2 -> memory(act) } }
            .setNegativeButton("Tutup", null).show()
    }

    private fun ballTap(act: MainActivity) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(act.dp(24), act.dp(12), act.dp(24), act.dp(12)) }
        val score = TextView(act).apply { textSize = 18f; gravity = Gravity.CENTER; text = "Score: 0" }
        val btn = Button(act).apply { text = "⚽ TAP!" }
        box.addView(score); box.addView(btn)
        var points = 0
        var timer: CountDownTimer? = null
        val dialog = AlertDialog.Builder(act).setTitle("Ball Game").setView(box).setNegativeButton("Keluar", null).create()
        btn.setOnClickListener { points++; score.text = "Score: $points" }
        dialog.setOnDismissListener { timer?.cancel(); timer = null }
        dialog.show()
        timer = object : CountDownTimer(10_000L, 1000L) {
            override fun onTick(ms: Long) { if (dialog.isShowing) btn.text = "⚽ ${ms / 1000 + 1}" }
            override fun onFinish() {
                if (!dialog.isShowing) return
                btn.isEnabled = false
                val r = BitPetSystem.rewardMiniGame(points * 10, "Ball Game")
                score.text = "Selesai! Score $points\n${r.message}"
            }
        }.also { it.start() }
    }

    private fun foodCatch(act: MainActivity) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(act.dp(24), act.dp(12), act.dp(24), act.dp(12)) }
        val info = TextView(act).apply { textSize = 17f; gravity = Gravity.CENTER; text = "Tap 🍎 saat muncul!" }
        val btn = Button(act).apply { text = "Siap" }
        box.addView(info); box.addView(btn)
        var score = 0
        var timer: CountDownTimer? = null
        val dialog = AlertDialog.Builder(act).setTitle("Food Catch").setView(box).setNegativeButton("Keluar", null).create()
        btn.setOnClickListener {
            if (btn.text != "Siap") { score++; info.text = "🍎 Dapat! Score $score" }
            btn.text = if (score % 2 == 0) "🍎 TAP" else "🍪 TAP"
        }
        dialog.setOnDismissListener { timer?.cancel(); timer = null }
        dialog.show()
        timer = object : CountDownTimer(10_000L, 1200L) {
            override fun onTick(ms: Long) { if (dialog.isShowing) btn.text = "🍎 TAP" }
            override fun onFinish() {
                if (!dialog.isShowing) return
                btn.isEnabled = false
                val r = BitPetSystem.rewardMiniGame(score * 12, "Food Catch")
                info.text = "Selesai! Score $score\n${r.message}"
            }
        }.also { it.start() }
    }

    private fun memory(act: MainActivity) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(act.dp(24), act.dp(12), act.dp(24), act.dp(12))
        }
        val info = TextView(act).apply { textSize = 17f; gravity = Gravity.CENTER }
        val start = Button(act).apply { text = "Mulai" }
        val choices = LinearLayout(act).apply {
            gravity = Gravity.CENTER
            visibility = LinearLayout.GONE
        }
        box.addView(info)
        box.addView(start)
        box.addView(choices)

        val symbols = listOf("🍎", "⚽", "💧", "⭐")
        var sequence = emptyList<String>()
        var position = 0
        var score = 0
        var showing = false

        fun showChoices() {
            showing = false
            position = 0
            choices.removeAllViews()
            choices.visibility = LinearLayout.VISIBLE
            info.text = "Ulangi urutan: ${position + 1}/${sequence.size}"
            symbols.forEach { symbol ->
                choices.addView(Button(act).apply {
                    text = symbol
                    setOnClickListener {
                        if (position >= sequence.size) return@setOnClickListener
                        if (symbol == sequence[position]) {
                            position++
                            if (position == sequence.size) {
                                score++
                                if (score >= 3) {
                                    val r = BitPetSystem.rewardMiniGame(score * 15, "Memory")
                                    info.text = "Memory sempurna!\n${r.message}"
                                    choices.visibility = LinearLayout.GONE
                                    start.isEnabled = false
                                } else {
                                    info.text = "Benar! Ronde berikutnya…"
                                    choices.visibility = LinearLayout.GONE
                                    start.postDelayed({ if (start.isAttachedToWindow) start.performClick() }, 350L)
                                }
                            } else {
                                info.text = "Benar: ${position + 1}/${sequence.size}"
                            }
                        } else {
                            info.text = "Salah. Urutan tadi: ${sequence.joinToString(" ")}"
                            choices.visibility = LinearLayout.GONE
                            start.isEnabled = true
                            start.text = "Coba ronde lagi"
                        }
                    }
                }, LinearLayout.LayoutParams(0, act.dp(54), 1f))
            }
        }

        fun newRound() {
            sequence = sequence + symbols.random()
            showing = true
            choices.visibility = LinearLayout.GONE
            info.text = "Ingat: ${sequence.joinToString(" ")}"
            start.text = "Saya ingat"
        }

        start.setOnClickListener {
            if (showing) {
                showChoices()
            } else {
                newRound()
            }
        }

        val dialog = AlertDialog.Builder(act)
            .setTitle("Memory Bit")
            .setView(box)
            .setNegativeButton("Keluar", null)
            .create()
        dialog.setOnDismissListener { choices.removeAllViews() }
        dialog.show()
    }
}
