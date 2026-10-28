package app.momiji.tv

import android.webkit.JavascriptInterface
import app.momiji.net.ResourceType

/** WebView omits request type for extensionless fetch/XHR. Document-start reports only metadata. */
class RequestHints {
    data class Hint(val type: ResourceType,val page: String,val time: Long)
    private val values=object: LinkedHashMap<String,java.util.ArrayDeque<Hint>>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,java.util.ArrayDeque<Hint>>?)=size>512
    }
    @JavascriptInterface
    @Synchronized fun note(url: String,method: String,kind: String,page: String) {
        if(url.length>16384 || page.length>8192 || method.length>16 || !url.startsWith("http")) return
        val type=when(kind) { "fetch","xhr"->ResourceType.XMLHTTPREQUEST; "beacon"->ResourceType.PING; else->return }
        val key=method.uppercase()+"|"+url
        val queue=values.getOrPut(key) { java.util.ArrayDeque() }
        while(queue.size>=8) queue.removeFirst()
        queue.addLast(Hint(type,page,android.os.SystemClock.uptimeMillis()))
    }
    @Synchronized fun consume(url: String,method: String): Hint? {
        val key=method.uppercase()+"|"+url; val queue=values[key] ?: return null
        val now=android.os.SystemClock.uptimeMillis()
        while(queue.isNotEmpty()) {
            val item=queue.removeFirst(); if(now-item.time<5000) { if(queue.isEmpty()) values.remove(key); return item }
        }
        values.remove(key); return null
    }
    @Synchronized fun clear()=values.clear()
}
