package app.momiji.tv

import android.content.Context
import org.json.JSONObject

object Cosmetic {

    private val unsupported = listOf(
        ":-abp-", ":has-text", ":contains", ":xpath", ":style", ":matches-", ":upward", ":remove",
        ":min-text-length", ":watch-attr", ":others", ":nth-ancestor", ":if(", "+js(", "[-ext-",
    )

    private data class SelectorRule(val selector: String, val excluded: Set<String>)
    private class Rules(
        val site: Map<String, List<SelectorRule>>,
        val generic: List<SelectorRule>,
        val allow: Map<String, List<SelectorRule>>,
        val genericAllow: List<SelectorRule>,
    )

    @Volatile
    private var rules = Rules(emptyMap(), emptyList(), emptyMap(), emptyList())
    private val scripts = object : LinkedHashMap<String, String?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 32
    }

    fun init(context: Context) {
        FilterRepository.start(context)
    }
    fun install(base: List<String>,russian: List<String>) {
        installCompiled(compile(base,russian),emptyList())
    }
    fun compile(base: List<String>,russian: List<String>): String {
        val parsed=ArrayList<String>()
        base.forEach { parse(it.trim(),true,parsed) }; russian.forEach { parse(it.trim(),false,parsed) }
        return CACHE_HEADER+"\n"+parsed.joinToString("\n")
    }
    fun installCompiled(text: String,custom: List<String>) {
        val parsed=ArrayList<String>(); custom.forEach { parse(it.trim(),true,parsed) }
        val lines=text.lineSequence(); require(lines.firstOrNull()==CACHE_HEADER)
        val fresh=load(lines.drop(1).filter(String::isNotEmpty).toList()+parsed)
        synchronized(scripts) { rules=fresh; scripts.clear() }
    }

    fun script(host: String): String? {
        val key = host.lowercase().trimEnd('.')
        return synchronized(scripts) {
            if (scripts.containsKey(key)) return@synchronized scripts[key]
            buildScript(key).also { scripts[key] = it }
        }
    }

    private fun buildScript(host: String): String? {
        val r = rules
        val suffixes = suffixes(host).toHashSet()
        fun applies(rule: SelectorRule) = rule.excluded.none { it in suffixes }
        val excluded = HashSet<String>()
        r.genericAllow.filter(::applies).forEach { excluded.add(it.selector) }
        suffixes.forEach { s -> r.allow[s]?.filter(::applies)?.forEach { excluded.add(it.selector) } }
        val chosen = LinkedHashSet<String>()
        suffixes.forEach { s -> r.site[s]?.filter(::applies)?.forEach { chosen.add(it.selector) } }
        r.generic.filter(::applies).forEach { chosen.add(it.selector) }
        chosen.removeAll(excluded)
        if (chosen.isEmpty()) return null
        val css = chosen.take(1600).joinToString("") { "$it{display:none!important}" }.takeIf { it.length<=196608 } ?: return null
        return "(function(){var s=document.getElementById('momiji-cf');if(!s){s=document.createElement('style');s.id='momiji-cf';" +
            "(document.head||document.documentElement).appendChild(s);}s.textContent=${JSONObject.quote(css)};})()"
    }

    private fun suffixes(host: String): List<String> {
        val out = mutableListOf(host)
        var i = host.indexOf('.')
        while (i >= 0 && host.indexOf('.', i + 1) >= 0) {
            out.add(host.substring(i + 1))
            i = host.indexOf('.', i + 1)
        }
        return out
    }

    private fun parse(line: String, withGeneric: Boolean, out: MutableList<String>) {
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        if (line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("#@$#") || line.contains("#@?#")) return
        val exception = line.indexOf("#@#")
        val at = if (exception >= 0) exception else line.indexOf("##")
        if (at < 0) return
        val selector = line.substring(at + if (exception >= 0) 3 else 2)
        if (selector.isEmpty() || selector.length > 8192 || selector.any { it in "\t{}\u0000" } || unsupported.any { selector.contains(it) }) return
        val domains = line.substring(0, at).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        // Dropping an unsupported domain could broaden the remaining rule.
        if (domains.any { !DOMAIN.matches(it.removePrefix("~")) }) return
        if (exception < 0 && !withGeneric && domains.none { !it.startsWith("~") }) return
        out.add("${if (exception >= 0) "A" else "H"}\t${domains.joinToString(",").ifEmpty { "*" }}\t$selector")
    }

    private fun load(lines: List<String>): Rules {
        val site = HashMap<String, MutableList<SelectorRule>>()
        val generic = ArrayList<SelectorRule>()
        val allow = HashMap<String, MutableList<SelectorRule>>()
        val genericAllow = ArrayList<SelectorRule>()
        for (line in lines) {
            val fields = line.split('\t', limit = 3)
            require(fields.size == 3 && fields[0] in setOf("H", "A") && fields[2].isNotEmpty())
            val domains = if (fields[1] == "*") emptyList() else fields[1].split(',')
            require(domains.all { DOMAIN.matches(it.removePrefix("~")) })
            val positive = domains.filterNot { it.startsWith("~") }
            val selector = SelectorRule(fields[2], domains.filter { it.startsWith("~") }.map { it.substring(1) }.toSet())
            val exception = fields[0] == "A"
            if (positive.isEmpty()) {
                (if (exception) genericAllow else generic).add(selector)
            } else {
                val target = if (exception) allow else site
                positive.forEach { target.getOrPut(it) { ArrayList() }.add(selector) }
            }
        }
        return Rules(site, generic.distinct(), allow, genericAllow.distinct())
    }

    private val DOMAIN = Regex("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*")
    private const val CACHE_HEADER = "momiji-cosmetic-v2"
}
