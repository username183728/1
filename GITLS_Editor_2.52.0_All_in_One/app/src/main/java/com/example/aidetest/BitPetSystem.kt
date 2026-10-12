package com.example.aidetest

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.airbnb.lottie.LottieAnimationView
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bit Life Engine
 *
 * Bit is a persistent companion, not a collection of buttons.
 * State -> needs -> mood -> behavior -> animation -> interaction -> memory -> progression.
 * All values are local/offline and persisted through SharedPreferences.
 */
enum class BitPetState {
    IDLE, HAPPY, SAD, HUNGRY, SLEEPY, BORED, DIRTY, EXCITED, CURIOUS,
    EATING, PLAYING, BATHING, SLEEPING, WAKE_UP, INTERACTING
}

enum class BitMood {
    HAPPY, EXCITED, SLEEPY, HUNGRY, SAD, BORED, DIRTY, IRRITATED, CURIOUS, LOVE, SURPRISED
}

enum class BitPetAction { PET, EAT, PLAY, BATH, SLEEP }

enum class BitTimeOfDay { MORNING, DAY, EVENING, NIGHT, LATE_NIGHT }

data class BitPetSnapshot(
    val state: BitPetState,
    val hunger: Int,
    val happiness: Int,
    val clean: Int,
    val energy: Int,
    val xp: Int,
    val level: Int,
    val xpToNext: Int,
    val bond: Int,
    val message: String,
    val busy: Boolean,
    val funLevel: Int = 70,
    val sleepiness: Int = 20,
    val affection: Int = 20,
    val health: Int = 100,
    val mood: BitMood = BitMood.HAPPY,
    val relationshipLevel: Int = 1,
    val timeOfDay: BitTimeOfDay = BitTimeOfDay.DAY,
    val totalInteractions: Int = 0,
    val favoriteFood: String = "Belum ada",
    val favoriteActivity: String = "Belum ada"
)

data class BitPetResult(
    val ok: Boolean,
    val message: String,
    val leveledUp: Boolean,
    val newLevel: Int,
    val snapshot: BitPetSnapshot
)

object BitPetSystem {
    private const val PREF_HUNGER = "bit_hunger"
    private const val PREF_HAPPY = "bit_mood"
    private const val PREF_CLEAN = "bit_clean"
    private const val PREF_ENERGY = "bit_energy"
    private const val PREF_FUN = "bit_fun"
    private const val PREF_SLEEPINESS = "bit_sleepiness"
    private const val PREF_AFFECTION = "bit_affection"
    private const val PREF_HEALTH = "bit_health"
    private const val PREF_XP = "bit_companion_xp"
    private const val PREF_BOND = "bit_bond"
    private const val PREF_FEED = "bit_feed_count"
    private const val PREF_PET = "bit_pet_count"
    private const val PREF_PLAY = "bit_play_count"
    private const val PREF_BATH = "bit_bath_count"
    private const val PREF_SLEEP = "bit_sleep_count"
    private const val PREF_INTERACTIONS = "bit_interactions"
    private const val PREF_LAST_TICK = "bit_last_tick_ms"
    private const val PREF_LAST_OPEN = "bit_last_open_ms"
    private const val PREF_LAST_EAT = "bit_last_eat_ms"
    private const val PREF_LAST_PLAY = "bit_last_play_ms"
    private const val PREF_LAST_BATH = "bit_last_bath_ms"
    private const val PREF_LAST_SLEEP = "bit_last_sleep_ms"
    private const val PREF_FAV_FOOD = "bit_favorite_food"
    private const val PREF_FAV_ACTIVITY = "bit_favorite_activity"
    private const val PREF_LAST_TIME_BUCKET = "bit_last_time_bucket"

    private const val XP_PER_LEVEL = 40
    private const val AUTONOMY_MS = 12_000L
    private const val DECAY_INTERVAL_MS = 30_000L
    private const val MAX_OFFLINE_STEPS = 240 // 2 hours of simulation at 30s steps

    private val handler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var activity: MainActivity? = null
    @Volatile private var state = BitPetState.IDLE
    @Volatile private var mood = BitMood.HAPPY
    @Volatile private var busyUntil = 0L
    @Volatile private var lastMessage = "Bit siap menemani"
    private var autonomyStarted = false
    private var welcomePending = false
    private var lastAutonomousAt = 0L
    private val listeners = mutableListOf<(BitPetSnapshot) -> Unit>()
    private val pendingCallbacks = linkedSetOf<Runnable>()

