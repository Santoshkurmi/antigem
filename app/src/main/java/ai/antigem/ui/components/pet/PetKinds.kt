package ai.antigem.ui.components.pet

import ai.antigem.i18n.L
import org.json.JSONObject

/** Every pet. [id] is what Settings saves; [emoji] follows its name in "I'm …!", [treatEmoji] is on the menu's Treat chip. */
enum class PetKind(val id: String, val emoji: String, val treatEmoji: String, val specialEmoji: String) {
    GEMMY("gemmy", "💜", "⭐", "✨"),
    KITSU("kitsu", "🦊", "🍣", "🔥"),
    FROST("frost", "🐻‍❄️", "🐟", "🪓"),
    BEEP("beep", "🤖", "🔋", "📡"),
    MOCHI("mochi", "🐱", "🥛", "😽"),
    CINDER("cinder", "🐉", "🌶️", "🔥"),
    MIST("mist", "👻", "🍬", "🌫️"),
    LOTL("lotl", "🦎", "🦐", "🫧");

    val displayName: String get() = when (this) {
        GEMMY -> L.pets.gemmy.name
        KITSU -> L.pets.kitsu.name
        FROST -> L.pets.frost.name
        BEEP -> L.pets.beep.name
        MOCHI -> L.pets.mochi.name
        CINDER -> L.pets.cinder.name
        MIST -> L.pets.mist.name
        LOTL -> L.pets.lotl.name
    }

    /** A few words under the name in the pet grid. */
    val tag: String get() = when (this) {
        GEMMY -> L.pets.gemmy.tag
        KITSU -> L.pets.kitsu.tag
        FROST -> L.pets.frost.tag
        BEEP -> L.pets.beep.tag
        MOCHI -> L.pets.mochi.tag
        CINDER -> L.pets.cinder.tag
        MIST -> L.pets.mist.tag
        LOTL -> L.pets.lotl.tag
    }

    /** What it is and does (the pet's own settings page). */
    val about: String get() = when (this) {
        GEMMY -> L.pets.gemmy.about
        KITSU -> L.pets.kitsu.about
        FROST -> L.pets.frost.about
        BEEP -> L.pets.beep.about
        MOCHI -> L.pets.mochi.about
        CINDER -> L.pets.cinder.about
        MIST -> L.pets.mist.about
        LOTL -> L.pets.lotl.about
    }

    /** The color sets (palette index = position); each pet's art has the same count. */
    val lookNames: List<String> get() = when (this) {
        GEMMY -> listOf(L.pets.looks.classic, L.pets.looks.mint, L.pets.looks.sunset, L.pets.looks.midnight)
        KITSU -> listOf(L.pets.looks.amber, L.pets.looks.snow, L.pets.looks.spirit, L.pets.looks.shadow)
        FROST -> listOf(L.pets.looks.polar, L.pets.looks.arctic, L.pets.looks.honey, L.pets.looks.cocoa)
        BEEP -> listOf(L.pets.looks.silver, L.pets.looks.retro, L.pets.looks.rose, L.pets.looks.gold)
        MOCHI -> listOf(L.pets.looks.snow, L.pets.looks.ginger, L.pets.looks.tuxedo, L.pets.looks.tabby)
        CINDER -> listOf(L.pets.looks.ember, L.pets.looks.jade, L.pets.looks.sapphire, L.pets.looks.amethyst)
        MIST -> listOf(L.pets.looks.classic, L.pets.looks.mint, L.pets.looks.lilac, L.pets.looks.pumpkin)
        LOTL -> listOf(L.pets.looks.pink, L.pets.looks.wild, L.pets.looks.gold, L.pets.looks.blue)
    }

    /** The pet's one extra choice (tail count, axe, bell), or null. */
    val optionLabel: String? get() = when (this) {
        KITSU -> L.pets.kitsu.option
        FROST -> L.pets.frost.option
        MOCHI -> L.pets.mochi.option
        else -> null
    }

    val optionValues: List<String> get() = when (this) {
        KITSU -> listOf("1", "3", "5", "7", "9")
        FROST, MOCHI -> listOf(L.common.on, L.common.off)
        else -> emptyList()
    }

    val defaultOption: Int get() = when (this) {
        KITSU -> 4 // nine tails
        else -> 0
    }

    /** The signature move's name on the long-press menu and in Settings. */
    fun specialLabel(look: PetLook): String = when (this) {
        GEMMY -> L.pets.gemmy.special
        KITSU -> L.pets.kitsu.special
        FROST -> if (look.option(this) == 0) L.pets.frost.special else L.pets.frost.specialNoAxe
        BEEP -> L.pets.beep.special
        MOCHI -> L.pets.mochi.special
        CINDER -> L.pets.cinder.special
        MIST -> L.pets.mist.special
        LOTL -> L.pets.lotl.special
    }

