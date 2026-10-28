import app.momiji.net.CosmeticRules
import app.momiji.net.PublicSuffixes
import app.momiji.net.RequestRules
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Rebuilds the bundled compiled filters (network.bin.gz, cosmetic.txt.gz) from the bundled lists with
 * the same core code the app runs, so a fresh install starts with the current engine's format.
 *
 *   ./momiji-browser/gradlew -p tests/network-core compileProtection
 */
fun main(args: Array<String>) {
    val assets = File(args[0])
    fun list(name: String) = GZIPInputStream(File(assets, "$name.txt.gz").inputStream()).bufferedReader().readText()
    val suffixes = PublicSuffixes(list("publicsuffix"))
    // Same order as FilterRepository.lists: ad lists before the tracking-pixel-heavy privacy list.
    val texts = listOf("base", "mobile", "russian", "privacy").map(::list)
    val lines = texts.flatMap { it.lines().filter { line -> line.length in 2..8192 } }
    val rules = RequestRules(lines, suffixes)
    GZIPOutputStream(File(assets, "network.bin.gz").outputStream()).use(rules::write)
    val cosmetic = CosmeticRules.compile(texts.asSequence().flatMap { it.lineSequence() })
    GZIPOutputStream(File(assets, "cosmetic.txt.gz").outputStream()).use { it.write(cosmetic.toByteArray()) }
    println("network rules=${rules.count} skipped=${rules.skipped}; cosmetic lines=${cosmetic.lineSequence().count() - 1}")
}