    private val autonomyTick = object : Runnable {
        override fun run() {
            if (!autonomyStarted) return
            applyPassiveDecay()
            evaluateMoodState()
            notifyListeners()
            maybeAutonomousBehavior()
            handler.postDelayed(this, AUTONOMY_MS)
        }
    }

    fun bind(act: MainActivity) {
        activity = act
        prefs = act.prefs
        loadFromDisk()
        applyPassiveDecay()
        evaluateMoodState(forceIdleMessage = true)
        if (welcomePending) {
            welcomePending = false
            lastMessage = when (timeOfDay()) {
                BitTimeOfDay.MORNING -> "Bit bangun! Selamat pagi ☀️"
                BitTimeOfDay.EVENING -> "Bit menyambutmu sore ini ✨"
                BitTimeOfDay.NIGHT, BitTimeOfDay.LATE_NIGHT -> "Bit masih menemanimu malam ini 🌙"
                BitTimeOfDay.DAY -> "Bit senang kamu kembali 💛"
            }
            if (state !in activeStates()) state = BitPetState.HAPPY
        }
        p().edit().putLong(PREF_LAST_OPEN, System.currentTimeMillis()).apply()
    }

    fun unbind() {
        stopAutonomy()
        cancelPendingCallbacks()
        listeners.clear()
        activity = null
        prefs = null
    }

