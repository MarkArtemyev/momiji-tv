package app.momiji.tv

import android.content.Context
import app.momiji.net.CosmeticRules
import app.momiji.net.PublicSuffixes

/** Page-side rules (hiding, extended CSS, styles, scriptlets, JS) served to the shield; see [CosmeticRules]. */
object Cosmetic {
    @Volatile private var rules: CosmeticRules? = null
    private val pages = object : LinkedHashMap<String, CosmeticRules.Page>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CosmeticRules.Page>?) = size > 64
    }

    fun init(context: Context) {
        FilterRepository.start(context)
    }

    fun compile(lists: List<String>): String = CosmeticRules.compile(lists.asSequence().flatMap { it.lineSequence() })

    fun installCompiled(text: String, custom: List<String>, suffixes: PublicSuffixes) {
        val fresh = CosmeticRules(text, custom, suffixes)
        synchronized(pages) { rules = fresh; pages.clear() }
    }

    fun page(host: String, flags: Int): CosmeticRules.Page? {
        val current = rules ?: return null
        val key = "$host|$flags"
        synchronized(pages) { pages[key]?.let { return it } }
        val page = current.page(host, flags)
        synchronized(pages) { if (rules === current) pages[key] = page }
        return page
    }

    fun generic(host: String, tokens: Collection<String>, flags: Int): String = rules?.generic(host, tokens, flags).orEmpty()

    fun scripts(): List<CosmeticRules.Script> = rules?.scripts.orEmpty()

    val counts: Map<CosmeticRules.Kind, Int> get() = rules?.counts.orEmpty()
}
