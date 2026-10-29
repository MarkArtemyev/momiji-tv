package app.momiji.home.display

/**
 * What the TV panel reports and how Momiji uses it. Pure logic with no Android types, so every choice
 * is checked on the JVM (tests/home-display); DisplayProbe fills [ScreenFacts] from Android APIs.
 *
 * Android lets an app change only part of a TV's picture: its own window (colour mode, refresh rate),
 * and, after a one-time ADB grant of WRITE_SECURE_SETTINGS, three system-wide display settings.
 * Local dimming, backlight and picture modes live in the vendor's firmware; [advice] covers those.
 */
enum class HdrType(val label: String) { DOLBY_VISION("Dolby Vision"), HDR10("HDR10"), HLG("HLG"), HDR10_PLUS("HDR10+") }

/** [seamless]: refresh rates the panel can switch to from this mode without blanking (Android 12+). */
data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float, val seamless: Set<Float> = emptySet())

/** Current values of the system settings Momiji may change; null when the system does not let Momiji read them. */
data class SystemSettings(val matchFrameRate: Int? = null, val hdrFormatsAuto: Int? = null, val allm: Int? = null)

data class ScreenFacts(
    val device: String,
    val sdk: Int,
    val current: Mode,
    val modes: List<Mode>,
    val hdr: Set<HdrType>,
    val peakNits: Float? = null,
    val averageNits: Float? = null,
    val wideColor: Boolean = false,
    val hdrScreen: Boolean = false,
    val allmSupported: Boolean = false,
    val settings: SystemSettings = SystemSettings(),
    val canWriteSettings: Boolean = false,
)

enum class Panel { BRIGHT_HDR, HDR, SDR }

enum class Preset(val title: String, val subtitle: String) {
    SYSTEM("Как в системе", "Momiji ничего не меняет в экране"),
    CINEMA("Mini-LED · кино в темноте", "Частота под фильм, все HDR, спокойный фон без бликов"),
    DAY("Mini-LED · светлая комната", "Плавный интерфейс, все HDR, бесшовная частота"),
    GAME("Mini-LED · игры", "Игровой режим ALLM, плавный интерфейс, бесшовная частота"),
}

/**
 * [calmAmbient] turns Home's particle effects off: small bright dots moving over a dark background are
 * exactly what local-dimming zones handle worst, and each one drags a halo (blooming) with it.
 */
data class Tuning(val maxRefresh: Boolean, val wideColor: Boolean, val hdrWallpaper: Boolean, val calmAmbient: Boolean,
    val matchFrameRate: Int?, val allHdrFormats: Boolean, val allm: Boolean)

enum class Table { SECURE, GLOBAL }

data class SettingChange(val table: Table, val key: String, val value: Int, val label: String)

object ScreenPlan {
    // Settings.Secure.MATCH_CONTENT_FRAME_RATE values, as in Android TV's "Match content frame rate".
    const val MATCH_NEVER = 0
    const val MATCH_SEAMLESS = 1
    const val MATCH_ALWAYS = 2
    const val KEY_MATCH = "match_content_frame_rate"
    const val KEY_HDR_AUTO = "are_user_disabled_hdr_formats_allowed"
    const val KEY_ALLM = "minimal_post_processing_allowed"

    fun tuning(preset: Preset): Tuning = when (preset) {
        Preset.SYSTEM -> Tuning(false, false, false, false, null, false, false)
        Preset.CINEMA -> Tuning(true, true, true, true, MATCH_ALWAYS, true, true)
        Preset.DAY -> Tuning(true, true, true, false, MATCH_SEAMLESS, true, true)
        Preset.GAME -> Tuning(true, true, false, false, MATCH_SEAMLESS, true, true)
    }

    fun panel(facts: ScreenFacts): Panel = when {
        facts.hdr.isNotEmpty() && (facts.peakNits ?: 0f) >= 1000f -> Panel.BRIGHT_HDR
        facts.hdr.isNotEmpty() || facts.hdrScreen -> Panel.HDR
        else -> Panel.SDR
    }

    /**
     * The fastest refresh rate at the current resolution that the panel reaches without blanking.
     * Null when there is nothing faster, or before Android 12, where Android cannot tell a seamless switch apart.
     */
    fun refreshMode(facts: ScreenFacts): Mode? {
        if (facts.sdk < 31) return null
        val current = facts.current
        val best = facts.modes.filter {
            it.width == current.width && it.height == current.height && it.refreshRate > current.refreshRate + 0.5f &&
                current.seamless.any { rate -> kotlin.math.abs(rate - it.refreshRate) < 0.5f }
        }.maxByOrNull { it.refreshRate }
        return best
    }

