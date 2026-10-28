package app.momiji.tv

import android.content.Context
import app.momiji.net.CosmeticRules

/** Document-start scripts: the shield (AdGuard Scriptlets + ExtendedCss + shield.js) and the lists' raw JS rules. */
object ShieldScript {
    @Volatile private var shield: String? = null

    /** The same assembly as tests/web-shield/run.js: one function, so nothing leaks into the page's globals. */
    fun shield(context: Context): String = shield ?: synchronized(this) {
        shield ?: buildString {
            append("(function(){\n")
            for (name in listOf("vendor/scriptlets.js", "vendor/extended-css.js", "shield.js")) {
                append(context.assets.open("protection/$name").bufferedReader().use { it.readText() }).append('\n')
            }
            append("})();")
        }.also { shield = it }
    }

    class Raw(val code: String, val origins: Set<String>)

    /**
     * Raw `#%#` rules from the lists, each in its own try block and gated by the bridge, which applies
     * exceptions and `$jsinject`. Only frames on the rules' own domains receive the script.
     */
    fun raw(scripts: List<CosmeticRules.Script>): Raw? {
        if (scripts.isEmpty()) return null
        val origins = LinkedHashSet<String>()
        for (script in scripts) {
            for (domain in script.include) {
                if (domain.endsWith(".*") || !Regex("[a-z0-9.-]+").matches(domain)) { origins.clear(); origins.add("*"); break }
                for (scheme in listOf("https", "http")) { origins.add("$scheme://$domain"); origins.add("$scheme://*.$domain") }
            }
            if ("*" in origins) break
        }
        val code = buildString {
            append("(function(){var b=window.MomijiShield;if(!b||!/^https?:$/.test(location.protocol))return;var ids;")
            append("try{ids=','+b.scripts(location.href)+',';}catch(e){return;}if(ids===',,')return;\n")
            for (script in scripts) {
                append("if(ids.indexOf(',").append(script.id).append(",')>=0){try{(function(){\n").append(script.code).append("\n})();}catch(e){}}\n")
            }
            append("})();")
        }
        return Raw(code, origins)
    }
}
