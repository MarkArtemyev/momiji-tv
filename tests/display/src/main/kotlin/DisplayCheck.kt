import app.momiji.home.display.*
import app.momiji.tv.FrameRateEstimator
import app.momiji.tv.FrameRatePlan

/** Home screen presets and the native player's frame rate: run with `./momiji-browser/gradlew -p tests/display displayCheck`. */
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

    test("frame_rates_snap_to_film_and_broadcast_rates") {
        check(FrameRatePlan.snap(23.976025f) == 23.976f && FrameRatePlan.snap(23.98f) == 23.976f && FrameRatePlan.snap(24.0001f) == 24f)
        check(FrameRatePlan.snap(25f) == 25f && FrameRatePlan.snap(29.97f) == 29.97f && FrameRatePlan.snap(59.94f) == 59.94f && FrameRatePlan.snap(50.2f) == 50f)
        for (odd in listOf(12.5f, 37f, 0f, -1f, Float.NaN)) check(FrameRatePlan.snap(odd) == null) { "$odd" }
        check(FrameRatePlan.number(23.976f) == "23,976" && FrameRatePlan.number(24f) == "24" && FrameRatePlan.number(59.94f) == "59,94" && FrameRatePlan.number(60.0001f) == "60")
    }

    test("an_adaptive_switch_keeps_a_rate_that_already_fits") {
        check(FrameRatePlan.next(null, 25f) == 25f)
        check(FrameRatePlan.next(50f, 25f) == 50f && FrameRatePlan.next(59.94f, 29.97f) == 59.94f && FrameRatePlan.next(47.952f, 23.976f) == 47.952f)
        check(FrameRatePlan.next(120f, 24f) == 120f)
        // 24 is not a multiple of 23.976: a film on it stutters once every 42 seconds.
        check(FrameRatePlan.next(24f, 23.976f) == 23.976f && FrameRatePlan.next(23.976f, 24f) == 24f)
        check(FrameRatePlan.next(60f, 24f) == 24f && FrameRatePlan.next(25f, 50f) == 50f)
    }

    test("frame_rate_is_measured_when_the_stream_does_not_declare_it") {
        fun measure(times: List<Long>): Float? { val e = FrameRateEstimator(); var result: Float? = null; for (t in times) result = e.add(t) ?: result; return result }
        fun micros(frames: Int, frameUs: Double) = (0 until frames).map { Math.round(it * frameUs) }
        // 90 kHz MPEG-TS clock: 23.976 fps is 3753.75 ticks a frame, so steps alternate between two values.
        fun ticks(frames: Int, perFrame: Double) = (0 until frames).map { Math.round(Math.floor(it * perFrame) * 1_000_000.0 / 90_000) }
        check(measure(micros(61, 1_000_000.0 * 1001 / 24000)) == 23.976f)
        check(measure(micros(61, 1_000_000.0 / 24)) == 24f)
        check(measure(ticks(61, 3753.75)) == 23.976f && measure(ticks(61, 3600.0)) == 25f && measure(ticks(61, 3003.0)) == 29.97f)
        check(measure(micros(60, 40_000.0)) == null) { "60 frames are only 59 steps" }
        // Every tenth frame dropped by a slow decoder: the double steps are left out.
        check(measure(micros(80, 40_000.0).filterIndexed { i, _ -> i % 10 != 5 }) == 25f)
        // A seek restarts the count.
        val seek = micros(40, 40_000.0) + micros(40, 40_000.0).map { it + 10_000_000 }
        check(measure(seek) == null)
        check(measure(seek + micros(30, 40_000.0).map { it + 11_600_000 }) == 25f)
        // Variable frame rate from a phone camera is left alone.
        val random = java.util.Random(7); var t = 0L
        check(measure((0 until 120).map { t += 20_000 + random.nextInt(40_000); t }) == null)
    }

    println("DISPLAY_PASSED=$passed")
}