    /** System settings the preset still needs, skipping values that already match and settings this Android lacks. */
    fun changes(preset: Preset, facts: ScreenFacts): List<SettingChange> {
        val tuning = tuning(preset)
        val wanted = ArrayList<SettingChange>()
        if (tuning.matchFrameRate != null && facts.sdk >= 31)
            wanted.add(SettingChange(Table.SECURE, KEY_MATCH, tuning.matchFrameRate, "Подстройка частоты под видео · ${matchLabel(tuning.matchFrameRate)}"))
        if (tuning.allHdrFormats && facts.sdk >= 31 && facts.hdr.isNotEmpty())
            wanted.add(SettingChange(Table.GLOBAL, KEY_HDR_AUTO, 1, "HDR-форматы · все, что поддерживает ТВ"))
        if (tuning.allm && facts.sdk >= 30 && facts.allmSupported)
            wanted.add(SettingChange(Table.SECURE, KEY_ALLM, 1, "Игровой режим ALLM · разрешён"))
        return wanted.filter { current(facts.settings, it.key) != it.value }
    }

    fun current(settings: SystemSettings, key: String): Int? = when (key) {
        KEY_MATCH -> settings.matchFrameRate
        KEY_HDR_AUTO -> settings.hdrFormatsAuto
        KEY_ALLM -> settings.allm
        else -> null
    }

    fun matchLabel(value: Int?): String = when (value) {
        MATCH_NEVER -> "никогда"
        MATCH_SEAMLESS -> "только бесшовно"
        MATCH_ALWAYS -> "всегда, даже с паузой"
        else -> "неизвестно"
    }

    fun report(facts: ScreenFacts): String {
        val lines = ArrayList<String>()
        lines.add(facts.device)
        lines.add("Режим: ${facts.current.width}×${facts.current.height} · ${hz(facts.current.refreshRate)}")
        val rates = facts.modes.filter { it.width == facts.current.width && it.height == facts.current.height }.map { it.refreshRate }
        // 23.976 and 24 Hz are different modes: a film on the wrong one stutters every few tens of seconds.
        if (rates.isNotEmpty()) lines.add("Частоты при этом разрешении: ${rates.distinctBy { Math.round(it * 100) }.sorted().joinToString(", ") { hz(it) }}")
        if (facts.sdk >= 31) {
            val seamless = facts.current.seamless.filter { kotlin.math.abs(it - facts.current.refreshRate) >= 0.5f }
            lines.add("Без паузы переключается на: ${if (seamless.isEmpty()) "нет других частот" else seamless.sorted().joinToString(", ") { hz(it) }}")
        }
        val resolutions = facts.modes.map { "${it.width}×${it.height}" }.distinct()
        if (resolutions.size > 1) lines.add("Разрешения: ${resolutions.joinToString(", ")}")
        lines.add("HDR: ${if (facts.hdr.isEmpty()) "система не сообщает" else facts.hdr.sortedBy { it.ordinal }.joinToString(", ") { it.label }}")
        lines.add("Яркость по данным панели: " + when {
            facts.peakNits == null -> "не сообщается"
            facts.averageNits != null -> "пик ${facts.peakNits.toInt()} нит, средняя ${facts.averageNits.toInt()} нит"
            else -> "пик ${facts.peakNits.toInt()} нит"
        })
        lines.add("Широкий цветовой охват: ${if (facts.wideColor) "да" else "нет"}")
        lines.add("Игровой режим ALLM: ${if (facts.allmSupported) "поддерживается" else "нет"}")
        if (facts.sdk >= 31) lines.add("Подстройка частоты под видео: ${matchLabel(facts.settings.matchFrameRate)}")
        if (facts.sdk >= 31 && facts.hdr.isNotEmpty()) lines.add("Выбор HDR-форматов: " + when (facts.settings.hdrFormatsAuto) {
            1 -> "автоматически, все"; 0 -> "вручную, часть может быть выключена"; else -> "неизвестно" })
        lines.add("")
        lines.add(when (panel(facts)) {
            Panel.BRIGHT_HDR -> "Яркая HDR-панель: так обычно отчитываются Mini-LED и QD-Mini LED. Пресеты Mini-LED подходят."
            Panel.HDR -> "HDR-панель. Mini-LED система не называет: если у тебя Mini-LED, пресеты всё равно подходят."
            Panel.SDR -> "Система не сообщает о HDR. Пресеты помогут с частотой, но HDR-пункты будут пропущены."
        })
        return lines.joinToString("\n")
    }