    fun addListener(l: (BitPetSnapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (BitPetSnapshot) -> Unit) { listeners.remove(l) }

    fun startAutonomy() {
        if (autonomyStarted) return
        autonomyStarted = true
        applyPassiveDecay()
        evaluateMoodState()
        notifyListeners()
        handler.removeCallbacks(autonomyTick)
        handler.postDelayed(autonomyTick, AUTONOMY_MS)
    }

    fun stopAutonomy() {
        autonomyStarted = false
        handler.removeCallbacks(autonomyTick)
    }

    fun snapshot(): BitPetSnapshot {
        val x = xp()
        return BitPetSnapshot(
            state = state,
            hunger = hunger(), happiness = happiness(), clean = clean(), energy = energy(),
            xp = x, level = levelOf(x), xpToNext = levelOf(x) * XP_PER_LEVEL,
            bond = bond(), message = lastMessage,
            busy = SystemClock.elapsedRealtime() < busyUntil,
            funLevel = funLevel(), sleepiness = sleepiness(), affection = affection(), health = health(),
            mood = mood, relationshipLevel = relationshipLevel(), timeOfDay = timeOfDay(),
            totalInteractions = prefInt(PREF_INTERACTIONS, 0),
            favoriteFood = p().getString(PREF_FAV_FOOD, "Belum ada") ?: "Belum ada",
            favoriteActivity = p().getString(PREF_FAV_ACTIVITY, "Belum ada") ?: "Belum ada"
        )
    }

    fun perform(action: BitPetAction, face: LottieAnimationView? = null): BitPetResult {
        val prefs = p()
        val now = SystemClock.elapsedRealtime()
        if (now < busyUntil) return fail(lastMessage.ifBlank { "Bit masih sibuk…" })

        if (state == BitPetState.SLEEPING && action != BitPetAction.SLEEP) {
            transition(BitPetState.WAKE_UP, "Bit bangun…")
            animateWake(face)
            busyUntil = now + 1100L
            postGuarded(1050L) { evaluateMoodState(true); notifyListeners() }
            return BitPetResult(true, "Bit bangun…", false, levelOf(xp()), snapshot())
        }

        val before = levelOf(xp())
        val resultMsg: String
        when (action) {
            BitPetAction.PET -> {
                transition(BitPetState.INTERACTING, "Bit menikmati elusan 💛")
                bump(PREF_HAPPY, 8); bump(PREF_FUN, 3); bump(PREF_AFFECTION, 4); bump(PREF_ENERGY, -1)
                addXp(1); addBond(2); increment(PREF_PET)
                resultMsg = if (affection() >= 75) "Bit merasa sangat dekat denganmu 🥰" else "Bit senang dielus 💛"
                rememberActivity("Elus")
                animatePet(face)
            }
            BitPetAction.EAT -> {
                transition(BitPetState.EATING, "Nyam… Bit sedang makan 😋")
                bump(PREF_HUNGER, 22); bump(PREF_HAPPY, 5); bump(PREF_FUN, 2); bump(PREF_ENERGY, 2)
                bump(PREF_SLEEPINESS, -4); addXp(2); addBond(1); increment(PREF_FEED)
                prefs.edit().putLong(PREF_LAST_EAT, System.currentTimeMillis()).apply()
                resultMsg = "Nyam… Bit kenyang 😋"
                rememberActivity("Makan")
                animateEat(face)
            }
            BitPetAction.PLAY -> {
                if (energy() < 15 || health() < 20) {
                    transition(BitPetState.SLEEPY, "Bit kelelahan… istirahat dulu")
                    notifyListeners()
                    return fail("Bit kelelahan… istirahat dulu")
                }
                transition(BitPetState.PLAYING, "Bit asyik bermain 🎉")
                bump(PREF_HAPPY, 12); bump(PREF_FUN, 20); bump(PREF_ENERGY, -12); bump(PREF_HUNGER, -6)
                bump(PREF_CLEAN, -4); bump(PREF_SLEEPINESS, 7); addXp(3); addBond(2); increment(PREF_PLAY)
                prefs.edit().putLong(PREF_LAST_PLAY, System.currentTimeMillis()).apply()
                resultMsg = "Bit sangat senang bermain 🎉"
                rememberActivity("Main")
                animatePlay(face)
            }
            BitPetAction.BATH -> {
                transition(BitPetState.BATHING, "Airnya segar… 🫧")
                bump(PREF_CLEAN, 28); bump(PREF_HAPPY, 6); bump(PREF_FUN, 4); bump(PREF_ENERGY, -3)
                bump(PREF_SLEEPINESS, -3); heal(2); addXp(2); addBond(1); increment(PREF_BATH)
                prefs.edit().putLong(PREF_LAST_BATH, System.currentTimeMillis()).apply()
                resultMsg = "Bit bersih dan segar ✨"
                rememberActivity("Mandi")
                animateBath(face)
            }
            BitPetAction.SLEEP -> {
                transition(BitPetState.SLEEPING, "Zzz… Bit tidur 💤")
                bump(PREF_ENERGY, 28); bump(PREF_SLEEPINESS, -28); bump(PREF_HAPPY, 2); bump(PREF_FUN, -2)
                bump(PREF_HUNGER, -5); heal(3); addXp(1); addBond(1); increment(PREF_SLEEP)
                prefs.edit().putLong(PREF_LAST_SLEEP, System.currentTimeMillis()).apply()
                resultMsg = "Zzz… energi Bit pulih 💤"
                rememberActivity("Tidur")
                animateSleep(face)
            }
        }

        lastMessage = resultMsg
        val sceneMs = when (action) {
            BitPetAction.PET -> 1800L; BitPetAction.EAT -> 2700L; BitPetAction.PLAY -> 2500L
            BitPetAction.BATH -> 3000L; BitPetAction.SLEEP -> 3600L
        }
        busyUntil = now + sceneMs
        val after = levelOf(xp())
        val leveled = after > before
        if (leveled) {
            lastMessage = "Level up! Bit naik ke Lv $after 🎉"
            transition(BitPetState.EXCITED, lastMessage)
            animateLevelUp(face)
            activity?.runCatching { toast("Bit naik ke Level $after!") }
        }
        updateHealthFromNeeds()
        notifyListeners()

        handler.postDelayed({
            if (action == BitPetAction.SLEEP && state == BitPetState.SLEEPING) {
                transition(BitPetState.WAKE_UP, "Bit bangun segar ☀️")
                animateWake(face)
                busyUntil = SystemClock.elapsedRealtime() + 1150L
                postGuarded(1050L) { evaluateMoodState(true); notifyListeners() }
            } else {
                BitSoftMotion.softSettle(face)
                evaluateMoodState(true)
                notifyListeners()
            }
        }, sceneMs)
        return BitPetResult(true, lastMessage, leveled, after, snapshot())
    }

    /** Used by mini-games so game score can reward Bit without faking a care action. */
    fun rewardMiniGame(score: Int, game: String): BitPetResult {
        val now = SystemClock.elapsedRealtime()
        if (now < busyUntil) return fail("Bit masih sibuk…")
        val reward = (score.coerceAtLeast(0) / 10).coerceIn(1, 12)
        val before = levelOf(xp())
        bump(PREF_FUN, 8); bump(PREF_HAPPY, 5); bump(PREF_ENERGY, -4); bump(PREF_SLEEPINESS, 3)
        addXp(reward); addBond(1)
        rememberActivity(game)
        increment(PREF_INTERACTIONS)
        lastMessage = "Bit hebat! +$reward EXP 🎮"
        val after = levelOf(xp())
        val leveled = after > before
        if (leveled) { mood = BitMood.EXCITED; state = BitPetState.EXCITED }
        notifyListeners()
        return BitPetResult(true, lastMessage, leveled, after, snapshot())
    }

    private fun postGuarded(delayMs: Long, block: () -> Unit) {
        if (prefs == null || activity == null) return
        lateinit var callback: Runnable
        callback = Runnable {
            pendingCallbacks.remove(callback)
            if (prefs == null || activity == null) return@Runnable
            runCatching { block() }
        }
        pendingCallbacks.add(callback)
        handler.postDelayed(callback, delayMs)
    }

    private fun cancelPendingCallbacks() {
        pendingCallbacks.forEach(handler::removeCallbacks)
        pendingCallbacks.clear()
    }

    private fun p(): SharedPreferences = prefs ?: throw IllegalStateException("Bit system belum bind")
    private fun prefInt(key: String, default: Int) = p().getInt(key, default)
    private fun hunger() = prefInt(PREF_HUNGER, 70).coerceIn(0, 100)
    private fun happiness() = prefInt(PREF_HAPPY, 70).coerceIn(0, 100)
    private fun clean() = prefInt(PREF_CLEAN, 70).coerceIn(0, 100)
    private fun energy() = prefInt(PREF_ENERGY, 70).coerceIn(0, 100)
    private fun funLevel() = prefInt(PREF_FUN, 70).coerceIn(0, 100)
    private fun sleepiness() = prefInt(PREF_SLEEPINESS, 20).coerceIn(0, 100)
    private fun affection() = prefInt(PREF_AFFECTION, 20).coerceIn(0, 100)
    private fun health() = prefInt(PREF_HEALTH, 100).coerceIn(0, 100)
    private fun xp() = prefInt(PREF_XP, 0).coerceAtLeast(0)
    private fun bond() = prefInt(PREF_BOND, 10).coerceIn(0, 100)
    private fun relationshipLevel() = (1 + bond() / 20).coerceAtMost(6)
    fun levelOf(xp: Int = xp()) = (1 + xp / XP_PER_LEVEL).coerceAtMost(99)

    private fun bump(key: String, delta: Int) {
        val current = p().getInt(key, defaultFor(key))
        p().edit().putInt(key, (current + delta).coerceIn(0, 100)).apply()
    }
    private fun heal(delta: Int) { bump(PREF_HEALTH, delta) }
    private fun addXp(amount: Int) { p().edit().putInt(PREF_XP, xp() + amount.coerceAtLeast(0)).apply() }
    private fun addBond(amount: Int) { bump(PREF_BOND, amount) }
    private fun increment(key: String) { p().edit().putInt(key, prefInt(key, 0) + 1).apply() }
    private fun defaultFor(key: String) = when (key) {
        PREF_HUNGER, PREF_HAPPY, PREF_CLEAN, PREF_ENERGY, PREF_FUN -> 70
        PREF_SLEEPINESS, PREF_AFFECTION -> 20
        PREF_HEALTH -> 100
        PREF_BOND -> 10
        else -> 0
    }

    private fun loadFromDisk() {
        val p = p()
        val first = !p.contains(PREF_HUNGER)
        if (first) {
            p.edit().putInt(PREF_HUNGER, 70).putInt(PREF_HAPPY, 70).putInt(PREF_CLEAN, 70)
                .putInt(PREF_ENERGY, 70).putInt(PREF_FUN, 70).putInt(PREF_SLEEPINESS, 20)
                .putInt(PREF_AFFECTION, 20).putInt(PREF_HEALTH, 100).putInt(PREF_BOND, 10)
                .putLong(PREF_LAST_TICK, System.currentTimeMillis()).putLong(PREF_LAST_OPEN, System.currentTimeMillis()).apply()
            welcomePending = false
        } else {
            welcomePending = p.getLong(PREF_LAST_OPEN, 0L) > 0L
        }
        // Migration for older builds that had only four needs.
        if (!p.contains(PREF_FUN)) p.edit().putInt(PREF_FUN, 70).apply()
        if (!p.contains(PREF_SLEEPINESS)) p.edit().putInt(PREF_SLEEPINESS, 20).apply()
        if (!p.contains(PREF_AFFECTION)) p.edit().putInt(PREF_AFFECTION, p.getInt(PREF_BOND, 10)).apply()
        if (!p.contains(PREF_HEALTH)) p.edit().putInt(PREF_HEALTH, 100).apply()
    }

    private fun applyPassiveDecay() {
        val pref = prefs ?: return
        val now = System.currentTimeMillis()
        val storedLast = pref.getLong(PREF_LAST_TICK, now)
        val safeLast = if (storedLast <= 0L) now else storedLast
        val elapsed = (now - safeLast).coerceAtLeast(0L)
        val rawSteps = (elapsed / DECAY_INTERVAL_MS).toInt().coerceAtLeast(0)
        val steps = rawSteps.coerceAtMost(MAX_OFFLINE_STEPS)
        if (steps <= 0) return

        val sleeping = state == BitPetState.SLEEPING || sleepiness() > 82
        val hDelta = if (sleeping) -1 else -2
        val happyDelta = -1
        val cleanDelta = -1
        val funDelta = if (sleeping) -1 else -2
        val energyDelta = if (sleeping) 3 else -1
        val sleepyDelta = if (sleeping) -3 else 2

        fun next(key: String, delta: Int, default: Int = defaultFor(key)): Int {
            val current = pref.getInt(key, default)
            return (current + delta * steps).coerceIn(0, 100)
        }

        val nextHunger = next(PREF_HUNGER, hDelta)
        val nextHappy = next(PREF_HAPPY, happyDelta)
        val nextClean = next(PREF_CLEAN, cleanDelta)
        val nextFun = next(PREF_FUN, funDelta)
        val nextEnergy = next(PREF_ENERGY, energyDelta)
        val nextSleepiness = next(PREF_SLEEPINESS, sleepyDelta)
        val nextAffection = if (nextFun < 25) {
            (affection() - 1).coerceIn(0, 100)
        } else affection()

        var nextHealth = health()
        if (nextHunger < 20 || nextClean < 15 || nextEnergy < 12) nextHealth = (nextHealth - 1).coerceIn(0, 100)
        else if (nextHunger > 60 && nextClean > 60 && nextEnergy > 60) nextHealth = (nextHealth + 1).coerceIn(0, 100)
        val penalty = listOf(nextHunger, nextClean, nextEnergy, nextFun).count { it < 15 }
        nextHealth = when {
            penalty >= 2 -> (nextHealth - 2).coerceIn(0, 100)
            penalty == 0 && nextHealth < 100 -> (nextHealth + 1).coerceIn(0, 100)
            else -> nextHealth
        }

        // Commit every tick value + timestamp in one editor so process death cannot
        // advance lastTick while leaving only part of the stat updates persisted.
        val consumedMs = steps.toLong() * DECAY_INTERVAL_MS
        val nextLast = if (rawSteps > MAX_OFFLINE_STEPS) now else safeLast + consumedMs
        pref.edit()
            .putInt(PREF_HUNGER, nextHunger)
            .putInt(PREF_HAPPY, nextHappy)
            .putInt(PREF_CLEAN, nextClean)
            .putInt(PREF_ENERGY, nextEnergy)
            .putInt(PREF_FUN, nextFun)
            .putInt(PREF_SLEEPINESS, nextSleepiness)
            .putInt(PREF_AFFECTION, nextAffection)
            .putInt(PREF_HEALTH, nextHealth)
            .putLong(PREF_LAST_TICK, nextLast)
            .apply()
    }

    private fun updateHealthFromNeeds() {
        val penalty = listOf(hunger(), clean(), energy(), funLevel()).count { it < 15 }
        if (penalty >= 2) bump(PREF_HEALTH, -2)
        if (penalty == 0 && health() < 100) heal(1)
    }

    private fun evaluateMoodState(forceIdleMessage: Boolean = false) {
        if (state in activeStates() && !forceIdleMessage) return
        if (SystemClock.elapsedRealtime() < busyUntil && !forceIdleMessage) return
        mood = deriveMood()
        state = when (mood) {
            BitMood.SLEEPY -> BitPetState.SLEEPY
            BitMood.HUNGRY -> BitPetState.HUNGRY
            BitMood.SAD, BitMood.DIRTY -> BitPetState.SAD
            BitMood.BORED -> BitPetState.BORED
            BitMood.EXCITED -> BitPetState.EXCITED
            BitMood.CURIOUS -> BitPetState.CURIOUS
            else -> if (mood == BitMood.HAPPY || mood == BitMood.LOVE) BitPetState.HAPPY else BitPetState.IDLE
        }
        if (forceIdleMessage || lastMessage.isBlank() || lastMessage == "Bit siap menemani") {
            lastMessage = moodMessage()
        }
    }

    private fun deriveMood(): BitMood {
        val h = hunger(); val e = energy(); val f = funLevel(); val c = clean(); val s = sleepiness(); val a = affection(); val hp = happiness()
        return when {
            s >= 82 || e < 18 -> BitMood.SLEEPY
            h < 22 && e < 40 -> BitMood.IRRITATED
            h < 25 -> BitMood.HUNGRY
            c < 20 -> BitMood.DIRTY
            f < 22 -> BitMood.BORED
            hp < 25 || health() < 25 -> BitMood.SAD
            a >= 78 && hp >= 70 -> BitMood.LOVE
            hp >= 82 && f >= 70 -> BitMood.EXCITED
            f >= 75 && hp >= 65 -> BitMood.HAPPY
            Random.nextFloat() < 0.10f -> BitMood.CURIOUS
            else -> BitMood.HAPPY
        }
    }

    private fun moodMessage() = when (mood) {
        BitMood.HAPPY -> "Bit senang menemanimu 😊"
        BitMood.EXCITED -> "Bit lagi bersemangat! ✨"
        BitMood.SLEEPY -> "Bit mengantuk… 😴"
        BitMood.HUNGRY -> "Bit lapar… 🍎"
        BitMood.SAD -> "Bit ingin sedikit perhatian…"
        BitMood.BORED -> "Bit mulai bosan… 🎈"
        BitMood.DIRTY -> "Bit merasa agak kotor… 🫧"
        BitMood.IRRITATED -> "Bit agak rewel…"
        BitMood.CURIOUS -> "Bit penasaran melihat sekeliling 👀"
        BitMood.LOVE -> "Bit merasa dekat denganmu 🥰"
        BitMood.SURPRISED -> "Eh! 😳"
    }

    private fun maybeAutonomousBehavior() {
        if (SystemClock.elapsedRealtime() < busyUntil || state in activeStates()) return
        val act = activity ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutonomousAt < 7000L) return
        lastAutonomousAt = now
        val face = act.bitFace

        // Need-driven priority: needs are behavior, not decorative numbers.
        when {
            sleepiness() >= 82 -> autonomousSleepy(face)
            hunger() < 24 -> autonomousHungry(face)
            clean() < 20 -> autonomousDirty(face)
            funLevel() < 22 -> autonomousBored(face)
            affection() < 18 && happiness() < 45 -> autonomousAttention(face)
            else -> autonomousIdle(face)
        }
        notifyListeners()
    }

