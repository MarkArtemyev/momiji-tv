package app.momiji.home.data

import android.content.Context
import app.momiji.home.model.AppEntry
import app.momiji.home.model.PerformanceMode
import app.momiji.home.model.HomeLayout
import app.momiji.home.media.WatchHistory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object StoreSelfCheck {
    fun run(context: Context): String {
        val prefsName = "momiji_selfcheck_" + UUID.randomUUID().toString()
        val tests = JSONArray()
        var failed = 0
        fun test(name: String, block: () -> Unit) {
            val result = JSONObject().put("name", name)
            try { block(); result.put("ok", true) }
            catch (error: Exception) { failed++; result.put("ok", false).put("error", error.message ?: error.javaClass.simpleName) }
            tests.put(result)
        }
        try {
            val store = HomeStore(context, prefsName)
            val main = store.activeProfile()
            val other = store.createProfile("Игры")
            fun app(n: Int) = AppEntry("test.momiji.app$n", "test.momiji.app$n.Main", "Приложение $n", n % 2 == 0)
            test("empty_default_favorites") { check(store.favorites(main.id).isEmpty()) }
            test("profile_isolation") {
                store.setFavorites(main.id, listOf(app(1).key, app(2).key))
                store.setFavorites(other.id, listOf(app(3).key))
                store.setHidden(main.id, app(2).key, true)
                store.recordLaunch(main.id, app(1))
                store.recordLaunch(other.id, app(3))
                store.selectProfile(other.id)
                check(store.activeProfile().id == other.id)
                check(store.favorites(main.id) == listOf(app(1).key, app(2).key))
                check(store.favorites(other.id) == listOf(app(3).key))
                check(store.hiddenApps(main.id) == setOf(app(2).key))
                check(store.hiddenApps(other.id).isEmpty())
                check(store.recentLaunches(main.id).single().appKey == app(1).key)
                check(store.recentLaunches(other.id).single().appKey == app(3).key)
                store.recordLaunch(main.id, app(4)) // A late callback keeps its captured profile ID.
                check(store.recentLaunches(main.id).first().appKey == app(4).key)
                check(store.recentLaunches(other.id).single().appKey == app(3).key)
            }
            test("favorites_reorder_and_dedup") {
                store.setFavorites(main.id, listOf(app(2).key, app(1).key, app(2).key, ""))
                check(store.favorites(main.id) == listOf(app(2).key, app(1).key))
            }
            test("history_bounded_and_dedup") {
                for (i in 1..40) store.recordLaunch(main.id, app(i))
                check(store.recentLaunches(main.id).size == 32)
                check(store.recentLaunches(main.id).first().appKey == app(40).key)
                check(store.recentLaunches(main.id).last().appKey == app(9).key)
                store.recordLaunch(main.id, app(12))
                check(store.recentLaunches(main.id).size == 32)
                check(store.recentLaunches(main.id).first().appKey == app(12).key)
                check(store.recentLaunches(main.id).map { it.appKey }.distinct().size == 32)
            }
            test("hide_unhide_and_local_history_clear") {
                store.setHidden(main.id, app(2).key, false)
                check(store.hiddenApps(main.id).isEmpty())
                store.clearHistory(other.id)
                check(store.recentLaunches(other.id).isEmpty())
                check(store.recentLaunches(main.id).size == 32)
            }
            test("unknown_profile_guard") {
                val before = store.favorites(other.id)
                var rejected = false
                try { store.setFavorites("does_not_exist", listOf(app(99).key)) } catch (_: IllegalArgumentException) { rejected = true }
                check(rejected)
                check(store.favorites(other.id) == before)
                check(store.profiles().size == 2)
            }
            test("rename_and_persist_reopen") {
                store.renameProfile(other.id, "Игровой")
                store.performanceMode = PerformanceMode.LITE
                store.animationsEnabled = false
                store.wallpaperUri = "content://momiji.test/wallpaper"
                store.setHidden(other.id, app(4).key, true)
                val reopened = HomeStore(context, prefsName)
                check(reopened.activeProfile().id == other.id)
                check(reopened.activeProfile().name == "Игровой")
                check(reopened.favorites(main.id) == store.favorites(main.id))
                check(reopened.recentLaunches(main.id) == store.recentLaunches(main.id))
                check(reopened.hiddenApps(other.id) == setOf(app(4).key))
                check(reopened.performanceMode == PerformanceMode.LITE)
                check(!reopened.animationsEnabled)
                check(reopened.wallpaperUri == "content://momiji.test/wallpaper")
            }
            test("categories_aliases_layout_and_games_are_profile_local") {
                val films = store.createCategory(main.id,"Кино")
                val games = store.createCategory(main.id,"Игры")
                store.updateCategory(main.id, films.copy(appKeys=listOf(app(2).key,app(1).key,app(2).key)))
                store.moveCategory(main.id,games.id,-1)
                store.setAlias(main.id,app(1).key,"Моё кино")
                store.setGame(main.id,app(1).key,true)
                store.setLayout(main.id,HomeLayout(columns=6,cardSize=2,largeText=true,showServices=false,roundCards=false))
                store.setAccent(main.id,0xFF8EC8C4.toInt())
                val reopened=HomeStore(context,prefsName)
                check(reopened.categories(main.id).map { it.id } == listOf(games.id, films.id))
                check(reopened.categories(main.id).last().appKeys == listOf(app(2).key,app(1).key))
                check(reopened.aliases(main.id)[app(1).key] == "Моё кино")
                check(reopened.gameApps(main.id) == setOf(app(1).key))
                check(reopened.layout(main.id).columns == 6 && !reopened.layout(main.id).showServices)
                check(reopened.categories(other.id).isEmpty() && reopened.aliases(other.id).isEmpty())
                check(reopened.gameApps(other.id).isEmpty() && reopened.layout(other.id).columns == 0)
            }
            test("ambient_settings_persist_and_remain_profile_local") {
                val chosen=store.layout(main.id).copy(ambientEffect="MAPLE",ambientIntensity=2,ambientSpeed=0,ambientColor=4)
                store.setLayout(main.id,chosen)
                val reopened=HomeStore(context,prefsName)
                check(reopened.layout(main.id) == chosen)
                check(reopened.layout(other.id).ambientEffect == "OFF")
                check(reopened.layout(other.id).ambientIntensity == 1 && reopened.layout(other.id).ambientSpeed == 1)
                val invalid=listOf(chosen.copy(ambientEffect="UNKNOWN"),chosen.copy(ambientIntensity=-1),
                    chosen.copy(ambientIntensity=3),chosen.copy(ambientSpeed=-1),chosen.copy(ambientSpeed=3),
                    chosen.copy(ambientColor=-1),chosen.copy(ambientColor=5))
                for (candidate in invalid) {
                    var rejected=false
                    try { store.setLayout(main.id,candidate) } catch (_: IllegalArgumentException) { rejected=true }
                    check(rejected && reopened.layout(main.id) == chosen)
                }
            }
            test("portable_profile_round_trip_does_not_copy_history") {
                val payload=store.exportProfile(main.id)
                check(!payload.contains("launchedAt") && JSONObject(payload).getJSONObject("profile").getJSONArray("recent").length() == 0)
                val imported=store.importProfile(payload)
                check(imported.id != main.id)
                check(store.favorites(imported.id) == store.favorites(main.id))
                check(store.categories(imported.id) == store.categories(main.id))
                check(store.aliases(imported.id) == store.aliases(main.id))
                check(store.layout(imported.id) == store.layout(main.id))
                check(store.recentLaunches(imported.id).isEmpty())
                store.setAlias(imported.id,app(1).key,"Другая карточка")
                check(store.aliases(main.id)[app(1).key] == "Моё кино")
                check(store.deleteProfile(imported.id)); store.selectProfile(other.id)
            }
            test("portable_ambient_values_are_clamped_without_mutating_source_profile") {
                val original=store.layout(main.id)
                val payload=JSONObject(store.exportProfile(main.id))
                payload.getJSONObject("profile").getJSONObject("layout")
                    .put("ambient","UNSUPPORTED").put("ambientIntensity",900).put("ambientSpeed",-900).put("ambientColor",900)
                val imported=store.importProfile(payload.toString())
                val layout=HomeStore(context,prefsName).layout(imported.id)
                check(layout.ambientEffect == "OFF" && layout.ambientIntensity == 2 && layout.ambientSpeed == 0 && layout.ambientColor == 4)
                check(store.layout(main.id) == original && store.layout(other.id).ambientEffect == "OFF")
                check(store.deleteProfile(imported.id)); store.selectProfile(other.id)
            }
            test("legacy_portable_layout_defaults_to_static_background") {
                val payload=JSONObject(store.exportProfile(main.id))
                val portableLayout=payload.getJSONObject("profile").getJSONObject("layout")
                listOf("ambient","ambientIntensity","ambientSpeed","ambientColor").forEach { portableLayout.remove(it) }
                val imported=store.importProfile(payload.toString())
                val layout=store.layout(imported.id)
                check(layout.ambientEffect == "OFF" && layout.ambientIntensity == 1 && layout.ambientSpeed == 1 && layout.ambientColor == 0)
                check(store.layout(main.id).ambientEffect == "MAPLE")
                check(store.deleteProfile(imported.id)); store.selectProfile(other.id)
            }
            test("cache_observes_other_store_instance_and_rejects_bad_import") {
                val reopened=HomeStore(context,prefsName)
                check(reopened.activeProfile().id == other.id)
                store.selectProfile(main.id)
                check(reopened.activeProfile().id == main.id)
                val before=store.profiles().size
                var rejected=false
                try { store.importProfile("{\"format\":\"unrelated\",\"version\":1}") } catch (_: Exception) { rejected=true }
                check(rejected && store.profiles().size == before)
                store.selectProfile(other.id)
            }
            val databaseName="momiji_watch_selfcheck_${UUID.randomUUID()}.db"
            val history=WatchHistory(context,databaseName)
            try {
                val page="https://example.org/watch/episode-1"
                test("watch_progress_is_profile_local_and_stable") {
                    history.save(main.id,"Первая серия",page,120_000,1_800_000)
                    history.save(other.id,"Первая серия",page,600_000,1_800_000)
                    check(history.list(main.id).single().position == 120_000L)
                    check(history.list(other.id).single().position == 600_000L)
                    history.save(main.id,"Обновлённое название",page,180_000,1_800_000)
                    check(history.list(main.id).single().title == "Обновлённое название")
                    check(history.find(other.id,page)?.position == 600_000L)
                }
                test("rewind_to_start_removes_only_captured_profile_progress") {
                    history.save(main.id,"Первая серия",page,120_000,1_800_000)
                    history.save(main.id,"Первая серия",page,3_000,1_800_000)
                    check(history.find(main.id,page) == null)
                    check(history.find(other.id,page)?.position == 600_000L)
                    history.save(main.id,"Первая серия",page,120_000,1_800_000)
                    history.save(main.id,"Первая серия",page,0,1_800_000)
                    check(history.find(main.id,page) == null && history.find(other.id,page)?.position == 600_000L)
                    val firstPlay="$page?case=first-short-play"
                    history.save(main.id,"Короткое открытие",firstPlay,3_000,1_800_000)
                    check(history.find(main.id,firstPlay) == null)
                }
                test("completed_watch_is_removed_from_continue_only") {
                    history.save(main.id,"Серия",page,1_790_000,1_800_000,ended=true)
                    check(history.list(main.id).isEmpty())
                    check(history.list(main.id,unfinishedOnly=false).single().completed)
                    check(history.list(other.id).size == 1)
                    history.clear(main.id)
                    check(history.list(main.id,unfinishedOnly=false).isEmpty() && history.list(other.id).size == 1)
                }
                test("watch_validation_and_bounded_retention") {
                    check(!WatchHistory.validPage("javascript:alert(1)"))
                    check(!WatchHistory.validPage("file:///data/secret"))
                    check(!WatchHistory.validPage("https://user:password@example.org/watch"))
                    var rejected=false
                    try { history.save(main.id,"Неверный прогресс",page,2_000,1_000) } catch (_: IllegalArgumentException) { rejected=true }
                    check(rejected)
                    for (n in 0..205) history.save(main.id,"Видео $n","https://example.org/watch/$n",20_000,300_000)
                    check(history.list(main.id,limit=200).size == 200)
                    val id=history.list(other.id).single().id
                    history.remove(other.id,id)
                    check(history.list(other.id).isEmpty() && history.list(main.id,limit=200).size == 200)
                }
            } finally { history.close(); context.deleteDatabase(databaseName) }
            test("custom_artwork_is_local_and_excluded_from_portable_settings") {
                val local="file:///data/user/0/app.momiji.home/files/icons/example.img"
                store.setIcon(main.id,app(1).key,local)
                check(HomeStore(context,prefsName).icons(main.id)[app(1).key] == local)
                check(store.icons(other.id).isEmpty())
                val exported=JSONObject(store.exportProfile(main.id))
                check(!exported.getJSONObject("profile").has("icons"))
                exported.getJSONObject("profile").put("icons",JSONObject().put(app(1).key,"file:///data/secret"))
                val imported=store.importProfile(exported.toString())
                check(store.icons(imported.id).isEmpty())
                check(store.deleteProfile(imported.id)); store.selectProfile(other.id)
                store.setIcon(main.id,app(1).key,null)
                check(store.icons(main.id).isEmpty())
            }
            test("delete_guards_and_stale_id") {
                check(store.deleteProfile(other.id))
                check(store.activeProfile().id == main.id)
                check(!store.deleteProfile(main.id))
                check(!store.deleteProfile("does_not_exist"))
                var rejected = false
                try { store.recordLaunch(other.id, app(7)) } catch (_: IllegalArgumentException) { rejected = true }
                check(rejected)
                check(store.recentLaunches(main.id).first().appKey == app(12).key)
            }
            test("corrupt_preferences_fallback") {
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                    .putString("state_v1", "{broken").putInt("performance", 123).putString("animations", "broken").commit()
                val recovered = HomeStore(context, prefsName)
                check(recovered.profiles().size == 1)
                check(recovered.activeProfile().name == "Основной")
                check(recovered.favorites(recovered.activeProfile().id).isEmpty())
                check(recovered.performanceMode == PerformanceMode.BALANCED)
                check(recovered.animationsEnabled)
                recovered.renameProfile(recovered.activeProfile().id, "Восстановленный")
                check(HomeStore(context, prefsName).activeProfile().name == "Восстановленный")
            }
        } catch (error: Exception) {
            failed++
            tests.put(JSONObject().put("name", "setup").put("ok", false).put("error", error.message ?: error.javaClass.simpleName))
        } finally {
            // Delete only this run's randomly named test preferences, never the user's store.
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(prefsName)
        }
        return JSONObject().put("ok", failed == 0).put("passed", failed == 0).put("count", tests.length())
            .put("failures", failed).put("tests", tests).toString()
    }
}
