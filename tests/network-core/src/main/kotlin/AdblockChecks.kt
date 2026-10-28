import app.momiji.net.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** Ad blocking engine checks: list syntax, network v2, cosmetics/scriptlets, HLS pruning and the bundled lists. */
fun adblockChecks(repo: File?, test: (String, () -> Unit) -> Unit) {
    val psl = PublicSuffixes("com\nlc\nru\nco.uk\nexample\nnet\norg\ntv")

    test("list_directives_and_platform_hints") {
        val lines = """
            ||always.example^
            !#if (adguard_ext_safari || adguard_app_ios)
            ||safari-only.example^
            !#else
            ||not-safari.example^
            !#endif
            !#if (adguard_ext_chromium && !adguard_ext_chromium_mv3)
            !#if (adguard_app_android)
            ||nested-android.example^
            !#endif
            ||chromium.example^
            !#endif
            !+ PLATFORM(ios, ext_android_cb, ext_safari)
            ||hinted-safari.example^
            !+ NOT_OPTIMIZED NOT_PLATFORM(ext_chromium, windows)
            ||hinted-not-chromium.example^
            !+ NOT_PLATFORM(ios)
            ||hinted-not-ios.example^
            ||after-hints.example^
        """.trimIndent().lineSequence()
        val kept = FilterSyntax.applicable(lines).toList()
        check(kept == listOf("||always.example^", "||not-safari.example^", "||chromium.example^", "||hinted-not-ios.example^", "||after-hints.example^")) { kept.toString() }
        check(FilterSyntax.evaluate("(adguard || adguard_app_ios && adguard_ext_safari)"))
        check(!FilterSyntax.evaluate("(adguard_app_ios || ext_ublock"))
        check(FilterSyntax.evaluate("!ext_ublock"))
    }

    test("tld_wildcard_domains_and_escaped_options") {
        check(FilterSyntax.domainMatches("www.mangaraw.lc", "mangaraw.*", psl))
        check(FilterSyntax.domainMatches("mangaraw.co.uk", "mangaraw.*", psl))
        check(!FilterSyntax.domainMatches("notmangaraw.lc", "mangaraw.*", psl))
        check(!FilterSyntax.domainMatches("mangaraw.lc.evil.com", "mangaraw.*", psl))
        val keys = FilterSyntax.hostKeys("www.mangaraw.co.uk", psl)
        check("mangaraw.*" in keys && "www.mangaraw.*" in keys && "mangaraw.co.uk" in keys) { keys.toString() }
        val rule = "/player?\$jsonprune=\\$..[adPlacements\\, adSlots],domain=example.com"
        check(rule.substring(FilterSyntax.optionsStart(rule)) == "\$jsonprune=\\$..[adPlacements\\, adSlots],domain=example.com")
        check(FilterSyntax.splitUnescaped("a=x\\,y,b", ',') == listOf("a=x\\,y", "b"))
    }

    val network = RequestRules(listOf(
        "||pop.example^\$popup", "||tracker.example^", "||everything.example^\$all",
        "||ads.example/vast/*\$xmlhttprequest", "||ads.example/vast/*\$redirect=noopvast-3.0", "@@||ads.example/vast/allowed\$xmlhttprequest",
        "||imasdk.example/ima3.js\$script,redirect=google-ima3", "||cdn.example/promo.mp4\$media,redirect-rule=noopmp4-1s", "||cdn.example/promo.mp4",
        "*\$script,third-party,denyallow=trusted-cdn.example,domain=strict.example",
        "||wild.example^\$script,domain=mangaraw.*",
        "@@||quiet.example^\$generichide", "@@||nojs.example^\$jsinject,elemhide", "@@||whitelisted.example^\$document",
        "||api.example/player\$replace=/(<VAST[\\s\\S]*?>)[\\s\\S]*<\\/VAST>/\\$1<\\/VAST>/,xmlhttprequest",
        "/youtubei/v*/player?\$jsonprune=\\$..[adPlacements\\, adSlots\\, playerAds],domain=video.example",
        "@@||api.example/player/raw\$replace",
        "||cdn.example/vod/*.m3u8\$hls=/#UPLYNK-SEGMENT:.*\\,ad/t,domain=tv.example",
        "@@||allow.example^\$redirect", "||bad-option.example^\$csp=script-src 'none'"), psl)
    fun request(url: String, page: String = "https://site.example/", type: ResourceType = ResourceType.SCRIPT) = FilterRequest(url, page, type)

    test("popup_and_all_only_where_rules_say_so") {
        check(network.blocks(request("https://pop.example/x", type = ResourceType.POPUP)))
        check(!network.blocks(request("https://pop.example/x.js")))
        check(!network.blocks(request("https://tracker.example/x", type = ResourceType.POPUP)))
        check(network.blocks(request("https://tracker.example/x", type = ResourceType.DOCUMENT)))
        for (type in listOf(ResourceType.POPUP, ResourceType.DOCUMENT, ResourceType.SCRIPT, ResourceType.IMAGE))
            check(network.blocks(request("https://everything.example/a", type = type))) { type.toString() }
        check(network.popupCount == 2)
    }

    test("redirect_and_redirect_rule_standins") {
        val vast = network.decision(request("https://ads.example/vast/tag?x=1", type = ResourceType.XMLHTTPREQUEST))!!
        check(vast.blocked && vast.redirect == "noopvast-3.0") { vast.toString() }
        check(network.decision(request("https://ads.example/vast/allowed", type = ResourceType.XMLHTTPREQUEST))?.blocked == false)
        check(network.decision(request("https://imasdk.example/ima3.js"))?.redirect == "google-ima3")
        val clip = request("https://cdn.example/promo.mp4", type = ResourceType.MEDIA)
        check(network.decision(clip)?.let { it.blocked && it.redirect == null } == true)
        check(network.redirectRule(clip) == "noopmp4-1s")
        check(network.redirectRule(request("https://cdn.example/other.mp4", type = ResourceType.MEDIA)) == null)
        // A redirect exception must never unblock, and unknown options keep the rule out entirely.
        check(network.decision(request("https://allow.example/a.js")) == null)
        check(network.decision(request("https://bad-option.example/a.js")) == null)
        check(RuleDecision.stronger(RuleDecision(true, 1, "noopjs"), RuleDecision(true, 1))?.redirect == "noopjs")
        check(RuleDecision.stronger(RuleDecision(true, 1), RuleDecision(true, 1, "noopjs"))?.redirect == "noopjs")
    }

    test("denyallow_and_wildcard_domain_modifiers") {
        check(network.blocks(request("https://random-cdn.example/a.js", "https://strict.example/")))
        check(!network.blocks(request("https://trusted-cdn.example/a.js", "https://strict.example/")))
        check(!network.blocks(request("https://random-cdn.example/a.js", "https://other.example/")))
        check(network.blocks(request("https://wild.example/a.js", "https://www.mangaraw.lc/")))
        check(!network.blocks(request("https://wild.example/a.js", "https://mangaraw-fan.lc/")))
    }

    test("page_exceptions_switch_layers_off") {
        check(network.pageFlags("https://quiet.example/page") == PageFlags.GENERICHIDE)
        check(network.pageFlags("https://m.nojs.example/") == PageFlags.JSINJECT or PageFlags.ELEMHIDE)
        check(network.pageFlags("https://whitelisted.example/") == PageFlags.ALL)
        check(network.decision(request("https://tracker.example/x.js", "https://whitelisted.example/"))?.blocked == false)
        check(network.blocks(request("https://tracker.example/x.js", "https://quiet.example/")))
        check(network.pageFlags("https://site.example/") == 0)
    }

    test("content_rules_replace_jsonprune_hls") {
        val replace = network.content(request("https://api.example/player?id=1", type = ResourceType.XMLHTTPREQUEST), ContentKind.REPLACE)
        check(replace == listOf("/(<VAST[\\s\\S]*?>)[\\s\\S]*<\\/VAST>/$1<\\/VAST>/")) { replace.toString() }
        check(RequestRules.replaceParts(replace[0]) == Triple("(<VAST[\\s\\S]*?>)[\\s\\S]*<\\/VAST>", "$1</VAST>", ""))
        check(network.content(request("https://api.example/player/raw", type = ResourceType.XMLHTTPREQUEST), ContentKind.REPLACE).isEmpty())
        check(network.content(request("https://api.example/player", type = ResourceType.SCRIPT), ContentKind.REPLACE).isEmpty())
        val prune = network.content(request("https://video.example/youtubei/v1/player?key=1", "https://video.example/watch", ResourceType.XMLHTTPREQUEST), ContentKind.JSONPRUNE)
        check(prune == listOf("$..[adPlacements, adSlots, playerAds]")) { prune.toString() }
        check(network.hasContentFor("https://video.example/watch", ContentKind.JSONPRUNE))
        check(!network.hasContentFor("https://site.example/", ContentKind.JSONPRUNE))
        val hls = network.content(request("https://cdn.example/vod/a.m3u8", "https://tv.example/", ResourceType.XMLHTTPREQUEST), ContentKind.HLS)
        check(hls == listOf("/#UPLYNK-SEGMENT:.*,ad/t")) { hls.toString() }
        check(network.content(request("https://cdn.example/vod/a.m3u8", "https://quiet.example/", ResourceType.XMLHTTPREQUEST), ContentKind.HLS).isEmpty())
    }

    test("network_v2_binary_roundtrip") {
        val bytes = ByteArrayOutputStream().also(network::write).toByteArray()
        val restored = RequestRules(bytes.inputStream(), psl)
        val samples = listOf(request("https://pop.example/x", type = ResourceType.POPUP), request("https://ads.example/vast/tag", type = ResourceType.XMLHTTPREQUEST),
            request("https://imasdk.example/ima3.js"), request("https://random-cdn.example/a.js", "https://strict.example/"),
            request("https://wild.example/a.js", "https://www.mangaraw.lc/"), request("https://tracker.example/x.js", "https://whitelisted.example/"))
        for (sample in samples) check(restored.decision(sample) == network.decision(sample)) { sample.url }
        check(restored.redirectRule(request("https://cdn.example/promo.mp4", type = ResourceType.MEDIA)) == "noopmp4-1s")
        check(restored.pageFlags("https://m.nojs.example/") == network.pageFlags("https://m.nojs.example/"))
        check(restored.content(request("https://api.example/player", type = ResourceType.XMLHTTPREQUEST), ContentKind.REPLACE).size == 1)
        check(runCatching { RequestRules(byteArrayOf(0x4d, 0x4e, 0x52, 0x31, 0, 0, 0, 0).inputStream(), psl) }.isFailure)
    }

    val cosmetic = CosmeticRules(CosmeticRules.compile("""
        site.example##.site-banner
        ##.ad-banner
        ##div[id^="google_ads_"]
        ##.sponsor > a
        quiet.example,site.example#@#.ad-banner
        site.example#?#.feed > div:has-text(Реклама)
        site.example##.promo:upward(2)
        site.example##.overlay:remove()
        site.example#${'$'}#body { overflow: auto !important; }
        site.example#${'$'}#.x { background: url(https://evil.example/a.png) }
        site.example#%#//scriptlet('set-constant', 'ads.enabled', 'false')
        site.example#%#//scriptlet("prevent-window-open", 'it\'s', "say \"hi\"")
        site.example##+js(aopr, adBlockDetected)
        site.example,~shop.site.example#%#//scriptlet('abort-on-property-read', 'popunder')
        shop.site.example#@%#//scriptlet('set-constant')
        mangaraw.*#%#//scriptlet('prevent-fetch', 'ads.js')
        site.example#%#window.__momijiRaw = 1;
        other.example#%#window.__momijiRaw = 1;
        other.example#@%#window.__momijiRaw = 1;
        nojs.example#%#//scriptlet('set-constant', 'x', '1')
        nojs.example##.nojs-banner
        allscripts.example#%#//scriptlet('set-constant', 'y', '1')
        allscripts.example#@%#//scriptlet()
        #%#//scriptlet('no-topics')
        [${'$'}path=/page]site.example##.path-only
        site.example##^script:has-text(ads)
    """.trimIndent().lineSequence()), listOf("custom.example##.custom-ad", "site.example#@#.site-banner"), psl)

    test("cosmetic_hiding_styles_and_extended_css") {
        val page = cosmetic.page("www.site.example")
        check(".site-banner" !in page.css) // custom exception wins over the list
        check("div[id^=\"google_ads_\"]{display:none!important}" in page.css && "body { overflow: auto !important; }" in page.css)
        check(".ad-banner" !in page.css && "url(" !in page.css && ".path-only" !in page.css)
        check(page.extended.any { "div:has-text(Реклама) { display: none !important; }" in it } && page.extended.any { it == ".promo:upward(2) { display: none !important; }" })
        check(page.extended.any { it == ".overlay { remove: true; }" }) { page.extended.toString() }
        check(page.generic)
        check(cosmetic.page("custom.example").css.contains(".custom-ad{"))
        check(cosmetic.counts[CosmeticRules.Kind.SCRIPTLET]!! >= 7)
    }

    test("lowly_generic_selectors_follow_page_tokens") {
        check(".sponsor > a{display:none!important}" in cosmetic.generic("news.example", listOf(".sponsor", ".unrelated")))
        check(".ad-banner{" in cosmetic.generic("news.example", listOf(".ad-banner")))
        check(cosmetic.generic("site.example", listOf(".ad-banner")).isEmpty())
        check(cosmetic.generic("quiet.example", listOf(".sponsor"), PageFlags.GENERICHIDE).isEmpty())
        check("google_ads_" !in cosmetic.generic("news.example", listOf("#google_ads_1")))
    }

    test("scriptlets_adguard_ubo_exceptions_and_raw_js") {
        val page = cosmetic.page("site.example")
        val calls = page.scriptlets
        check(listOf("set-constant", "ads.enabled", "false") in calls)
        check(listOf("prevent-window-open", "it's", "say \"hi\"") in calls) { calls.toString() }
        check(listOf("ubo-aopr.js", "adBlockDetected") in calls)
        check(listOf("abort-on-property-read", "popunder") in calls && listOf("no-topics") in calls)
        val shop = cosmetic.page("shop.site.example").scriptlets
        check(shop.none { it[0] == "set-constant" } && shop.none { it.getOrNull(1) == "popunder" } && listOf("prevent-window-open", "it's", "say \"hi\"") in shop)
        check(listOf("prevent-fetch", "ads.js") in cosmetic.page("cdn.mangaraw.lc").scriptlets)
        check(cosmetic.page("allscripts.example").scriptlets.isEmpty())
        check(page.scripts.size == 1 && cosmetic.scripts[page.scripts[0]].code == "window.__momijiRaw = 1;")
        check(cosmetic.page("other.example").scripts.isEmpty())
        val nojs = cosmetic.page("nojs.example", PageFlags.JSINJECT or PageFlags.ELEMHIDE)
        check(nojs.scriptlets.isEmpty() && nojs.css.isEmpty() && nojs.scripts.isEmpty())
        val quiet = cosmetic.page("quiet.example", PageFlags.GENERICHIDE)
        check(!quiet.generic && "google_ads_" !in quiet.css)
        val payload = ShieldPayload.build(page, 0, true)
        check(payload.startsWith("{\"v\":1,") && "\"jp\":1" in payload && "[\"ubo-aopr.js\",\"adBlockDetected\"]" in payload)
        check(Json.stringArray(Json.quote("a\"b\\c\nd").let { "[$it,\"e\"]" }) == listOf("a\"b\\c\nd", "e"))
    }

    val vod = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1"
        #EXTINF:6.0,
        content/1.ts
        #EXTINF:6.0,
        content/2.ts
        #EXT-X-CUE-OUT:12
        #EXT-X-DISCONTINUITY
        #EXT-X-KEY:METHOD=NONE
        #EXTINF:6.0,
        https://ads.cdn.example/ad1.ts
        #EXT-X-CUE-OUT-CONT:6/12
        #EXTINF:6.0,
        https://ads.cdn.example/ad2.ts
        #EXT-X-CUE-IN
        #EXT-X-DISCONTINUITY
        #EXTINF:6.0,
        content/3.ts
        #EXTINF:6.0,
        content/4.ts
        #EXTINF:6.0,
        content/5.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    test("hls_cue_markers_keep_keys_and_discontinuity") {
        val result = HlsPruner.prune(vod, "https://cdn.example/vod/main.m3u8")!!
        val text = result.text
        check(result.removed == 2 && result.kept == 5 && "ad1.ts" !in text && "ad2.ts" !in text && "CUE" !in text) { text }
        val lines = text.lines()
        val third = lines.indexOf("content/3.ts")
        // After the cut the original playlist decrypted with METHOD=NONE, so that must still hold for segment 3.
        check(lines.subList(lines.indexOf("content/2.ts"), third).let { "#EXT-X-DISCONTINUITY" in it && "#EXT-X-KEY:METHOD=NONE" in it }) { text }
        check(lines.count { it == "#EXT-X-DISCONTINUITY" } == 1 && lines.last { it.isNotEmpty() } == "#EXT-X-ENDLIST" && lines[0] == "#EXTM3U")
        check(lines.indexOf("#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k1\"") < lines.indexOf("content/1.ts"))
    }

    test("hls_filters_rules_byteranges_and_safety") {
        val byFilter = HlsPruner.prune(vod.replace("#EXT-X-CUE-OUT:12", "").replace("#EXT-X-CUE-OUT-CONT:6/12", "").replace("#EXT-X-CUE-IN", ""),
            "https://cdn.example/vod/main.m3u8") { it.startsWith("https://ads.cdn.example/") }!!
        check(byFilter.removed == 2 && "ads.cdn.example" !in byFilter.text)
        val uplynk = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#UPLYNK-SEGMENT:abc,00000001,segment\n#EXTINF:4,\nc1.ts\n#UPLYNK-SEGMENT:def,00000002,ad\n#EXTINF:4,\nx1.ts\n" +
            "#UPLYNK-SEGMENT:abc,00000003,segment\n#EXTINF:4,\nc2.ts\n#EXTINF:4,\nc3.ts\n#EXT-X-ENDLIST\n"
        val rule = HlsPruner.rule("/#UPLYNK-SEGMENT:.*,ad/t")!!
        check(HlsPruner.prune(uplynk, "https://v.example/p.m3u8", listOf(rule))!!.text.let { "x1.ts" !in it && "c2.ts" in it })
        val dai = HlsPruner.rule("/redirector\\.googlevideo\\.com\\/videoplayback[\\s\\S]*?dclk_video_ads/")!!
        val daiList = "#EXTM3U\n#EXTINF:5,\nhttps://redirector.googlevideo.com/videoplayback?id=1&source=dclk_video_ads\n#EXTINF:5,\nhttps://c.example/1.ts\n" +
            "#EXTINF:5,\nhttps://c.example/2.ts\n#EXTINF:5,\nhttps://c.example/3.ts\n#EXT-X-ENDLIST\n"
        check(HlsPruner.prune(daiList, "https://dai.example/a.m3u8", listOf(dai))!!.text.let { "dclk" !in it && it.contains("#EXTINF:5,\nhttps://c.example/1.ts") })
        check(HlsPruner.prune(daiList, "https://dai.example/a.m3u8", listOf(HlsPruner.rule("*dclk_video_ads*")!!))!!.removed == 1)
        val ranges = "#EXTM3U\n#EXT-X-VERSION:4\n#EXTINF:4,\n#EXT-X-BYTERANGE:100@0\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:50\nhttps://ad.example/x.ts\n" +
            "#EXTINF:4,\n#EXT-X-BYTERANGE:100\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:100\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:100\nall.ts\n#EXT-X-ENDLIST\n"
        val ranged = HlsPruner.prune(ranges, "https://v.example/r.m3u8") { "ad.example" in it }!!.text
        check("#EXT-X-BYTERANGE:100@100" in ranged && "#EXT-X-BYTERANGE:100@200" in ranged && "#EXT-X-BYTERANGE:100@300" in ranged) { ranged }
        val live = vod.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "").replace("#EXT-X-ENDLIST", "")
        check(HlsPruner.prune(live, "https://cdn.example/live.m3u8") == null)
        check(HlsPruner.prune("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", "https://cdn.example/master.m3u8") == null)
        check(HlsPruner.prune(vod, "https://cdn.example/vod/main.m3u8") { "content/" in it } == null)
        check(HlsPruner.prune(vod.replace("ads.cdn.example", "cdn2.example").replace("#EXT-X-CUE-OUT:12\n", "").replace("#EXT-X-CUE-OUT-CONT:6/12\n", "").replace("#EXT-X-CUE-IN\n", ""),
            "https://cdn.example/vod/main.m3u8") == null)
    }

    test("replace_rules_follow_javascript_semantics") {
        val vast = "<VAST version=\"3.0\"><Ad id=\"1\"><Linear/></Ad></VAST>"
        val rule = RequestRules.replaceParts("/(<VAST[\\s\\S]*?>)[\\s\\S]*<\\/VAST>/$1<\\/VAST>/")!!
        check(ReplaceRules.apply(vast, listOf(rule)) == "<VAST version=\"3.0\"></VAST>") { ReplaceRules.apply(vast, listOf(rule)) }
        check(ReplaceRules.apply("a1 a2 A3", listOf(Triple("a(\\d)", "[$1|$&|$$]", ""))) == "[1|a1|$] a2 A3")
        check(ReplaceRules.apply("a1 a2 A3", listOf(Triple("a(\\d)", "x$1", "gi"))) == "x1 x2 x3")
        check(ReplaceRules.apply("keep {", listOf(Triple("function(){", "", "g"), Triple("keep", "kept", ""))) == "kept {")
        check(RequestRules.replaceParts("/a\\/b/c\\/d/gi") == Triple("a\\/b", "c/d", "gi"))
        check(RequestRules.replaceParts("/unterminated/x") == null && RequestRules.replaceParts("noslash") == null)
    }

    test("surrogate_fallbacks_for_known_ad_libraries") {
        check(Surrogates.fallback("https://imasdk.googleapis.com/js/sdkloader/ima3.js", ResourceType.SCRIPT) == "google-ima3")
        check(Surrogates.fallback("https://securepubads.g.doubleclick.net/tag/js/gpt.js", ResourceType.SCRIPT) == "googletagservices-gpt")
        check(Surrogates.fallback("https://ads.example/vast.xml?x=1", ResourceType.XMLHTTPREQUEST) == "noopvast-4.0")
        check(Surrogates.fallback("https://ads.example/serve?type=vmap", ResourceType.XMLHTTPREQUEST) == "noopvmap-1.0")
        check(Surrogates.fallback("https://ads.example/preroll.mp4", ResourceType.MEDIA) == "noopmp4-1s")
        check(Surrogates.fallback("https://site.example/vastly-better.js", ResourceType.SCRIPT) == null)
        check(Surrogates.fallback("https://site.example/api/vastness", ResourceType.XMLHTTPREQUEST) == null)
    }

    if (repo == null) return
    val assets = File(repo, "momiji-browser/app/src/main/assets/protection")
    fun list(name: String) = GZIPInputStream(File(assets, "$name.txt.gz").inputStream()).bufferedReader().readLines()

    test("bundled_lists_compile_with_new_engine") {
        val suffixes = PublicSuffixes(list("publicsuffix").joinToString("\n"))
        val names = listOf("base", "mobile", "russian", "privacy").filter { File(assets, "$it.txt.gz").isFile }
        val lines = names.flatMap(::list)
        val started = System.nanoTime()
        val rules = RequestRules(lines, suffixes)
        val networkMs = (System.nanoTime() - started) / 1_000_000
        val compiled = CosmeticRules.compile(lines.asSequence())
        val cosmeticRules = CosmeticRules(compiled, emptyList(), suffixes)
        val totalMs = (System.nanoTime() - started) / 1_000_000
        println("  lists=$names network=${rules.count} skipped=${rules.skipped} redirects=${rules.redirectCount} popups=${rules.popupCount} " +
            "content=${rules.contentCount} cosmetic=${cosmeticRules.counts} networkMs=$networkMs totalMs=$totalMs")
        check(rules.redirectCount > 500 && rules.popupCount > 1000 && rules.contentCount > 100)
        check(cosmeticRules.counts[CosmeticRules.Kind.SCRIPTLET]!! > 4000 && cosmeticRules.counts[CosmeticRules.Kind.EXTENDED]!! > 2000)
        // Real payloads for the Chromium suite (tests/web-shield), built from the bundled lists.
        File(repo, "tests/network-core/build").mkdirs()
        File(repo, "tests/network-core/build/payloads.json").writeText(listOf("www.youtube.com").joinToString(",", "{", "}") { host ->
            Json.quote(host) + ":" + ShieldPayload.build(cosmeticRules.page(host), 0, rules.hasContentFor("https://$host/", ContentKind.JSONPRUNE))
        })
        val youtube = cosmeticRules.page("www.youtube.com").scriptlets
        check(youtube.any { it[0] == "json-prune-fetch-response" || it[0] == "json-prune" }) { youtube.toString() }
        check(rules.content(FilterRequest("https://www.youtube.com/youtubei/v1/player?key=1", "https://m.youtube.com/watch?v=1", ResourceType.XMLHTTPREQUEST), ContentKind.JSONPRUNE).isNotEmpty())
        // Safari/iOS-only rules must stay out of a Chromium WebView.
        val iosOnly = lines.windowed(2).firstOrNull { it[0].trim() == "!+ PLATFORM(ios, ext_safari)" && it[1].contains("##") }
        if (iosOnly != null) check(!compiled.contains("H\t" + iosOnly[1].substringBefore("##") + "\t" + iosOnly[1].substringAfter("##") + "\n")) { iosOnly[1] }
        val ima = rules.decision(FilterRequest("https://imasdk.googleapis.com/js/sdkloader/ima3.js", "https://somevideosite.example/", ResourceType.SCRIPT))
        println("  ima3 decision=$ima")
        val bytes = ByteArrayOutputStream().also(rules::write).toByteArray()
        fun heap(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }
        val before = heap()
        val loaded = RequestRules(bytes.inputStream(), suffixes)
        val networkHeap = heap() - before
        val cosmeticLoaded = CosmeticRules(compiled, emptyList(), suffixes)
        val cosmeticHeap = heap() - before - networkHeap
        println("  compiled network=${bytes.size / 1024}KiB cosmetic=${compiled.length / 1024}KiB heap network=${networkHeap / 1048576}MiB cosmetic=${cosmeticHeap / 1048576}MiB")
        check(loaded.count == rules.count && cosmeticLoaded.counts == cosmeticRules.counts)
        // A low-end TV app gets 128-256 MiB of heap; the whole engine has to stay well inside it.
        check(networkHeap + cosmeticHeap < 96L * 1048576)
        val redirects = File(assets, "vendor/redirects.json").takeIf { it.isFile }?.readText()
        if (redirects != null) {
            val known = Regex("\"(?:title|aliases)\"\\s*:\\s*(\\[[^\\]]*\\]|\"[^\"]*\")").findAll(redirects).flatMap { Regex("\"([^\"]+)\"").findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }.toSet()
            val used = listOf("google-ima3", "google-ima3-dai", "googletagservices-gpt", "googlesyndication-adsbygoogle", "google-analytics", "google-analytics-ga",
                "amazon-apstag", "metrika-yandex-tag", "metrika-yandex-watch", "scorecardresearch-beacon", "noopvast-4.0", "noopvmap-1.0", "noopmp4-1s",
                "noopmp3-0.1s", "1x1-transparent.gif", "noopframe", "noopcss", "noopjs", "nooptext")
            check(used.all { it in known }) { used.filterNot { it in known }.toString() }
        }
    }
}