    companion object {
        fun of(id: String?): PetKind = entries.firstOrNull { it.id == id } ?: GEMMY
    }
}

/** One pet's look: color set, its extra option (-1 = the pet's default) and a nickname ("" = its own name). */
data class PetLook(val palette: Int = 0, val option: Int = -1, val nickname: String = "") {
    fun option(kind: PetKind): Int = if (option in kind.optionValues.indices) option else kind.defaultOption
    fun name(kind: PetKind): String = nickname.trim().ifEmpty { kind.displayName }
}

/** Ready-made battery / smoothness settings: still after [restAfter] s (0 = never), hover and moving frame rates. */
enum class PetPower(val restAfter: Int, val idleFps: Int, val moveFps: Int) {
    SAVER(2, 20, 30),
    BALANCED(5, 30, 0),
    LIVELY(15, 30, 0),
    ALWAYS(0, 30, 0)
}

/**
 * Settings → Pets (saved as one JSON text). On/off stays the old "Gemmy enabled" switch, so existing choices carry over.
 * [restAfterS] / [sleepAfterS] 0 = never; [moveFps] 0 = every screen frame; [activity] 0 off … 3 often.
 */
data class PetSettings(
    val kind: PetKind = PetKind.GEMMY,
    val size: Float = 1f,
    val opacity: Float = 1f,
    val activity: Int = 2,
    val wander: Boolean = true,
    val sleepAfterS: Int = 50,
    val speech: Boolean = true,
    val bubbleTime: Float = 1f,
    val agentReact: Boolean = true,
    val haptics: Boolean = true,
    val effects: Boolean = true,
    val hideWithKeyboard: Boolean = false,
    val restAfterS: Int = 5,
    val idleFps: Int = 30,
    val moveFps: Int = 0,
    val looks: Map<PetKind, PetLook> = emptyMap()
) {
    fun look(k: PetKind = kind): PetLook = looks[k] ?: PetLook()
    fun withLook(k: PetKind, look: PetLook) = copy(looks = looks + (k to look))

    /** The preset these values match, or null (custom). */
    val power: PetPower? get() = PetPower.entries.firstOrNull { it.restAfter == restAfterS && it.idleFps == idleFps && it.moveFps == moveFps }
    fun withPower(p: PetPower) = copy(restAfterS = p.restAfter, idleFps = p.idleFps, moveFps = p.moveFps)

    internal fun tuning() = PetTuning(
        restAfterS = if (restAfterS <= 0) NEVER_S else restAfterS.toFloat(),
        sleepAfterS = if (sleepAfterS <= 0) NEVER_S else sleepAfterS.toFloat(),
        trickEvery = when (activity) {
            0 -> 0f
            1 -> 2f
            3 -> 0.5f
            else -> 1f
        },
        wander = wander,
        speech = speech,
        bubbleTime = bubbleTime,
        agentReact = agentReact,
        haptics = haptics,
        effects = effects,
        idleFps = idleFps,
        moveFps = moveFps
    )

    fun toJson(): String = JSONObject().apply {
        put("kind", kind.id)
        put("size", size.toDouble())
        put("opacity", opacity.toDouble())
        put("activity", activity)
        put("wander", wander)
        put("sleepAfter", sleepAfterS)
        put("speech", speech)
        put("bubble", bubbleTime.toDouble())
        put("agent", agentReact)
        put("haptics", haptics)
        put("effects", effects)
        put("hideKb", hideWithKeyboard)
        put("restAfter", restAfterS)
        put("idleFps", idleFps)
        put("moveFps", moveFps)
        put("looks", JSONObject().apply {
            looks.forEach { (k, l) -> put(k.id, JSONObject().put("p", l.palette).put("o", l.option).put("n", l.nickname)) }
        })
    }.toString()

    companion object {
        val SIZES = 0.6f..1.6f
        val OPACITIES = 0.35f..1f
        val REST_AFTER = listOf(2, 5, 10, 15, 30, 60, 0)
        val SLEEP_AFTER = listOf(30, 50, 120, 300, 0)
        val IDLE_FPS = listOf(15, 20, 30, 60)
        val MOVE_FPS = listOf(30, 60, 0)

        fun fromJson(json: String?): PetSettings {
            if (json.isNullOrBlank()) return PetSettings()
            return runCatching {
                val o = JSONObject(json)
                val looks = HashMap<PetKind, PetLook>()
                o.optJSONObject("looks")?.let { lo ->
                    lo.keys().forEach { id ->
                        val k = PetKind.entries.firstOrNull { it.id == id } ?: return@forEach
                        val l = lo.optJSONObject(id) ?: return@forEach
                        looks[k] = PetLook(
                            palette = l.optInt("p", 0).coerceIn(0, 3),
                            option = l.optInt("o", -1),
                            nickname = l.optString("n", "").take(24)
                        )
                    }
                }
                PetSettings(
                    kind = PetKind.of(o.optString("kind")),
                    size = o.optDouble("size", 1.0).toFloat().coerceIn(SIZES.start, SIZES.endInclusive),
                    opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(OPACITIES.start, OPACITIES.endInclusive),
                    activity = o.optInt("activity", 2).coerceIn(0, 3),
                    wander = o.optBoolean("wander", true),
                    sleepAfterS = o.optInt("sleepAfter", 50).coerceAtLeast(0),
                    speech = o.optBoolean("speech", true),
                    bubbleTime = o.optDouble("bubble", 1.0).toFloat().coerceIn(0.5f, 2.5f),
                    agentReact = o.optBoolean("agent", true),
                    haptics = o.optBoolean("haptics", true),
                    effects = o.optBoolean("effects", true),
                    hideWithKeyboard = o.optBoolean("hideKb", false),
                    restAfterS = o.optInt("restAfter", 5).coerceAtLeast(0),
                    idleFps = o.optInt("idleFps", 30).coerceIn(10, 120),
                    moveFps = o.optInt("moveFps", 0).coerceIn(0, 120),
                    looks = looks
                )
            }.getOrDefault(PetSettings())
        }
    }
}

