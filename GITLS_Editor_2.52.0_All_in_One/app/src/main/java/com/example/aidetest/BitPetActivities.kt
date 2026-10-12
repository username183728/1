package com.example.aidetest

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView

/**
 * Interactive Bit activities: BUTTON opens activity → user interaction → reward.
 * Emoji placeholders; swap later for sprites/Lottie without changing flow.
 */
internal object BitPetActivities {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSceneCallbacks = java.util.WeakHashMap<FrameLayout, MutableSet<Runnable>>()

    private fun postScene(room: FrameLayout, delayMs: Long, block: () -> Unit) {
        if (!room.isAttachedToWindow) return
        val callbacks = pendingSceneCallbacks.getOrPut(room) { linkedSetOf() }
        lateinit var r: Runnable
        r = Runnable {
            callbacks.remove(r)
            if (!room.isAttachedToWindow) return@Runnable
            runCatching { block() }
        }
        callbacks.add(r)
        handler.postDelayed(r, delayMs)
    }

    private fun cancelSceneCallbacks(room: FrameLayout) {
        pendingSceneCallbacks.remove(room)?.forEach(handler::removeCallbacks)
    }

    // Bit Room has a fixed action bar at the bottom. Activity overlays must sit
    // above it and their controls must reserve enough clearance so nothing is
    // visually or touch-wise covered by the action bar.
    private const val ACTION_BAR_CLEARANCE_DP = 112

    private fun activityBottomClearance(act: MainActivity): Int = act.dp(ACTION_BAR_CLEARANCE_DP)

    data class FoodItem(val emoji: String, val reaction: String, val name: String)

    private val foods = listOf(
        FoodItem("🍎", "😋", "Apel"),
        FoodItem("🍰", "🤩", "Kue"),
        FoodItem("🍕", "😍", "Pizza"),
        FoodItem("🥕", "😐→😋", "Wortel"),
        FoodItem("🍋", "😳", "Lemon"),
        FoodItem("🍪", "😋", "Cookie"),
        FoodItem("🍦", "🥰", "Es krim")
    )

    private val toys = listOf("⚽" to "Bola", "🧸" to "Boneka", "🎈" to "Balon")

    // Scene props are intentionally lightweight canvas placeholders. They give each
    // activity its own little "room scene" like the reference, while remaining easy
    // to replace with real vector/Lottie art later.
    private enum class SceneProp { FOOD_TABLE, BATHTUB, BED, TOY_STAGE }

    private fun sceneLayer(room: FrameLayout): FrameLayout? =
        room.findViewWithTag("bit_scene_props") as? FrameLayout

