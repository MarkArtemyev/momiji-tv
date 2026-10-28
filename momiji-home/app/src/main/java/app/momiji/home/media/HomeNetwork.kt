package app.momiji.home.media

import android.content.Context
import android.net.Uri
import app.momiji.net.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Same transport, independent process. Companion IPC exchanges settings, never browsing history. */
object HomeNetwork {
    @Volatile private var nextRefresh=0L
    private var config=NetworkConfig()
    private var proxy: LoopbackProxy?=null
    private var table: DomainTable?=null
    private var protection=true
    private var exceptions=emptySet<String>()
    private var dnsVersion=0L
    fun invalidate() { nextRefresh=0 }
    @Synchronized private fun refresh(context: Context) {
        if(table==null) table=runCatching { DomainTable(context.assets.open("protection/domains.bin")) }.getOrNull()
        if(System.currentTimeMillis()<nextRefresh) return
        nextRefresh=System.currentTimeMillis()+30000
        try {
            val result=context.contentResolver.call(Uri.parse("content://app.momiji.tv.network"),"status",null,null) ?: return
            require(result.getInt("protocol")==1)
            val selected=result.getString("mode","OFF")
            val fresh=NetworkConfig(if(selected in setOf("TLS","TLS_TCP")) "TCP" else selected,result.getString("dns","SYSTEM"),result.getString("socksHost","127.0.0.1"),result.getInt("socksPort",1080))
            protection=result.getBoolean("adblock",true); exceptions=result.getStringArrayList("allowedHosts").orEmpty().toSet()
            val version=result.getLong("dnsVersion")
            if(version>0 && version!=dnsVersion) {
                runCatching { context.contentResolver.openInputStream(Uri.parse("content://app.momiji.tv.network/domains"))?.let { DomainTable(it) } }
                    .getOrNull()?.let { table=it; dnsVersion=version }
            }
            if(fresh!=config) { proxy?.close(); proxy=null; config=fresh }
        } catch(_: Exception) { /* Companion is optional; retain last known settings during temporary failure. */ }
    }
    fun open(context: Context,url: URL): HttpURLConnection {
        refresh(context.applicationContext)
        val endpoint: LoopbackProxy?
        synchronized(this) {
            if(protection && exceptions.none { url.host==it || url.host.endsWith(".$it") } && table?.blocks(url.host)==true) throw IOException("Artwork blocked by Momiji filter")
            if(config.active && proxy==null) { val resolver=SecureResolver(config.dns); proxy=LoopbackProxy(config,resolver::resolve) }
            endpoint=proxy
        }
        return (endpoint?.let { url.openConnection(it.proxy) } ?: url.openConnection()) as HttpURLConnection
    }
}
