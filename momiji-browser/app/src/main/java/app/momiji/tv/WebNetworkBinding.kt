package app.momiji.tv

import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

class WebNetworkBinding(private val web: WebView) {
    private var installed: String?=null
    private var pending=false
    fun apply(done: (Boolean)->Unit) {
        if(pending) { web.postDelayed({ apply(done) },40); return }
        val config=ProtectionPreferences(web.context).config(); NetworkRoute.configure(config)
        val endpoint=runCatching { NetworkRoute.endpoint() }.getOrElse { done(false); return }
        val target=endpoint?.port?.toString() ?: "OFF"
        if(target==installed) { done(true); return }
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { done(!config.active); return }
        pending=true
        val executor=java.util.concurrent.Executor { task -> web.post(task) }
        val finished=Runnable { pending=false; installed=target
            if(ProtectionPreferences(web.context).config()!=config) apply(done) else done(true) }
        try {
            if(endpoint==null) ProxyController.getInstance().clearProxyOverride(executor,finished)
            else ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:${endpoint.port}").addBypassRule("appassets.androidplatform.net")
                .removeImplicitRules().build(),executor,finished)
        } catch(_: RuntimeException) { pending=false; done(false) }
    }
}