    private fun showSceneProp(act: MainActivity, room: FrameLayout, prop: SceneProp): View? {
        val layer = sceneLayer(room) ?: return null
        layer.removeAllViews()
        val view = ScenePropView(act, prop).apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            contentDescription = when (prop) {
                SceneProp.FOOD_TABLE -> "Meja makan Bit"
                SceneProp.BATHTUB -> "Bak mandi Bit"
                SceneProp.BED -> "Kasur Bit"
                SceneProp.TOY_STAGE -> "Area bermain Bit"
            }
        }
        layer.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
        return view
    }

    private fun hideSceneProp(room: FrameLayout, animated: Boolean = true) {
        val layer = sceneLayer(room) ?: return
        if (!animated) {
            layer.removeAllViews()
            layer.alpha = 1f
            return
        }
        layer.animate().cancel()
        layer.animate().alpha(0f).setDuration(160L).withEndAction {
            layer.removeAllViews()
            layer.alpha = 1f
        }.start()
    }

    private class ScenePropView(context: android.content.Context, private val prop: SceneProp) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private fun dp(v: Float) = v * resources.displayMetrics.density

        private fun rr(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float, radius: Float) {
            paint.style = Paint.Style.FILL
            paint.color = color
            c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
        }

        private fun dot(c: Canvas, color: Int, x: Float, y: Float, radius: Float) {
            paint.style = Paint.Style.FILL
            paint.color = color
            c.drawCircle(x, y, radius, paint)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            shadow.color = Color.argb(24, 40, 40, 55)
            c.drawOval(RectF(w * .18f, h * .74f, w * .82f, h * .90f), shadow)
            when (prop) {
                SceneProp.FOOD_TABLE -> {
                    rr(c, Color.rgb(255, 239, 211), w*.22f, h*.61f, w*.78f, h*.79f, dp(28f))
                    rr(c, Color.rgb(255, 211, 135), w*.29f, h*.68f, w*.71f, h*.80f, dp(18f))
                    dot(c, Color.WHITE, w*.50f, h*.62f, dp(42f))
                    dot(c, Color.rgb(255, 215, 104), w*.50f, h*.62f, dp(31f))
                }
                SceneProp.BATHTUB -> {
                    rr(c, Color.rgb(188, 232, 248), w*.17f, h*.58f, w*.83f, h*.81f, dp(34f))
                    rr(c, Color.rgb(91, 202, 232), w*.22f, h*.65f, w*.78f, h*.79f, dp(28f))
                    dot(c, Color.rgb(235, 251, 255), w*.34f, h*.56f, dp(12f))
                    dot(c, Color.rgb(235, 251, 255), w*.50f, h*.52f, dp(15f))
                    dot(c, Color.rgb(235, 251, 255), w*.66f, h*.56f, dp(12f))
                }
                SceneProp.BED -> {
                    rr(c, Color.rgb(222, 214, 248), w*.17f, h*.56f, w*.83f, h*.80f, dp(34f))
                    rr(c, Color.rgb(248, 246, 255), w*.22f, h*.50f, w*.78f, h*.68f, dp(28f))
                    rr(c, Color.rgb(193, 182, 239), w*.27f, h*.61f, w*.73f, h*.79f, dp(24f))
                    dot(c, Color.WHITE, w*.33f, h*.62f, dp(12f))
                }
                SceneProp.TOY_STAGE -> {
                    rr(c, Color.rgb(255, 239, 184), w*.23f, h*.63f, w*.77f, h*.78f, dp(22f))
                    dot(c, Color.rgb(255, 158, 183), w*.34f, h*.58f, dp(17f))
                    dot(c, Color.rgb(135, 205, 255), w*.66f, h*.58f, dp(17f))
                }
            }
        }
    }

    fun playPetScene(act: MainActivity, room: FrameLayout, bit: LottieAnimationView) {
        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            tag = "bit_pet_hand_scene"
            elevation = act.dp(21).toFloat()
        }
        room.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val hand = TextView(act).apply {
            text = "🤚"
            textSize = 40f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        overlay.addView(hand, FrameLayout.LayoutParams(act.dp(74), act.dp(74)))
        fun placeHand() {
            val bl = IntArray(2); bit.getLocationOnScreen(bl)
            val ol = IntArray(2); overlay.getLocationOnScreen(ol)
            hand.translationX = bl[0] + bit.width * 0.50f - ol[0] - act.dp(37)
            hand.translationY = bl[1] + bit.height * 0.18f - ol[1] - act.dp(37)
        }
        overlay.post {
            placeHand()
            hand.animate().alpha(1f).translationYBy(act.dp(10).toFloat()).setDuration(180L).withEndAction {
                hand.animate().translationYBy(act.dp(18).toFloat()).setDuration(260L).withEndAction {
                    hand.animate().translationYBy((-act.dp(18)).toFloat()).alpha(0f).setDuration(220L)
                        .withEndAction { room.removeView(overlay) }.start()
                }.start()
            }.start()
        }
    }

    private class MouthReactionView(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var opened = false
            set(value) { field = value; invalidate() }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = Color.rgb(58, 44, 52)
            val rect = if (opened) {
                RectF(cx - width*.23f, cy - height*.34f, cx + width*.23f, cy + height*.34f)
            } else {
                RectF(cx - width*.10f, cy - height*.07f, cx + width*.10f, cy + height*.07f)
            }
            c.drawRoundRect(rect, width*.11f, width*.11f, p)
            if (opened) {
                p.color = Color.rgb(255, 160, 180)
                c.drawOval(RectF(cx - width*.11f, cy + height*.08f, cx + width*.11f, cy + height*.27f), p)
            }
        }
    }

    // ——— Food: drawer → pick → drag to mouth ———
    fun openFood(
        act: MainActivity,
        room: FrameLayout,
        bit: LottieAnimationView,
        onStatus: (String) -> Unit,
        onDone: (success: Boolean) -> Unit
    ) {
        dismissOverlay(room)
        onStatus("Pilih makanan…")
        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(40, 0, 0, 0))
            isClickable = true
            tag = "bit_activity_overlay"
            elevation = act.dp(20).toFloat()
        }
        room.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        showSceneProp(act, room, SceneProp.FOOD_TABLE)

        val drawer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(20))
            background = pillBg(act, if (act.isDarkTheme) Color.rgb(28, 30, 36) else Color.WHITE)
            elevation = act.dp(8).toFloat()
        }
        drawer.addView(TextView(act).apply {
            text = "Pilih makanan"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (act.isDarkTheme) Color.WHITE else Color.rgb(40, 42, 48))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, act.dp(10))
        })
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        foods.forEach { food ->
            val chip = TextView(act).apply {
                text = food.emoji
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
                contentDescription = food.name
                isClickable = true
                setOnClickListener {
                    drawer.animate().translationY(drawer.height.toFloat()).alpha(0f).setDuration(180L)
                        .withEndAction {
                            room.removeView(drawer)
                            startFoodDrag(act, overlay, room, bit, food, onStatus, onDone)
                        }.start()
                }
            }
            row.addView(chip)
        }
        drawer.addView(row)
        val cancel = TextView(act).apply {
            text = "Batal"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (act.isDarkTheme) Color.rgb(160, 165, 175) else Color.rgb(120, 125, 135))
            setPadding(0, act.dp(12), 0, 0)
            setOnClickListener {
                hideSceneProp(room)
                dismissOverlay(room)
                onStatus("Bit siap menemani")
                onDone(false)
            }
        }
        drawer.addView(cancel)
        overlay.addView(drawer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = act.dp(16)
            rightMargin = act.dp(16)
            bottomMargin = activityBottomClearance(act)
        })
        drawer.translationY = act.dp(120).toFloat()
        drawer.alpha = 0f
        drawer.animate().translationY(0f).alpha(1f).setDuration(280L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
    }

    private fun startFoodDrag(
        act: MainActivity,
        overlay: FrameLayout,
        room: FrameLayout,
        bit: LottieAnimationView,
        food: FoodItem,
        onStatus: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        onStatus("Seret ${food.name} ke Bit…")
        BitSoftMotion.stopBreathing(bit)

        val foodView = TextView(act).apply {
            text = food.emoji
            textSize = 40f
            gravity = Gravity.CENTER
            elevation = act.dp(6).toFloat()
        }
        val lp = FrameLayout.LayoutParams(act.dp(64), act.dp(64), Gravity.CENTER).apply {
            bottomMargin = act.dp(100)
        }
        overlay.addView(foodView, lp)

        // Hint
        val hint = TextView(act).apply {
            text = "Lepas di dekat Bit"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (act.isDarkTheme) Color.rgb(200, 205, 215) else Color.rgb(90, 95, 105))
        }
        overlay.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = activityBottomClearance(act)
        })

        val mouth = MouthReactionView(act).apply {
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
        }
        overlay.addView(mouth, FrameLayout.LayoutParams(act.dp(54), act.dp(44)))
        fun updateMouthPosition() {
            val bl = IntArray(2); bit.getLocationOnScreen(bl)
            val ol = IntArray(2); overlay.getLocationOnScreen(ol)
            mouth.translationX = bl[0] + bit.width * 0.50f - ol[0] - act.dp(27)
            mouth.translationY = bl[1] + bit.height * 0.57f - ol[1] - act.dp(22)
        }
        overlay.post { updateMouthPosition() }

        var dragging = false
        var originX = 0f
        var originY = 0f
        var nearNotice = false
        var lastNearPulse = 0L
        foodView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    originX = e.rawX
                    originY = e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    act.scroll.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener true
                    val dx = e.rawX - originX
                    val dy = e.rawY - originY
                    v.translationX = dx
                    v.translationY = dy
                    // Bit follows the food with a delayed, forgiving gaze.
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val fx = loc[0] + v.width / 2f
                    val fy = loc[1] + v.height / 2f
                    act.bitAnim?.lookAt(fx, fy, 900L)

                    val distance = distanceToBitMouth(bit, v)
                    val magnetRadius = act.dp(118).toFloat()
                    if (distance <= magnetRadius) {
                        if (!nearNotice) {
                            nearNotice = true
                            hint.text = "😮  dekat…"
                            updateMouthPosition()
                            mouth.opened = true
                            mouth.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start()
                            BitSoftMotion.softAnticipate(bit, amount = 0.028f, ms = 180L)
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastNearPulse > 420L) {
                            lastNearPulse = now
                            val bx = bitScreenMouthX(bit)
                            val by = bitScreenMouthY(bit)
                            val nx = ((fx - bx) / act.dp(120).toFloat()).coerceIn(-1f, 1f)
                            val ny = ((fy - by) / act.dp(120).toFloat()).coerceIn(-1f, 1f)
                            BitSoftMotion.softLeanToward(bit, nx, ny, 180L)
                        }
                    } else if (nearNotice) {
                        nearNotice = false
                        hint.text = "Lepas di dekat Bit"
                        mouth.animate().alpha(0f).setDuration(120L).start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    // Mouth hitbox = generous rect around Bit center
                    if (nearBitMouth(bit, v, act.dp(78))) {
                        // Success: Bit notices → opens/leans → food enters → bite/chew → reward.
                        onStatus("${food.reaction}  Bit mau makan ${food.name}!")
                        hint.text = "😮"
                        val bx = bitScreenMouthX(bit)
                        val by = bitScreenMouthY(bit)
                        val o = IntArray(2); overlay.getLocationOnScreen(o)
                        val mouthX = bx - o[0] - v.width * 0.50f
                        val mouthY = by - o[1] - v.height * 0.50f
                        BitSoftMotion.softAnticipate(bit, amount = 0.05f, ms = 190L)
                        v.animate()
                            .translationX(mouthX)
                            .translationY(mouthY)
                            .scaleX(1.16f).scaleY(1.08f)
                            .setDuration(240L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.9f))
                            .withEndAction {
                                // Tiny pause at the mouth makes the bite readable on small screens.
                                mouth.opened = false
                                mouth.animate().scaleX(0.84f).scaleY(0.84f).setDuration(80L).start()
                                v.animate().scaleX(1.04f).scaleY(0.96f).setDuration(90L)
                                    .withEndAction {
                                        v.animate().scaleX(0.12f).scaleY(0.12f).alpha(0f).setDuration(130L)
                                            .withEndAction {
                                                // Actual stat/XP reward happens only after the visible bite.
                                                BitPetSystem.rememberFood(food.name)
                                                val r = BitPetSystem.perform(BitPetAction.EAT, bit)
                                                BitSoftMotion.sceneEat(bit) {
                                                    when (food.emoji) {
                                                        "🍋" -> BitSoftMotion.softShake(bit, deg = 5f, ms = 300L)
                                                        "🍰", "🍦" -> BitSoftMotion.softBounce(bit, peak = 1.08f, ms = 400L)
                                                        "🥕" -> BitSoftMotion.softSway(bit, deg = 2.2f, ms = 260L)
                                                        else -> {}
                                                    }
                                                }
                                                postScene(room, 1450L) {
                                                    mouth.animate().alpha(0f).setDuration(120L).start()
                                                    hideSceneProp(room)
                                                    dismissOverlay(room)
                                                    onStatus(r.message)
                                                    onDone(true)
                                                }
                                            }.start()
                                    }.start()
                            }.start()
                    } else {
                        // Fail: spring back
                        onStatus("Lebih dekat ke Bit…")
                        v.animate().translationX(0f).translationY(0f).setDuration(280L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                            .start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ——— Bath: drag sponge, scrub Bit ———
    fun openBath(
        act: MainActivity,
        room: FrameLayout,
        bit: LottieAnimationView,
        onStatus: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        dismissOverlay(room)
        onStatus("Gosok Bit dengan spons…")
        BitSoftMotion.stopBreathing(bit)
        BitSoftMotion.softSquash(bit, amount = 0.03f, ms = 200L)

        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(50, 40, 120, 140))
            isClickable = true
            tag = "bit_activity_overlay"
            elevation = act.dp(20).toFloat()
        }
        room.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        showSceneProp(act, room, SceneProp.BATHTUB)

        val sponge = TextView(act).apply {
            text = "🧽"
            textSize = 36f
            gravity = Gravity.CENTER
            elevation = act.dp(4).toFloat()
        }
        overlay.addView(sponge, FrameLayout.LayoutParams(act.dp(56), act.dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = act.dp(100)
        })

        val progress = TextView(act).apply {
            text = "🫧 0 / 8"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
            background = pillBg(act, Color.argb(160, 0, 0, 0))
        }
        overlay.addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = act.dp(80)
        })

        val cancel = TextView(act).apply {
            text = "Batal"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
            setOnClickListener {
                hideSceneProp(room)
                dismissOverlay(room)
                BitSoftMotion.resetPose(bit)
                onStatus("Bit siap menemani")
                onDone(false)
            }
        }
        overlay.addView(cancel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = activityBottomClearance(act)
        })

        var scrubs = 0
        var lastBubbleAt = 0L
        var lastSpongeX = 0f
        var lastSpongeY = 0f
        var ox = 0f
        var oy = 0f
        sponge.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ox = e.rawX; oy = e.rawY
                    lastSpongeX = e.rawX; lastSpongeY = e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    act.scroll.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = e.rawX
                    val ny = e.rawY
                    v.translationX = nx - ox
                    v.translationY = ny - oy
                    if (nearBitBody(bit, v, act.dp(122))) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val sx = (nx - lastSpongeX) / act.dp(40).coerceAtLeast(1).toFloat()
                        val sy = (ny - lastSpongeY) / act.dp(40).coerceAtLeast(1).toFloat()
                        lastSpongeX = nx
                        lastSpongeY = ny
                        if (now - lastBubbleAt > 180L) {
                            lastBubbleAt = now
                            scrubs++
                            progress.text = "🫧 $scrubs / 8"
                            spawnBubble(act, overlay, nx, ny)
                            BitSoftMotion.reactToScrub(bit, sx, sy, 150L)
                            if (scrubs % 3 == 0) {
                                BitSoftMotion.softAnticipate(bit, amount = 0.025f, ms = 150L)
                            }
                            act.bitAnim?.lookAt(nx, ny, 400L)
                            if (scrubs >= 8) {
                                onStatus("Bit bersih! ✨")
                                BitSoftMotion.softShake(bit, deg = 8f, ms = 450L) {
                                    BitSoftMotion.softSettle(bit)
                                    val r = BitPetSystem.perform(BitPetAction.BATH, bit)
                                    postScene(room, 500L) {
                                        hideSceneProp(room)
                                        dismissOverlay(room)
                                        onStatus(r.message)
                                        onDone(true)
                                    }
                                }
                                sponge.setOnTouchListener(null)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    // ——— Play: pick toy → drag near Bit ———
    fun openPlay(
        act: MainActivity,
        room: FrameLayout,
        bit: LottieAnimationView,
        onStatus: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        dismissOverlay(room)
        onStatus("Pilih mainan…")
        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(40, 0, 0, 0))
            isClickable = true
            tag = "bit_activity_overlay"
            elevation = act.dp(20).toFloat()
        }
        room.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        showSceneProp(act, room, SceneProp.TOY_STAGE)

        val drawer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(16))
            background = pillBg(act, if (act.isDarkTheme) Color.rgb(28, 30, 36) else Color.WHITE)
            elevation = act.dp(8).toFloat()
        }
        toys.forEach { (emoji, name) ->
            val chip = TextView(act).apply {
                text = "$emoji\n$name"
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
                setOnClickListener {
                    room.removeView(drawer)
                    startToyDrag(act, overlay, room, bit, emoji, name, onStatus, onDone)
                }
            }
            drawer.addView(chip)
        }
        overlay.addView(drawer, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = activityBottomClearance(act)
        })
        drawer.translationY = act.dp(80).toFloat()
        drawer.alpha = 0f
        drawer.animate().translationY(0f).alpha(1f).setDuration(240L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()

        overlay.setOnClickListener {
            // tap outside = cancel only if still on picker
            if (drawer.parent != null) {
                hideSceneProp(room)
                dismissOverlay(room)
                onStatus("Bit siap menemani")
                onDone(false)
            }
        }
    }

    private fun startToyDrag(
        act: MainActivity,
        overlay: FrameLayout,
        room: FrameLayout,
        bit: LottieAnimationView,
        emoji: String,
        name: String,
        onStatus: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        onStatus("Seret $name ke Bit…")
        BitSoftMotion.stopBreathing(bit)
        val toy = TextView(act).apply {
            text = emoji
            textSize = 40f
            gravity = Gravity.CENTER
        }
        overlay.addView(toy, FrameLayout.LayoutParams(act.dp(64), act.dp(64), Gravity.CENTER).apply {
            bottomMargin = act.dp(90)
        })
        var ox = 0f
        var oy = 0f
        var chaseReady = false
        var lastChasePulse = 0L
        toy.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ox = e.rawX; oy = e.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    act.scroll.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.translationX = e.rawX - ox
                    v.translationY = e.rawY - oy
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val tx = loc[0] + v.width / 2f
                    val ty = loc[1] + v.height / 2f
                    act.bitAnim?.lookAt(tx, ty, 650L)
                    val dist = distanceToBitBody(bit, v)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (dist < act.dp(150).toFloat() && !chaseReady) {
                        chaseReady = true
                        onStatus("Bit siap mengejar $name…")
                        BitSoftMotion.softAnticipate(bit, amount = 0.045f, ms = 170L)
                    }
                    if (dist < act.dp(132).toFloat() && now - lastChasePulse > 480L) {
                        lastChasePulse = now
                        val b = IntArray(2); bit.getLocationOnScreen(b)
                        val nx = ((tx - (b[0] + bit.width / 2f)) / act.dp(120).toFloat()).coerceIn(-1f, 1f)
                        val ny = ((ty - (b[1] + bit.height / 2f)) / act.dp(120).toFloat()).coerceIn(-1f, 1f)
                        BitSoftMotion.softLeanToward(bit, nx, ny, 180L)
                    }
                    if (dist >= act.dp(165).toFloat()) chaseReady = false
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (nearBitMouth(bit, v, act.dp(80))) {
                        onStatus("Bit mengejar $name! 🎉")
                        // Bit tetap di posisi ruangan. Hanya mata/tubuh yang bereaksi;
                        // jangan menggeser container Bit saat mainan didekatkan.
                        bit.animate().cancel()
                        bit.translationX = 0f
                        bit.translationY = 0f
                        BitSoftMotion.softAnticipate(bit, amount = 0.055f, ms = 150L)
                        v.animate().scaleX(1.12f).scaleY(1.12f).alpha(0f).setDuration(180L)
                            .withEndAction {
                                BitSoftMotion.scenePlay(bit) {
                                    val r = BitPetSystem.perform(BitPetAction.PLAY, bit)
                                    postScene(room, 450L) {
                                        hideSceneProp(room)
                                        dismissOverlay(room)
                                        onStatus(r.message)
                                        onDone(true)
                                    }
                                }
                            }.start()
                        toy.setOnTouchListener(null)
                    } else {
                        v.animate().translationX(0f).translationY(0f).setDuration(260L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
                        onStatus("Dekatkan $name ke Bit…")
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ——— Sleep (mostly guided scene, still not instant XP without opening) ———
    fun openSleep(
        act: MainActivity,
        room: FrameLayout,
        bit: LottieAnimationView,
        onStatus: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        dismissOverlay(room)
        onStatus("Bit mengantuk… 🥱")
        BitSoftMotion.stopBreathing(bit)
        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(70, 20, 16, 40))
            isClickable = true
            tag = "bit_activity_overlay"
            elevation = act.dp(20).toFloat()
        }
        room.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        showSceneProp(act, room, SceneProp.BED)
        val zzz = TextView(act).apply {
            text = "💤"
            textSize = 28f
            gravity = Gravity.CENTER
            alpha = 0f
        }
        overlay.addView(zzz, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            bottomMargin = act.dp(130)
        })
        // Mini scene then reward
        BitSoftMotion.sceneSleepEnter(bit) {
            zzz.animate().alpha(1f).translationY((-act.dp(20)).toFloat()).setDuration(600L).start()
            onStatus("Zzz… 😴")
            postScene(room, 900L) {
                val r = BitPetSystem.perform(BitPetAction.SLEEP, bit)
                // perform already schedules wake; dismiss overlay after short sleep visual
                postScene(room, 1200L) {
                    zzz.animate().alpha(0f).setDuration(200L).start()
                    hideSceneProp(room)
                    dismissOverlay(room)
                    onStatus(r.message)
                    onDone(true)
                }
            }
        }
    }

    // ——— helpers ———
    private fun bitScreenMouthX(bit: View): Float {
        val b = IntArray(2)
        bit.getLocationOnScreen(b)
        return b[0] + bit.width * 0.50f
    }

    private fun bitScreenMouthY(bit: View): Float {
        val b = IntArray(2)
        bit.getLocationOnScreen(b)
        return b[1] + bit.height * 0.55f
    }

    private fun distanceToBitMouth(bit: View, item: View): Float {
        val i = IntArray(2)
        item.getLocationOnScreen(i)
        val ix = i[0] + item.width / 2f
        val iy = i[1] + item.height / 2f
        val dx = bitScreenMouthX(bit) - ix
        val dy = bitScreenMouthY(bit) - iy
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun nearBitBody(bit: View, item: View, radiusPx: Int): Boolean {
        return distanceToBitBody(bit, item) <= radiusPx
    }

    private fun distanceToBitBody(bit: View, item: View): Float {
        val b = IntArray(2)
        val i = IntArray(2)
        bit.getLocationOnScreen(b)
        item.getLocationOnScreen(i)
        val bx = b[0] + bit.width / 2f
        val by = b[1] + bit.height * 0.52f
        val ix = i[0] + item.width / 2f
        val iy = i[1] + item.height / 2f
        val dx = bx - ix
        val dy = by - iy
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun nearBitMouth(bit: View, item: View, radiusPx: Int): Boolean {
        val b = IntArray(2)
        val i = IntArray(2)
        bit.getLocationOnScreen(b)
        item.getLocationOnScreen(i)
        val bx = b[0] + bit.width / 2f
        val by = b[1] + bit.height * 0.55f // slightly lower = "mouth"
        val ix = i[0] + item.width / 2f
        val iy = i[1] + item.height / 2f
        val dx = bx - ix
        val dy = by - iy
        return dx * dx + dy * dy <= radiusPx * radiusPx.toFloat()
    }

    private fun spawnBubble(act: MainActivity, parent: FrameLayout, rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        parent.getLocationOnScreen(loc)
        val bubble = TextView(act).apply {
            text = if (kotlin.random.Random.nextBoolean()) "🫧" else "💦"
            textSize = 18f
            alpha = 0.9f
        }
        val x = (rawX - loc[0] - act.dp(12)).toInt()
        val y = (rawY - loc[1] - act.dp(12)).toInt()
        parent.addView(bubble, FrameLayout.LayoutParams(act.dp(28), act.dp(28)).apply {
            leftMargin = x
            topMargin = y
        })
        bubble.animate().translationY((-act.dp(40)).toFloat()).alpha(0f).setDuration(500L)
            .withEndAction { parent.removeView(bubble) }.start()
    }

    fun dismissOverlay(room: FrameLayout) {
        cancelSceneCallbacks(room)
        val toRemove = mutableListOf<View>()
        for (i in 0 until room.childCount) {
            val c = room.getChildAt(i)
            if (c.tag == "bit_activity_overlay") toRemove.add(c)
        }
        toRemove.forEach { room.removeView(it) }
    }

    private fun pillBg(act: MainActivity, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = act.dp(22).toFloat()
        setColor(color)
    }
}