    private fun autonomousSleepy(face: LottieAnimationView?) {
        state = BitPetState.SLEEPY; mood = BitMood.SLEEPY; lastMessage = "Bit menguap… kasur kelihatan nyaman 😴"
        BitSoftMotion.softSquash(face, 0.035f, 420L) { BitSoftMotion.softSettle(face) }
        activity?.bitAnim?.playSad(face)
    }
    private fun autonomousHungry(face: LottieAnimationView?) {
        state = BitPetState.HUNGRY; mood = BitMood.HUNGRY; lastMessage = "Bit mencari sesuatu untuk dimakan… 🍎"
        BitSoftMotion.softSway(face, 2.8f, 1300L)
        activity?.bitAnim?.lookAt(face?.x ?: 0f, (face?.y ?: 0f) + (face?.height ?: 0), 650L)
    }
    private fun autonomousDirty(face: LottieAnimationView?) {
        state = BitPetState.DIRTY; mood = BitMood.DIRTY; lastMessage = "Bit merasa kurang nyaman… 🫧"
        BitSoftMotion.softShake(face, 2.5f, 500L) { BitSoftMotion.softSettle(face) }
    }
    private fun autonomousBored(face: LottieAnimationView?) {
        state = BitPetState.BORED; mood = BitMood.BORED; lastMessage = "Bit bosan… ayo main sebentar? 🎈"
        BitSoftMotion.softSway(face, 3.5f, 1800L)
    }
    private fun autonomousAttention(face: LottieAnimationView?) {
        lastMessage = "Bit melihatmu… 👀"; mood = BitMood.CURIOUS; state = BitPetState.CURIOUS
        activity?.bitAnim?.playWink(face)
        BitSoftMotion.softBounce(face, 1.035f, 500L)
    }
    private fun autonomousIdle(face: LottieAnimationView?) {
        val tod = timeOfDay()
        when {
            tod == BitTimeOfDay.MORNING -> { lastMessage = "Bit bangun dan meregangkan badan ☀️"; BitSoftMotion.softStretch(face, 0.055f, 650L) }
            tod == BitTimeOfDay.NIGHT || tod == BitTimeOfDay.LATE_NIGHT -> { lastMessage = "Bit mulai mengantuk… 🌙"; BitSoftMotion.softSway(face, 1.8f, 1900L) }
            Random.nextFloat() < 0.35f -> { lastMessage = "Bit melihat sekeliling… 👀"; activity?.bitAnim?.lookAt(0f, 0f, 1200L) }
            Random.nextFloat() < 0.45f -> { lastMessage = "Bit melakukan idle kecil ✨"; BitSoftMotion.softBounce(face, 1.025f, 520L) }
            else -> { lastMessage = moodMessage(); BitSoftMotion.softSway(face, 1.7f, 2000L) }
        }
    }