/** Builds the art for [kind] in [look] at [d] px per dp (already times the user's size). */
internal fun createPetArt(kind: PetKind, look: PetLook, d: Float): PetArt {
    val p = look.palette.coerceIn(0, 3)
    val o = look.option(kind)
    return when (kind) {
        PetKind.GEMMY -> GemmyArt(d, p)
        PetKind.KITSU -> KitsuArt(d, p, tails = intArrayOf(1, 3, 5, 7, 9)[o.coerceIn(0, 4)])
        PetKind.FROST -> FrostArt(d, p, axe = o == 0)
        PetKind.BEEP -> BeepArt(d, p)
        PetKind.MOCHI -> MochiArt(d, p, bell = o == 0)
        PetKind.CINDER -> CinderArt(d, p)
        PetKind.MIST -> MistArt(d, p)
        PetKind.LOTL -> LotlArt(d, p)
    }
}

/** What a pet says. Shared lines for everyone, plus each pet's own hello, taps, signature move and treat. */
internal class PetLines(private val kind: PetKind, private val petName: String, private val axe: Boolean = true) {

    fun name(): String = L.pets.common.imName(petName, kind.emoji)

    fun hello(): String = when (kind) {
        PetKind.GEMMY -> listOf("Hi! 👋", L.components.floatingMascot.heyThere, L.components.floatingMascot.helloFriend).random()
        PetKind.KITSU -> listOf("Hi! 👋", L.pets.kitsu.hello).random()
        PetKind.FROST -> listOf("Hi! 👋", L.pets.frost.hello).random()
        PetKind.BEEP -> listOf("Hi! 👋", L.pets.beep.hello).random()
        PetKind.MOCHI -> listOf("Hi! 👋", L.pets.mochi.hello).random()
        PetKind.CINDER -> listOf("Hi! 👋", L.pets.cinder.hello).random()
        PetKind.MIST -> listOf("Hi! 👋", L.pets.mist.hello).random()
        PetKind.LOTL -> listOf("Hi! 👋", L.pets.lotl.hello).random()
    }

