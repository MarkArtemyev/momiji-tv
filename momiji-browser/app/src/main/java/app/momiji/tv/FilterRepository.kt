package app.momiji.tv

import android.content.Context
import android.util.AtomicFile
import app.momiji.net.*
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Offline-first immutable generations. A failed/partial refresh never replaces working filters. */
object FilterRepository {
    private val executor=Executors.newSingleThreadExecutor { r -> Thread(r,"Momiji-Filters").apply { isDaemon=true } }
    private val downloader=Executors.newSingleThreadExecutor { r -> Thread(r,"Momiji-FilterDownload").apply { isDaemon=true } }
    private val stateLock=Any()
    private val started=AtomicBoolean(); private val updating=AtomicBoolean(); private val ready=CountDownLatch(1)
    @Volatile var status="Загрузка фильтров"; private set
    @Volatile var updatedAt=0L; private set
    @Volatile var advanced: RequestRules?=null; private set
    @Volatile var custom: RequestRules?=null; private set
    private var baseRules: RequestRules?=null
    private var compiledCosmetic=""
    private var currentDirectory: File?=null
    @Volatile var revision=0L; private set
    private var psl: PublicSuffixes?=null
    private val sources=linkedMapOf("dns" to listOf("https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
        "base" to listOf("https://filters.adtidy.org/extension/chromium/filters/2.txt","https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_2_Base/filter.txt"),
        "russian" to listOf("https://filters.adtidy.org/extension/chromium/filters/1.txt","https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_1_Russian/filter.txt"),
        "privacy" to listOf("https://filters.adtidy.org/extension/chromium/filters/3.txt","https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_3_Spyware/filter.txt"))
    fun start(context: Context) {
        if(!started.compareAndSet(false,true)) return
        val app=context.applicationContext
        executor.execute {
            try {
                psl=PublicSuffixes(app.assets.open("protection/publicsuffix.txt").bufferedReader().use { it.readText() })
                val generation=active(app)
                if(generation!=null) try { install(app,generation); status="Фильтры из кэша" }
                    catch(_: Exception) { install(app,null); status="Встроенные фильтры" }
                else { install(app,null); status="Встроенные фильтры" }
            } catch(error: Exception) { android.util.Log.e("MomijiFilters","Filter bootstrap failed",error); status="Часть фильтров недоступна; базовая защита работает" }
            finally { ready.countDown() }
            if(System.currentTimeMillis()-updatedAt>7L*24*60*60*1000) update(app)
        }
    }
    private fun waitFor(latch: CountDownLatch): Boolean=try { latch.await(8,TimeUnit.SECONDS) } catch(_: InterruptedException) { Thread.currentThread().interrupt(); false }
    fun awaitReady()=waitFor(ready)
    fun awaitIdle(): Boolean { val barrier=CountDownLatch(1); executor.execute { barrier.countDown() }; return waitFor(barrier) }
    fun rebuildCustom(context: Context) {
        val app=context.applicationContext
        executor.execute { synchronized(stateLock) { runCatching { applyCustom(app) }.onFailure { status="Ошибка пользовательских правил" } } }
    }
    fun update(context: Context,finished: ((Boolean)->Unit)?=null) {
        if(!updating.compareAndSet(false,true)) { finished?.invoke(false); return }
        val app=context.applicationContext
        downloader.execute {
            var success=false; var directory: File?=null
            try {
                status="Обновление фильтров"
                val root=File(app.filesDir,"filters-v3").apply { mkdirs() }
                directory=File(root,"g-${UUID.randomUUID()}").apply { mkdirs() }
                val manifest=JSONObject().put("time",System.currentTimeMillis())
                for((name,urls) in sources) {
                    var raw: ByteArray?=null
                    for(url in urls) { raw=runCatching { fetch(url) }.getOrNull(); if(raw!=null) break }
                    val bytes=raw ?: error("Filter source unavailable")
                    val text=bytes.toString(Charsets.UTF_8)
                    require(text.lineSequence().count { it.startsWith("||") || it.contains("##") }>500)
                    GZIPOutputStream(File(directory,"$name.txt.gz").outputStream()).use { it.write(bytes) }
                    manifest.put(name,sha(bytes))
                }
                File(directory,"manifest.json").writeText(manifest.toString())
                // Parse all files before publishing the generation.
                synchronized(stateLock) {
                    val network=readNetwork(app,directory)
                    require(network.count>1000)
                    AdBlock.validateDns(read(app,directory,"dns"))
                    network.write(File(directory,"network.bin").outputStream())
                    val cosmetic=Cosmetic.compile(read(app,directory,"base").lines(),read(app,directory,"russian").lines())
                    File(directory,"cosmetic.txt").writeText(cosmetic)
                    manifest.put("networkBinary",sha(File(directory,"network.bin").readBytes())).put("cosmeticBinary",sha(cosmetic.toByteArray()))
                    File(directory,"manifest.json").writeText(manifest.toString())
                    val pointer=AtomicFile(File(root,"active")); val stream=pointer.startWrite()
                    try { stream.write(directory.name.toByteArray()); pointer.finishWrite(stream) }
                    catch(error: Exception) { pointer.failWrite(stream); throw error }
                    install(app,directory,network)
                }
                status="Фильтры обновлены"; success=true
                root.listFiles()?.filter { it.isDirectory && it.name.startsWith("g-") && it!=directory }?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { stale ->
                    // Only direct owned files; no recursive traversal or symlink-following deletion.
                    if(stale.canonicalFile.parentFile==root.canonicalFile) { stale.listFiles()?.filter { it.canonicalFile.parentFile==stale.canonicalFile }?.forEach { it.delete() }; stale.delete() }
                }
            } catch(_: Exception) { status="Обновление не удалось — прежние фильтры сохранены" }
            finally {
                if(!success) directory?.let { failed ->
                    val root=File(app.filesDir,"filters-v3").canonicalFile
                    val activeName=runCatching { File(root,"active").readText() }.getOrNull()
                    if(failed.name!=activeName && failed.canonicalFile.parentFile==root) { failed.listFiles()?.filter { it.canonicalFile.parentFile==failed.canonicalFile }?.forEach { it.delete() }; failed.delete() }
                }
                updating.set(false); finished?.invoke(success)
            }
        }
    }
    private fun active(context: Context): File?=runCatching {
        val root=File(context.filesDir,"filters-v3")
        val name=AtomicFile(File(root,"active")).openRead().use { it.readBytesBounded(80).toString(Charsets.UTF_8) }
        require(name.matches(Regex("g-[a-f0-9-]{36}")))
        val dir=File(root,name).canonicalFile; require(dir.parentFile==root.canonicalFile)
        val manifest=JSONObject(File(dir,"manifest.json").readText())
        for(key in sources.keys) require(sha(read(context,dir,key).toByteArray())==manifest.getString(key))
        if(manifest.has("networkBinary")) require(sha(File(dir,"network.bin").readBytes())==manifest.getString("networkBinary"))
        if(manifest.has("cosmeticBinary")) require(sha(File(dir,"cosmetic.txt").readBytes())==manifest.getString("cosmeticBinary"))
        dir
    }.getOrNull()
    private fun read(context: Context,generation: File?,name: String): String {
        val stream=if(generation==null) context.assets.open("protection/$name.txt") else GZIPInputStream(File(generation,"$name.txt.gz").inputStream())
        return stream.use { decoded -> decoded.readBytesBounded(20*1024*1024).toString(Charsets.UTF_8) }
    }
    private fun readNetwork(context: Context,directory: File?): RequestRules {
        if(directory==null) return RequestRules(context.assets.open("protection/network.bin"),psl ?: error("No suffix list"))
        val compiled=File(directory,"network.bin")
        if(compiled.isFile) return RequestRules(compiled.inputStream(),psl ?: error("No suffix list"))
        return readNetworkText(context,directory,emptyList())
    }
    private fun readNetworkText(context: Context,directory: File?,additional: List<String>): RequestRules {
        val lines=ArrayList<String>()
        for(name in listOf("base","russian","privacy")) lines.addAll(read(context,directory,name).lineSequence().filter {
            it.length in 2..8192 && !it.startsWith('!') && !it.startsWith('[') && !it.contains('#') &&
                !listOf("removeparam=","redirect=","redirect-rule=","replace=","csp=","permissions=","urltransform=","removeheader=").any(it::contains)
        }.toList())
        lines.addAll(additional)
        return RequestRules(lines,psl ?: error("Public suffix list unavailable"))
    }
    private fun install(context: Context,directory: File?,network: RequestRules?=null) {
        if(directory==null) AdBlock.installOffline(context) else AdBlock.installDns(read(context,directory,"dns"))
        baseRules=network ?: readNetwork(context,directory); currentDirectory=directory
        compiledCosmetic=if(directory==null) context.assets.open("protection/cosmetic.txt").bufferedReader().use { it.readText() }
            else File(directory,"cosmetic.txt").takeIf { it.isFile }?.readText() ?: Cosmetic.compile(read(context,directory,"base").lines(),read(context,directory,"russian").lines())
        applyCustom(context)
        updatedAt=if(directory!=null) JSONObject(File(directory,"manifest.json").readText()).optLong("time") else {
            val date=JSONObject(context.assets.open("protection/manifest.json").bufferedReader().use { it.readText() }).getString("session_date")
            java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).parse(date)?.time ?: System.currentTimeMillis()
        }
        AdBlock.saveSnapshot(context)
        revision++
    }
    private fun applyCustom(context: Context) {
        val lines=ProtectionPreferences(context).customRules.lines()
        if(lines.any { it.contains("badfilter") }) { advanced=readNetworkText(context,currentDirectory,lines); custom=null }
        else { advanced=baseRules; custom=RequestRules(lines,psl ?: error("No suffix list")) }
        Cosmetic.installCompiled(compiledCosmetic,lines)
        revision++
    }
    private fun fetch(url: String): ByteArray {
        val connection=NetworkRoute.open(URL(url)); connection.connectTimeout=7000; connection.readTimeout=12000; connection.instanceFollowRedirects=false
        connection.setRequestProperty("User-Agent","MomijiFilters/3")
        try { require(connection.responseCode==200); return connection.inputStream.use { it.readBytesBounded(20*1024*1024) } }
        finally { connection.disconnect() }
    }
    private fun sha(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
