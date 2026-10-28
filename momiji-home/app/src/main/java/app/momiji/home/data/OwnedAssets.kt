package app.momiji.home.data

import android.content.Context
import android.net.Uri
import java.io.File

/** Never traverses other directories or follows a link outside this app's owned asset folders. */
class OwnedAssets(context: Context,private val store: HomeStore) {
    private val root=context.applicationContext.filesDir
    fun removeUnusedIcon(uri: String?) {
        uri ?: return
        val referenced=store.profiles().any { profile -> runCatching { uri in store.icons(profile.id).values }.getOrDefault(false) }
        if (referenced) return
        runCatching {
            val parsed=Uri.parse(uri)
            if (parsed.scheme != "file" || !parsed.authority.isNullOrEmpty()) return
            val directory=File(root,"icons").canonicalFile
            val file=File(parsed.path.orEmpty()).canonicalFile
            if (file.parentFile == directory && file.name.matches(Regex("icon-[A-Za-z0-9-]+\\.img"))) file.delete()
        }
    }
    fun pruneOrphans(now: Long=System.currentTimeMillis()) {
        val referenced=HashSet<String>()
        for (profile in store.profiles()) runCatching { referenced.addAll(store.icons(profile.id).values) }
        for (folder in listOf("icons","wallpapers")) runCatching {
            val directory=File(root,folder).canonicalFile
            val prefix=if (folder=="icons") "icon-" else "wallpaper-"
            directory.listFiles()?.forEach { candidate ->
                if (Thread.currentThread().isInterrupted) return
                val file=candidate.canonicalFile
                if (file.parentFile != directory || !file.isFile || !file.name.startsWith(prefix) || file.extension!="img") return@forEach
                val uri=Uri.fromFile(file).toString()
                if (uri !in referenced && uri != store.wallpaperUri && now-file.lastModified()>15*60_000) file.delete()
            }
        }
    }
}
