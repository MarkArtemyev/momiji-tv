import app.momiji.home.display.*

/** Screen preset checks for Momiji Home: run with `./momiji-browser/gradlew -p tests/home-display displayCheck`. */
fun main() {
    var passed = 0
    fun test(name: String, action: () -> Unit) { action(); passed++; println("PASS $name") }

    // A 4K QD-Mini LED panel as Android 14 reports it: 60 Hz UI with a seamless 120 Hz, 24/50 Hz for film.
    val uhd60 = Mode(1, 3840, 2160, 60f, setOf(60f, 120f))
    val miniLed = ScreenFacts(
        device = "TCL 65C855 · Android 14", sdk = 34, current = uhd60,
        modes = listOf(uhd60, Mode(2, 3840, 2160, 120f, setOf(60f, 120f)), Mode(3, 3840, 2160, 24f), Mode(4, 3840, 2160, 50f), Mode(5, 1920, 1080, 144f)),
        hdr = setOf(HdrType.DOLBY_VISION, HdrType.HDR10, HdrType.HLG, HdrType.HDR10_PLUS), peakNits = 2600f, averageNits = 800f,
        wideColor = true, hdrScreen = true, allmSupported = true,
        settings = SystemSettings(matchFrameRate = ScreenPlan.MATCH_SEAMLESS, hdrFormatsAuto = 0, allm = 1), canWriteSettings = true,
    )

    test("presets_map_to_the_intended_tuning") {
        check(ScreenPlan.tuning(Preset.SYSTEM) == Tuning(false, false, false, false, null, false, false))
        val cinema = ScreenPlan.tuning(Preset.CINEMA)
        check(cinema.calmAmbient && cinema.matchFrameRate == ScreenPlan.MATCH_ALWAYS && cinema.allHdrFormats && cinema.wideColor)
        check(!ScreenPlan.tuning(Preset.DAY).calmAmbient && ScreenPlan.tuning(Preset.DAY).matchFrameRate == ScreenPlan.MATCH_SEAMLESS)
        check(ScreenPlan.tuning(Preset.GAME).allm && !ScreenPlan.tuning(Preset.GAME).hdrWallpaper)
    }

    test("refresh_rate_goes_up_only_seamlessly_at_the_same_resolution") {
        check(ScreenPlan.refreshMode(miniLed)?.id == 2)
        // 144 Hz exists only at 1080p, and 24 Hz is lower: neither is picked for the UI.
        check(ScreenPlan.refreshMode(miniLed.copy(current = uhd60.copy(seamless = setOf(60f)))) == null)
        check(ScreenPlan.refreshMode(miniLed.copy(sdk = 30)) == null)
        check(ScreenPlan.refreshMode(miniLed.copy(current = Mode(2, 3840, 2160, 120f, setOf(60f, 120f)))) == null)
    }

    test("system_changes_skip_matching_and_missing_settings") {
        val cinema = ScreenPlan.changes(Preset.CINEMA, miniLed)
        check(cinema.map { it.key } == listOf(ScreenPlan.KEY_MATCH, ScreenPlan.KEY_HDR_AUTO)) { cinema.toString() }
        check(cinema.first { it.key == ScreenPlan.KEY_MATCH }.value == ScreenPlan.MATCH_ALWAYS && cinema.first { it.key == ScreenPlan.KEY_HDR_AUTO }.table == Table.GLOBAL)
        check(ScreenPlan.changes(Preset.DAY, miniLed).map { it.key } == listOf(ScreenPlan.KEY_HDR_AUTO))
        check(ScreenPlan.changes(Preset.SYSTEM, miniLed).isEmpty())
        val unreadable = miniLed.copy(settings = SystemSettings())
        check(ScreenPlan.changes(Preset.GAME, unreadable).size == 3)
        check(ScreenPlan.changes(Preset.GAME, unreadable.copy(sdk = 30)).map { it.key } == listOf(ScreenPlan.KEY_ALLM))
        check(ScreenPlan.changes(Preset.GAME, unreadable.copy(allmSupported = false, hdr = emptySet())).map { it.key } == listOf(ScreenPlan.KEY_MATCH))
    }

    test("panel_classification_and_report") {
        check(ScreenPlan.panel(miniLed) == Panel.BRIGHT_HDR)
        check(ScreenPlan.panel(miniLed.copy(peakNits = 500f)) == Panel.HDR && ScreenPlan.panel(miniLed.copy(peakNits = null)) == Panel.HDR)
        check(ScreenPlan.panel(miniLed.copy(hdr = emptySet(), hdrScreen = false)) == Panel.SDR)
        val report = ScreenPlan.report(miniLed)
        for (part in listOf("3840×2160 · 60 Гц", "24 Гц, 50 Гц, 60 Гц, 120 Гц", "Без паузы переключается на: 120 Гц", "Dolby Vision, HDR10, HLG, HDR10+",
            "пик 2600 нит, средняя 800 нит", "ALLM: поддерживается", "только бесшовно", "вручную", "Mini-LED и QD-Mini LED"))
            check(part in report) { "missing '$part' in\n$report" }
        val fractional = ScreenPlan.report(miniLed.copy(modes = listOf(uhd60, Mode(6, 3840, 2160, 23.976f))))
        check("23,98 Гц" in fractional) { fractional }
        check("не сообщается" in ScreenPlan.report(miniLed.copy(peakNits = null, averageNits = null)))
    }

    test("advice_matches_panel_and_access") {
        val cinema = ScreenPlan.advice(Preset.CINEMA, miniLed)
        check("Локальное затемнение: высокое" in cinema && "Dolby Vision" in cinema && "Momiji включает подстройку частоты «всегда" in cinema)
        val noAccess = ScreenPlan.advice(Preset.CINEMA, miniLed.copy(canWriteSettings = false, hdr = setOf(HdrType.HDR10)))
        check("после доступа по ADB" in noAccess && "Dolby Vision" !in noAccess)
        val game = ScreenPlan.advice(Preset.GAME, miniLed)
        check("HDMI 2.1" in game && "ALLM" in game && "HGiG" in game)
        check("включи вручную" in ScreenPlan.advice(Preset.GAME, miniLed.copy(allmSupported = false, modes = listOf(uhd60.copy(seamless = emptySet())))))
        check("прошивка производителя" in cinema && "прошивка" !in ScreenPlan.advice(Preset.SYSTEM, miniLed))
    }

    println("HOME_DISPLAY_PASSED=$passed")
}