    /** What to set in the TV's own picture menu, which no Android app can reach. */
    fun advice(preset: Preset, facts: ScreenFacts): String {
        val dolby = HdrType.DOLBY_VISION in facts.hdr
        val fast = facts.modes.any { it.refreshRate >= 100f }
        val where = "Настройки → Экран и звук → Дополнительные настройки экрана"
        fun matchLine(value: Int, effect: String) = if (facts.canWriteSettings) "• Momiji включает подстройку частоты «${matchLabel(value)}»: $effect"
            else "• Подстройка частоты под видео: «${matchLabel(value)}» ($where). Momiji сделает это сам после доступа по ADB."
        val lines = when (preset) {
            Preset.SYSTEM -> listOf("Momiji не трогает экран. Выбери пресет Mini-LED, чтобы получить подсказки для меню изображения.")
            Preset.CINEMA -> listOfNotNull(
                "• Режим изображения: «Кино», «Filmmaker» или «Калиброванный». Не «Яркий» и не «Динамический»: они съедают детали в тенях.",
                "• Локальное затемнение: высокое. Если вокруг субтитров и логотипов заметно свечение, поставь среднее.",
                "• Подсветка для обычного видео: 30–50 % в тёмной комнате. Для HDR оставь максимум: пиковая яркость и есть смысл Mini-LED.",
                "• Цветовая температура: «Тёплая» (Warm 2). Она ближе всего к эталону D65.",
                "• Сглаживание движения (MEMC, Motion Clarity): выкл., иначе фильм выглядит как сериал. Шумоподавление: выкл.",
                if (dolby) "• Dolby Vision: «Тёмный» (Dark), или «IQ», если есть датчик освещённости." else null,
                "• Тонмаппинг HDR10: динамический.",
                matchLine(MATCH_ALWAYS, "плееры, которые просят точную частоту фильма, переводят экран на 24 Гц с секундной паузой, и в панорамах нет рывков."))
            Preset.DAY -> listOfNotNull(
                "• Режим изображения: «Стандартный» или дневное «Кино».",
                "• Локальное затемнение: среднее. Датчик освещённости, если он есть: включи.",
                "• Подсветка: 70–100 %. Днём Mini-LED может себе это позволить.",
                if (dolby) "• Dolby Vision: «Яркий» (Bright) или «IQ»." else null,
                "• Сглаживание движения: низкое для спорта, выкл. для фильмов.",
                matchLine(MATCH_SEAMLESS, "частота меняется только без пауз при запуске видео."))
            Preset.GAME -> listOfNotNull(
                if (facts.allmSupported) "• Игровой режим: «Авто». Momiji разрешает ALLM, и консоль сама включит режим с минимальной задержкой."
                else "• Игровой режим: включи вручную для входа консоли. ТВ не сообщает о поддержке ALLM.",
                "• Для HDR-игр: HGiG, если есть, или тонмаппинг «Выкл.». Яркость тогда настраивает сама игра.",
                if (fast) "• Подключай консоль к порту HDMI 2.1 (обычно HDMI 3 или 4, 120 Гц / VRR) и включи для порта «Расширенный формат HDMI»." else null,
                "• Локальное затемнение: среднее. Высокое даёт свечение вокруг прицела и интерфейса.",
                "• Сглаживание движения и шумоподавление: выкл., они добавляют задержку.")
        }
        val footer = "\nЭти пункты есть в меню изображения самого ТВ (кнопка настроек на пульте). Сторонние приложения на Android не могут менять подсветку и локальное затемнение: их хранит прошивка производителя."
        return lines.joinToString("\n") + if (preset == Preset.SYSTEM) "" else "\n$footer"
    }

    private fun hz(rate: Float): String =
        if (kotlin.math.abs(rate - Math.round(rate)) < 0.005f) "${Math.round(rate)} Гц" else String.format(java.util.Locale.ROOT, "%.2f Гц", rate).replace('.', ',')
}
