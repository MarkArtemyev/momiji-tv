package app.momiji.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.widget.*
import app.momiji.net.*
import java.net.URL
import java.util.concurrent.Executors
import app.momiji.net.CosmeticRules

class NetworkSettingsActivity: Activity() {
    private lateinit var prefs: ProtectionPreferences
    private lateinit var store: Store
    private lateinit var body: LinearLayout
    private var filterStatus: TextView?=null
    private var filterButton: TextView?=null
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"Momiji-NetCheck").apply { isDaemon=true } }
    private val amber=0xffffb347.toInt()
    private var currentHost=""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs=ProtectionPreferences(this); store=Store(this); NetworkRoute.configure(prefs.config()); AdBlock.init(this)
        currentHost=intent.getStringExtra("page")?.let { runCatching { Uri.parse(it).takeIf { uri -> uri.scheme in setOf("http","https") }?.host }.getOrNull() }.orEmpty()
        val scroll=ScrollView(this).apply { setBackgroundColor(0xff11151c.toInt()); isFillViewport=true }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(40),dp(24),dp(40),dp(32)) }
        scroll.addView(body); setContentView(scroll); render()
        worker.execute { FilterRepository.awaitReady(); runOnUiThread { if(!isDestroyed) refreshStatus() } }
    }
    private fun render() {
        body.removeAllViews()
        body.addView(TextView(this).apply { text="Сеть и защита Momiji"; textSize=27f; setTextColor(Color.WHITE); typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        body.addView(TextView(this).apply { text="Браузер и главный экран · настройки сохраняются на устройстве"; textSize=14f; setTextColor(0xffb7bfcb.toInt()); setPadding(0,dp(7),0,dp(18)) })
        button("Реклама и трекеры · ${if(store.adBlock) "включена защита" else "выключена защита"}") { store.adBlock=!store.adBlock; render() }
        if(currentHost.isNotEmpty()) button("Этот сайт · ${if(prefs.siteAllowed(currentHost)) "исключение" else "фильтруется"} · $currentHost") {
            val allowed=prefs.allowedHosts.toMutableSet()
            if(prefs.siteAllowed(currentHost)) allowed.removeAll { currentHost==it || currentHost.endsWith(".$it") } else allowed.add(currentHost)
            prefs.allowedHosts=allowed; render()
        }
        button("Исключения для сайтов · ${prefs.allowedHosts.size}") { edit("Домены без фильтрации","По одному домену в строке, без https://",prefs.allowedHosts.sorted().joinToString("\n"),32768) {
            prefs.allowedHosts=it.lines().map(String::trim).filter(String::isNotEmpty).toSet(); render()
        } }
        button("Свои правила · ${prefs.customRules.lines().count { it.isNotBlank() }}") { edit("Правила блокировки","Например: ||ads.example.org^ или example.org##.ad-banner",prefs.customRules,131072) {
            prefs.customRules=it; FilterRepository.rebuildCustom(this); render()
        } }
        button("Всплывающие окна · ${if(prefs.blockPopups) "блокировать без действия пользователя" else "обычное поведение"}") { prefs.blockPopups=!prefs.blockPopups; render() }
        button("Сторонние cookies · ${if(prefs.blockThirdPartyCookies) "ограничены" else "разрешены"}") { prefs.blockThirdPartyCookies=!prefs.blockThirdPartyCookies; render() }
        filterButton=button("Обновить фильтры · ${AdBlock.size} доменов") {
            FilterRepository.update(this) { okay -> runOnUiThread { if(!isDestroyed) { toast(if(okay) "Фильтры обновлены" else FilterRepository.status); render() } } }
            toast("Проверяю обновления; прежняя защита продолжает работать")
        }
        val config=prefs.config()
        val modes=arrayOf("OFF","TCP","SOCKS5")
        val names=arrayOf("Выключен","TCP-разбиение","Прокси ByeDPI / SOCKS5")
        button("Обход DPI · ${names[modes.indexOf(config.mode)]}") {
            choose("Режим подключения",names,modes.indexOf(config.mode)) { prefs.setConfig(config.copy(mode=modes[it])); render() }
        }
        val dns=arrayOf("SYSTEM","CLOUDFLARE","GOOGLE"); val dnsNames=arrayOf("Системный","Cloudflare · HTTPS","Google · HTTPS")
        button("DNS · ${dnsNames[dns.indexOf(config.dns)]}") {
            choose("Кому отправлять DNS-запросы",dnsNames,dns.indexOf(config.dns)) { prefs.setConfig(config.copy(dns=dns[it])); render() }
        }
        button("Адрес SOCKS5 · ${config.socksHost}:${config.socksPort}") { edit("Прокси ByeDPI / SOCKS5","Сначала запусти свой прокси. Например: 127.0.0.1:1080","${config.socksHost}:${config.socksPort}",300) {
            val uri=Uri.parse("socks://"+it.trim()); require(uri.host!=null && uri.userInfo==null && uri.path.isNullOrEmpty() && uri.query==null && uri.fragment==null && uri.port in 1..65535) { "Нужны адрес и порт, например 127.0.0.1:1080" }
            prefs.setConfig(config.copy(socksHost=uri.host!!,socksPort=uri.port)); render()
        } }
        button("Проверить подключение") { edit("Проверка HTTPS","Адрес сайта для проверки соединения и сертификата","https://example.com",2048) { input ->
            val url=URL(input.trim()); require(url.protocol=="https" && url.userInfo==null)
            toast("Проверяю ${url.host}")
            worker.execute { val start=System.nanoTime()
                val result=runCatching { val connection=NetworkRoute.open(url); connection.connectTimeout=8000; connection.readTimeout=8000; connection.requestMethod="HEAD"; connection.instanceFollowRedirects=false
                    try { "${url.host}: HTTP ${connection.responseCode}, ${(System.nanoTime()-start)/1000000} мс. TLS-сертификат проверен." } finally { connection.disconnect() } }
                    .getOrElse { "Не удалось подключиться: ${it.javaClass.simpleName}" }
                runOnUiThread { if(!isDestroyed) AlertDialog.Builder(this).setTitle("Проверка соединения").setMessage(result).setPositiveButton("Готово",null).show() }
            }
        } }
        filterStatus=TextView(this).apply { textSize=13f; setTextColor(0xffb7bfcb.toInt()); setPadding(0,dp(14),0,dp(10)) }
        body.addView(filterStatus); refreshStatus()
        button("Как работает защита и где её пределы") { AlertDialog.Builder(this).setTitle("Защита внутри Momiji")
            .setMessage("Фильтры браузера учитывают адрес, тип запроса, сайт и исключения. Главный экран блокирует рекламные и отслеживающие домены при загрузке картинок. Чужие приложения и системный трафик эти настройки не фильтруют.\n\nTCP-разбиение меняет начальную отправку HTTPS без расшифровки и подмены сертификатов. VPN-слот Android не используется; IP не скрывается. Обход зависит от провайдера: блокировки по IP, UDP/QUIC и ограничения VPN-протоколов универсально не снимаются.\n\nSOCKS5 подключается к запущенному тобой ByeDPI или другому доверенному прокси. DoH отправляет DNS выбранному провайдеру по HTTPS. Если сайт сломался, добавь его в исключения или отключи обход. \n\nРеклама в видео: подставляются пустые VAST и заглушки рекламных SDK, чтобы плеер сразу переходил к фильму; из HLS-записей вырезаются вставленные сервером ролики (метки SCTE-35, правила \$hls, рекламные адреса). В прямых эфирах и при бесшовной вставке без меток ролики могут остаться. Реклама сторонних приложений, в том числе приложения YouTube, не фильтруется.")
            .setPositiveButton("Понятно",null).show() }
        button("Готово") { finish() }
    }
    private fun choose(title: String,values: Array<String>,selected: Int,apply: (Int)->Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values,selected) { dialog,which -> apply(which); dialog.dismiss() }.setNegativeButton("Отмена",null).show()
    }
    private fun edit(title: String,hint: String,value: String,limit: Int,save: (String)->Unit) {
        val input=EditText(this).apply { setText(value); this.hint=hint; textSize=15f; setTextColor(Color.WHITE); minLines=2; maxLines=8; filters=arrayOf(InputFilter.LengthFilter(limit)); setSelectAllOnFocus(true) }
        val box=FrameLayout(this).apply { setPadding(dp(24),dp(12),dp(24),0); addView(input) }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { save(input.text.toString()); dialog.dismiss() } catch(error: Exception) { input.error=error.message ?: "Проверь значение" }
        } }; dialog.show()
    }
    private fun refreshStatus() {
        filterButton?.text="Обновить фильтры · ${AdBlock.size} доменов"
        val rules=FilterRepository.advanced; val page=Cosmetic.counts
        filterStatus?.text="${FilterRepository.status}\nСетевых правил: ${rules?.count ?: 0} · подмен для плееров и скриптов: ${rules?.redirectCount ?: 0} · " +
            "попапов: ${rules?.popupCount ?: 0} · правок ответов: ${rules?.contentCount ?: 0}\n" +
            "Скриптлетов: ${page[CosmeticRules.Kind.SCRIPTLET] ?: 0} · правил скрытия: ${(page[CosmeticRules.Kind.HIDE] ?: 0)+(page[CosmeticRules.Kind.EXTENDED] ?: 0)} · " +
            "стилей: ${page[CosmeticRules.Kind.STYLE] ?: 0}. Режим меняется для новых соединений — перезагрузи страницу или открой плеер заново."
    }
    private fun button(label: String,action: ()->Unit): TextView {
        val view=TextView(this).apply {
            text=label; textSize=16f; setTextColor(Color.WHITE); gravity=Gravity.CENTER_VERTICAL; minHeight=dp(52); setPadding(dp(18),dp(12),dp(18),dp(12))
            isFocusable=true; isClickable=true
            fun style(focus: Boolean) { background=GradientDrawable().apply { setColor(if(focus) 0xff34313a.toInt() else 0xff202630.toInt()); cornerRadius=dp(12).toFloat(); setStroke(dp(1),if(focus) amber else 0xff353d49.toInt()) } }
            style(false); setOnFocusChangeListener { _,focus -> style(focus) }; setOnClickListener { action() }
        }
        body.addView(view,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        return view
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    private fun toast(text: String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun dp(n: Int)=(resources.displayMetrics.density*n+.5f).toInt()
}
