package com.example.gemini.data.voice

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One of Antigravity's voice-mode voices (the same list the Antigravity desktop app offers), with its orb colours. */
data class AgyVoice(
    val name: String,
    val tone: String,
    /** Orb colours as 0xRRGGBB: the dark edge, the body and the highlight. */
    val deep: Int,
    val core: Int,
    val glint: Int
)

/** Settings of Antigravity voice calls (voice, speed, speaker, captions). Every value is live for the call screen. */
object AgyVoicePreferences {
    val voices = listOf(
        AgyVoice("Fola", "Clear and friendly", 0x1E2E6C, 0x688EE4, 0xCEDEFA),
        AgyVoice("Arlo", "Confident and clear", 0x5C3C1C, 0xE4B468, 0xF8E4BC),
        AgyVoice("Cleo", "Warm and engaging", 0x50244E, 0xC87EC0, 0xF0D0EC),
        AgyVoice("Finn", "Warm and approachable", 0x1C4844, 0x68B0A2, 0xC8ECE2),
        AgyVoice("Gero", "Clear and friendly", 0x5E302E, 0xE29280, 0xF8D4C8),
        AgyVoice("Lora", "Clear and friendly", 0x1A445E, 0x72B6DC, 0xD0ECF8),
        AgyVoice("Neno", "Deep and velvety", 0x2A4026, 0x8CB274, 0xD8EAC8),
        AgyVoice("Olin", "Light and precise", 0x3A3068, 0x9E8EE2, 0xE0DAFA),
        AgyVoice("Tari", "Confident and clear", 0x4C481C, 0xC4BE60, 0xEEECC4)
    )

    /** Playback speeds the voice service accepts. */
    val speeds = listOf(0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    /** "auto" = the phone's languages. */
    const val LANGUAGE_AUTO = "auto"

    /** Languages offered for calls (BCP 47 tag → name in that language, English name). */
    val languages = listOf(
        Triple("en-US", "English", "English"),
        Triple("hi-IN", "हिन्दी", "Hindi"),
        Triple("ne-NP", "नेपाली", "Nepali"),
        Triple("bn-IN", "বাংলা", "Bengali"),
        Triple("ur-PK", "اردو", "Urdu"),
        Triple("mr-IN", "मराठी", "Marathi"),
        Triple("ta-IN", "தமிழ்", "Tamil"),
        Triple("te-IN", "తెలుగు", "Telugu"),
        Triple("gu-IN", "ગુજરાતી", "Gujarati"),
        Triple("kn-IN", "ಕನ್ನಡ", "Kannada"),
        Triple("ml-IN", "മലയാളം", "Malayalam"),
        Triple("pa-IN", "ਪੰਜਾਬੀ", "Punjabi"),
        Triple("es-ES", "Español", "Spanish"),
        Triple("fr-FR", "Français", "French"),
        Triple("de-DE", "Deutsch", "German"),
        Triple("pt-BR", "Português", "Portuguese"),
        Triple("it-IT", "Italiano", "Italian"),
        Triple("ru-RU", "Русский", "Russian"),
        Triple("ar-SA", "العربية", "Arabic"),
        Triple("id-ID", "Bahasa Indonesia", "Indonesian"),
        Triple("ja-JP", "日本語", "Japanese"),
        Triple("ko-KR", "한국어", "Korean"),
        Triple("zh-CN", "中文", "Chinese")
    )

    /** Short name of a language setting for the call screen. */
    fun languageLabel(tag: String): String =
        if (tag == LANGUAGE_AUTO) "Phone language" else languages.firstOrNull { it.first == tag }?.second ?: tag

    /**
     * The Accept-Language header a call sends: the hub takes the caller's language from it (as from a browser's).
     * A chosen language goes first, with English after it for words like code names; "auto" sends the phone's list.
     */
    fun acceptLanguage(tag: String): String {
        if (tag != LANGUAGE_AUTO) return if (tag.startsWith("en")) tag else "$tag,en;q=0.5"
        val tags = runCatching {
            val list = android.os.LocaleList.getAdjustedDefault()
            (0 until list.size()).map { list[it].toLanguageTag() }
        }.getOrDefault(emptyList()).filter { it.isNotBlank() && it != "und" }.distinct().take(5)
        if (tags.isEmpty()) return "en-US"
        return tags.mapIndexed { i, t -> if (i == 0) t else "$t;q=${"%.1f".format(java.util.Locale.US, 1.0 - i * 0.1)}" }.joinToString(",")
    }

    private var sp: SharedPreferences? = null

    private val _voice = MutableStateFlow(voices.first().name)
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    /** Start calls on the loudspeaker (off = earpiece, or the headset when one is connected). */
    private val _speaker = MutableStateFlow(true)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    private val _captions = MutableStateFlow(true)
    val captions: StateFlow<Boolean> = _captions.asStateFlow()

    /** The language calls use ([LANGUAGE_AUTO] or a tag from [languages]). */
    private val _language = MutableStateFlow(LANGUAGE_AUTO)
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(tag: String) {
        if (tag != LANGUAGE_AUTO && languages.none { it.first == tag }) return
        _language.value = tag
        sp?.edit()?.putString("language", tag)?.apply()
    }

    /** A voice was chosen in the voice picker (shown before the first call). */
    private val _picked = MutableStateFlow(false)
    val picked: StateFlow<Boolean> = _picked.asStateFlow()

    fun setPicked() {
        _picked.value = true
        sp?.edit()?.putBoolean("picked", true)?.apply()
    }

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("agy_voice_call", Context.MODE_PRIVATE)
        sp = p
        _voice.value = p.getString("voice", null)?.takeIf { n -> voices.any { it.name == n } } ?: voices.first().name
        _speed.value = p.getFloat("speed", 1f).toDouble().takeIf { it in speeds } ?: 1.0
        _speaker.value = p.getBoolean("speaker", true)
        _captions.value = p.getBoolean("captions", true)
        _picked.value = p.getBoolean("picked", false)
        _language.value = p.getString("language", LANGUAGE_AUTO)?.takeIf { t -> t == LANGUAGE_AUTO || languages.any { it.first == t } } ?: LANGUAGE_AUTO
    }

    fun voiceOf(name: String): AgyVoice = voices.firstOrNull { it.name == name } ?: voices.first()

    fun setVoice(name: String) {
        if (voices.none { it.name == name }) return
        _voice.value = name
        sp?.edit()?.putString("voice", name)?.apply()
    }

    fun setSpeed(speed: Double) {
        if (speed !in speeds) return
        _speed.value = speed
        sp?.edit()?.putFloat("speed", speed.toFloat())?.apply()
    }

    fun setSpeaker(on: Boolean) {
        _speaker.value = on
        sp?.edit()?.putBoolean("speaker", on)?.apply()
    }

    fun setCaptions(on: Boolean) {
        _captions.value = on
        sp?.edit()?.putBoolean("captions", on)?.apply()
    }
}