    fun tap(): String = (
        when (kind) {
            PetKind.GEMMY -> listOf(
                L.components.floatingMascot.heheThatTickles, L.components.floatingMascot.needAHand,
                L.components.floatingMascot.letsBuildSomethingCool, L.components.floatingMascot.bugsWhere,
                L.components.floatingMascot.youreDoingGreat, L.components.floatingMascot.coffeeBreak
            )
            PetKind.KITSU -> listOf(L.pets.kitsu.tap1, L.pets.kitsu.tap2, L.pets.kitsu.tap3, L.pets.kitsu.tap4, L.pets.kitsu.tap5)
            PetKind.FROST -> listOf(L.pets.frost.tap1, L.pets.frost.tap2, L.pets.frost.tap3, L.pets.frost.tap4, L.pets.frost.tap5)
            PetKind.BEEP -> listOf(L.pets.beep.tap1, L.pets.beep.tap2, L.pets.beep.tap3, L.pets.beep.tap4, L.pets.beep.tap5)
            PetKind.MOCHI -> listOf(L.pets.mochi.tap1, L.pets.mochi.tap2, L.pets.mochi.tap3, L.pets.mochi.tap4, L.pets.mochi.tap5)
            PetKind.CINDER -> listOf(L.pets.cinder.tap1, L.pets.cinder.tap2, L.pets.cinder.tap3, L.pets.cinder.tap4, L.pets.cinder.tap5)
            PetKind.MIST -> listOf(L.pets.mist.tap1, L.pets.mist.tap2, L.pets.mist.tap3, L.pets.mist.tap4, L.pets.mist.tap5)
            PetKind.LOTL -> listOf(L.pets.lotl.tap1, L.pets.lotl.tap2, L.pets.lotl.tap3, L.pets.lotl.tap4, L.pets.lotl.tap5)
        } + name()
        ).random()

    fun special(): String = when (kind) {
        PetKind.GEMMY -> L.pets.gemmy.specialLine
        PetKind.KITSU -> L.pets.kitsu.specialLine
        PetKind.FROST -> if (axe) L.pets.frost.specialLine else L.pets.frost.specialLineNoAxe
        PetKind.BEEP -> L.pets.beep.specialLine
        PetKind.MOCHI -> L.pets.mochi.specialLine
        PetKind.CINDER -> L.pets.cinder.specialLine
        PetKind.MIST -> L.pets.mist.specialLine
        PetKind.LOTL -> L.pets.lotl.specialLine
    }

    /** The second half of a two-part move (the ghost coming back, the bubble popping). */
    fun special2(): String = when (kind) {
        PetKind.MIST -> L.pets.mist.specialLine2
        PetKind.LOTL -> L.pets.lotl.pop
        else -> special()
    }

    fun eat(): String = listOf(
        L.pets.common.yum,
        when (kind) {
            PetKind.GEMMY -> L.pets.gemmy.eat
            PetKind.KITSU -> L.pets.kitsu.eat
            PetKind.FROST -> L.pets.frost.eat
            PetKind.BEEP -> L.pets.beep.eat
            PetKind.MOCHI -> L.pets.mochi.eat
            PetKind.CINDER -> L.pets.cinder.eat
            PetKind.MIST -> L.pets.mist.eat
            PetKind.LOTL -> L.pets.lotl.eat
        }
    ).random()

    fun work(): String = listOf(
        L.components.floatingMascot.onIt, "Thinking… 🤔", L.components.floatingMascot.agentIsCooking,
        when (kind) {
            PetKind.GEMMY -> L.components.floatingMascot.onIt
            PetKind.KITSU -> L.pets.kitsu.work
            PetKind.FROST -> L.pets.frost.work
            PetKind.BEEP -> L.pets.beep.work
            PetKind.MOCHI -> L.pets.mochi.work
            PetKind.CINDER -> L.pets.cinder.work
            PetKind.MIST -> L.pets.mist.work
            PetKind.LOTL -> L.pets.lotl.work
        }
    ).random()

    fun grab(): String = listOf("Woah! 😲", L.components.floatingMascot.whereAreWeGoing, L.components.floatingMascot.upWeGo, L.components.floatingMascot.carefulWithMe).random()
    fun fling(): String = listOf("Wheeee! 🚀", "Yaaay! 🎢", L.components.floatingMascot.woohoo).random()
    fun ouch(): String = listOf("Ouch! 💫", L.components.floatingMascot.oof, "Bonk! 🤕").random()
    fun dizzy(): String = listOf(L.components.floatingMascot.woahDizzy, L.components.floatingMascot.theRoomIsSpinning).random()
    fun angry(): String = listOf(L.components.floatingMascot.heyStopPoking, L.components.floatingMascot.grrThatsEnough).random()
    fun wake(): String = listOf(L.components.floatingMascot.huhImAwake, L.components.floatingMascot.fiveMoreMinutes, L.components.floatingMascot.whaOhHi).random()
    fun done(): String = listOf("Done! ✨", L.components.floatingMascot.taDa, L.components.floatingMascot.allFinished).random()
    fun dance(): String = L.components.floatingMascot.letsDance
    fun nap(): String = L.components.floatingMascot.napTime
    fun bye(): String = L.components.floatingMascot.byeBye

    companion object {
        val EMPTY get() = PetLines(PetKind.GEMMY, "Gemmy")
    }
}