    private fun activeStates() = setOf(BitPetState.EATING, BitPetState.PLAYING, BitPetState.BATHING, BitPetState.SLEEPING, BitPetState.WAKE_UP, BitPetState.INTERACTING)
    private fun transition(to: BitPetState, msg: String) { state = to; lastMessage = msg }
    private fun notifyListeners() { val snap = snapshot(); listeners.toList().forEach { runCatching { it(snap) } } }
    private fun fail(msg: String) = BitPetResult(false, msg, false, levelOf(xp()), snapshot().copy(message = msg))

    private fun rememberActivity(name: String) {
        val p = p(); increment(PREF_INTERACTIONS)
        val current = p.getInt("bit_pref_$name", 0) + 1
        p.edit().putInt("bit_pref_$name", current).apply()
        val fav = listOf("Elus", "Makan", "Main", "Mandi", "Tidur")
            .maxByOrNull { p.getInt("bit_pref_$it", 0) }
        if (fav != null && p.getInt("bit_pref_$fav", 0) >= 3) p.edit().putString(PREF_FAV_ACTIVITY, fav).apply()
    }

    fun rememberFood(foodName: String) {
        val p = p(); val key = "bit_food_$foodName"; val count = p.getInt(key, 0) + 1
        p.edit().putInt(key, count).apply()
        val known = listOf("Apel", "Kue", "Pizza", "Wortel", "Lemon", "Cookie", "Es krim")
        val fav = known.maxByOrNull { p.getInt("bit_food_$it", 0) }
        if (fav != null && p.getInt("bit_food_$fav", 0) >= 2) p.edit().putString(PREF_FAV_FOOD, fav).apply()
    }

