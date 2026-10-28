package app.momiji.tv

object ProtectionScripts {
    fun start(prefs: ProtectionPreferences): String {
        val hosts=org.json.JSONArray(prefs.allowedHosts.toList())
        val popup=if(prefs.blockPopups) """
            var originalOpen=window.open;
            window.open=function(){if(navigator.userActivation&&!navigator.userActivation.isActive)return null;return originalOpen.apply(this,arguments);};
        """.trimIndent() else ""
        return """
            (function(){var allow=$hosts;var host=location.hostname.toLowerCase();
            if(allow.some(function(x){return host===x||host.endsWith('.'+x);}))return;
            function note(url,method,kind){try{if(window.MomijiShield)window.MomijiShield.note(new URL(url,location.href).href,String(method||'GET'),kind);}catch(e){}}
            if(window.fetch){var fetchOriginal=window.fetch;window.fetch=function(input,options){note(typeof input==='string'||input instanceof URL?input:input.url,(options&&options.method)||(input&&input.method)||'GET','fetch');return fetchOriginal.apply(this,arguments);};}
            var openOriginal=XMLHttpRequest.prototype.open,sendOriginal=XMLHttpRequest.prototype.send,tag=Symbol('momijiRequest');
            XMLHttpRequest.prototype.open=function(method,url){this[tag]=[url,method];return openOriginal.apply(this,arguments);};
            XMLHttpRequest.prototype.send=function(){if(this[tag])note(this[tag][0],this[tag][1],'xhr');return sendOriginal.apply(this,arguments);};
            if(navigator.sendBeacon){var beaconOriginal=navigator.sendBeacon;navigator.sendBeacon=function(url){note(url,'POST','beacon');return beaconOriginal.apply(this,arguments);};}
            $popup
            function apply(){${AdBlock.COSMETIC_JS}}
            if(document.documentElement)apply();else{var observer=new MutationObserver(function(){if(document.documentElement){observer.disconnect();apply();}});observer.observe(document,{childList:true,subtree:true});}
            })();
        """.trimIndent()
    }
}
