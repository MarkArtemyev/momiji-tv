package app.momiji.tv

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import app.momiji.net.*

object AdBlock {

    private val builtIn = hashSetOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagservices.com",
        "adservice.google.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "adsrvr.org",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "adform.net",
        "smartadserver.com",
        "teads.tv",
        "media.net",
        "hotjar.com",
        "an.yandex.ru",
        "mc.yandex.ru",
        "yandexadexchange.net",
        "adfox.ru",
        "adfox.yandex.ru",
        "top-fwz1.mail.ru",
        "ad.mail.ru",
        "adriver.ru",
        "betweendigital.com",
        "buzzoola.com",
        "otm-r.com",
        "relap.io",
        "tns-counter.ru",
        "smi2.ru",
        "24smi.info",
        "directadvert.ru",
        "mediametrics.ru",
        "admitad.com",
        "hybrid.ai",
        "sape.ru",
        "marketgid.com",
        "mgid.com",
        "lentainform.com",
    )

    private val rule = Regex("^(@@)?\\|\\|([a-z0-9._-]+)\\^\\|?(?:\\$(.*))?$")
    private class Rules(val blocked: LongArray, val allowed: LongArray)

    @Volatile
    private var rules = Rules(LongArray(0), LongArray(0))
    private val initialized = AtomicBoolean()
    @Volatile private var offline: DomainTable?=null
    @Volatile private var preferences: ProtectionPreferences?=null
    private val decisions = object : LinkedHashMap<String, Boolean>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 512
    }

    val size get() = maxOf(rules.blocked.size,offline?.size ?: 0) + builtIn.size

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        preferences=ProtectionPreferences(context)
        FilterRepository.start(context)
    }

    fun installOffline(context: Context) {
        val table=DomainTable(context.assets.open("protection/domains.bin"))
        synchronized(decisions) { offline=table; rules=Rules(LongArray(0),LongArray(0)); decisions.clear() }
    }
    fun installDns(text: String) {
        val parsed=parse(text.lineSequence()); require(parsed.blocked.size>1000)
        synchronized(decisions) { rules=parsed; offline=null; decisions.clear() }
    }
    fun validateDns(text: String) { require(parse(text.lineSequence()).blocked.size>1000) }
    fun saveSnapshot(context: Context) {
        val file=File(context.filesDir,"dns-table.bin.gz")
        val atomic=android.util.AtomicFile(file); val output=atomic.startWrite()
        try {
            val table=rules
            if(offline!=null) { val gzip=java.util.zip.GZIPOutputStream(output); context.assets.open("protection/domains.bin").use { it.copyTo(gzip) }; gzip.finish() }
            else { val gzip=java.util.zip.GZIPOutputStream(output); val data=DataOutputStream(gzip.buffered(65536))
                data.writeInt(0x4d444e31); data.writeInt(table.blocked.size); data.writeInt(table.allowed.size)
                table.blocked.forEach(data::writeLong); table.allowed.forEach(data::writeLong); data.flush(); gzip.finish() }
            atomic.finishWrite(output)
        } catch(error: Exception) { atomic.failWrite(output); throw error }
    }
    fun blocks(request: FilterRequest): Boolean {
        if(preferences?.siteAllowed(request.documentHost)==true) return false
        val base=FilterRepository.advanced?.decision(request)
        val custom=FilterRepository.custom?.decision(request)
        val contextual=when { base==null->custom; custom==null->base; custom.priority>=base.priority->if(custom.priority==base.priority && custom.blocked && !base.blocked) base else custom; else->base }
        val host=domainDecision(request.host)
        val winner=when { contextual==null->host; host==null->contextual
            contextual.priority>host.priority->contextual
            contextual.priority<host.priority->host
            else->if(!contextual.blocked) contextual else host }
        return winner?.blocked==true
    }
    private fun domainDecision(value: String): RuleDecision? {
        val normalized=DomainTable.normalizeHost(value)
        offline?.let { table -> return table.decision(normalized) ?: if(builtIn.any { normalized==it || normalized.endsWith(".$it") }) RuleDecision(true,1) else null }
        val table=rules; var block=false; var host=normalized
        while(host.isNotEmpty()) {
            val key=hash(host)
            if(table.allowed.binarySearch(key)>=0) return RuleDecision(false,2)
            if(host in builtIn || table.blocked.binarySearch(key)>=0) block=true
            val at=host.indexOf('.'); if(at<0) break; host=host.substring(at+1)
        }
        return if(block) RuleDecision(true,1) else null
    }

    fun blocks(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return synchronized(decisions) {
            decisions[h] ?: lookup(h).also { decisions[h] = it }
        }
    }

    private fun lookup(h: String): Boolean {
        offline?.let { return domainDecision(h)?.blocked==true }
        val table = rules
        var blocked = false
        var start = 0
        while (true) {
            val suffix = h.substring(start)
            val key = hash(suffix)
            // Any matching exception wins, including a permitted child of a blocked parent.
            if (table.allowed.binarySearch(key) >= 0) return false
            if (suffix in builtIn || table.blocked.binarySearch(key) >= 0) blocked = true
            val dot = h.indexOf('.', start)
            if (dot < 0) return blocked
            start = dot + 1
        }
    }

    private fun parse(lines: Sequence<String>): Rules {
        val blocked = Longs()
        val allowed = Longs()
        val disabledBlocks = HashSet<Long>()
        val disabledAllows = HashSet<Long>()
        for (line in lines) {
            val m = rule.matchEntire(line.trim()) ?: continue
            val options = m.groupValues[3]
            // This host matcher has no request-type, document, party or DNS context.
            // Unsupported modifiers must never become unconditional blocks or exceptions.
            if (options.isNotEmpty() && options != "badfilter") continue
            val key = hash(m.groupValues[2])
            val exception = m.groupValues[1].isNotEmpty()
            if (options == "badfilter") {
                (if (exception) disabledAllows else disabledBlocks).add(key)
            } else if (exception) allowed.add(key) else blocked.add(key)
        }
        return Rules(blocked.sorted().filter { it !in disabledBlocks }.distinct().toLongArray(),
            allowed.sorted().filter { it !in disabledAllows }.distinct().toLongArray())
    }

    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    private class Longs {
        private var data = LongArray(1 shl 16)
        private var n = 0

        fun add(v: Long) {
            if (n == data.size) data = data.copyOf(n * 2)
            data[n++] = v
        }

        fun sorted(): LongArray = data.copyOf(n).also { it.sort() }
    }


    const val COSMETIC_JS = """(function(){if(document.getElementById('momiji-ab'))return;var s=document.createElement('style');s.id='momiji-ab';s.textContent='ins.adsbygoogle,[id^="google_ads_"],[id^="div-gpt-ad"],iframe[src*="doubleclick.net"],iframe[src*="googlesyndication"],[id^="yandex_rtb"],[id^="adfox_"],[id^="AdFox"],div[class^="adfox"],[data-ad-slot],[data-adfox],[data-google-query-id],.adsbox,.ad-banner,.advertisement,[aria-label="Реклама"],[aria-label="Advertisement"]{display:none!important}';(document.head||document.documentElement).appendChild(s);})()"""

}
