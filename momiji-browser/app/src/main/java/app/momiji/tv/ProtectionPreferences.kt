package app.momiji.tv

import android.content.Context
import app.momiji.net.*

class ProtectionPreferences(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("protection",Context.MODE_PRIVATE)
    fun config(): NetworkConfig = runCatching { NetworkConfig(prefs.getString("mode","OFF")!!,prefs.getString("dns","SYSTEM")!!,
        prefs.getString("socks_host","127.0.0.1")!!,prefs.getInt("socks_port",1080)) }.getOrDefault(NetworkConfig()).let(::compatible)
    private fun compatible(value: NetworkConfig)=if(value.mode in setOf("TLS","TLS_TCP")) value.copy(mode="TCP") else value
    fun setConfig(value: NetworkConfig) { val config=compatible(value); prefs.edit().putString("mode",config.mode).putString("dns",config.dns)
        .putString("socks_host",config.socksHost).putInt("socks_port",config.socksPort).apply(); NetworkRoute.configure(config) }
    var allowedHosts: Set<String>
        get()=prefs.getStringSet("allowed_hosts",emptySet())!!.toSet()
        set(value) { require(value.size<=256); val normalized=value.map { DomainTable.normalizeHost(it) }.toSet()
            require(normalized.all { HOST.matches(it) }); prefs.edit().putStringSet("allowed_hosts",normalized).apply() }
    fun siteAllowed(host: String)=allowedHosts.any { host==it || host.endsWith(".$it") }
    var customRules: String
        get()=prefs.getString("custom_rules","").orEmpty()
        set(value) { require(value.toByteArray().size<=131072 && value.lineSequence().count()<=2000); prefs.edit().putString("custom_rules",value).apply() }
    var blockPopups: Boolean
        get()=prefs.getBoolean("block_popups",true)
        set(value) { prefs.edit().putBoolean("block_popups",value).apply() }
    var blockThirdPartyCookies: Boolean
        get()=prefs.getBoolean("third_party_cookies",true)
        set(value) { prefs.edit().putBoolean("third_party_cookies",value).apply() }
    companion object { private val HOST=Regex("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*") }
}

object NetworkRoute {
    @Volatile private var config=NetworkConfig()
    private var server: LoopbackProxy?=null
    private var nativeServer: LoopbackProxy?=null
    @Synchronized fun configure(value: NetworkConfig) {
        if(value==config) return
        server?.close(); nativeServer?.close(); server=null; nativeServer=null; config=value
    }
    fun config()=config
    fun nativeConfig()=config.let { if(it.mode in setOf("TLS","TLS_TCP")) it.copy(mode="TCP") else it }
    @Synchronized fun endpoint(nativeClient: Boolean=false): LoopbackProxy? {
        if(!config.active) return null
        if(nativeClient) {
            if(nativeServer==null) { val compatible=nativeConfig(); val resolver=SecureResolver(compatible.dns); nativeServer=LoopbackProxy(compatible,resolver::resolve) }
            return nativeServer
        }
        if(server==null) { val resolver=SecureResolver(config.dns); server=LoopbackProxy(config,resolver::resolve) }
        return server
    }
    fun open(url: java.net.URL): java.net.HttpURLConnection = (endpoint(true)?.let { url.openConnection(it.proxy) } ?: url.openConnection()) as java.net.HttpURLConnection
}