    fun timeOfDay(): BitTimeOfDay {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when { h in 5..9 -> BitTimeOfDay.MORNING; h in 10..16 -> BitTimeOfDay.DAY; h in 17..20 -> BitTimeOfDay.EVENING; h in 21..23 -> BitTimeOfDay.NIGHT; else -> BitTimeOfDay.LATE_NIGHT }
    }

    private fun animatePet(face: LottieAnimationView?) { activity?.bitAnim?.playLove(face); BitSoftMotion.scenePet(face) }
    private fun animateEat(face: LottieAnimationView?) { activity?.bitAnim?.playTap(face); BitSoftMotion.sceneEat(face) }
    private fun animatePlay(face: LottieAnimationView?) { activity?.bitAnim?.playBump(face); BitSoftMotion.scenePlay(face) }
    private fun animateBath(face: LottieAnimationView?) { activity?.bitAnim?.playTap(face); BitSoftMotion.sceneBath(face) }
    private fun animateSleep(face: LottieAnimationView?) { activity?.bitAnim?.playSleep(); BitSoftMotion.sceneSleepEnter(face) }
    private fun animateWake(face: LottieAnimationView?) { activity?.bitAnim?.playIdle(face); BitSoftMotion.softWakeUp(face) }
    private fun animateLevelUp(face: LottieAnimationView?) { activity?.bitAnim?.playLove(face); BitSoftMotion.sceneLevelUp(face) }
}
