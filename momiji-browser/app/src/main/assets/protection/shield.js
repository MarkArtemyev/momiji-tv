/*
 * Momiji shield. Runs at document start in every http(s) frame, before any page script, inside one
 * function together with the vendored AdGuard Scriptlets (SCRIPTLETS, SCRIPTLET_ALIASES) and ExtendedCss.
 * Everything page-specific comes from the Kotlin bridge (window.MomijiShield); no code is ever eval'd,
 * so it also works on pages whose CSP forbids eval and inline styles.
 *
 * payload: { v, f: page flags, css, ext: [rules], s: [[scriptlet, ...args]], js: [ids], g: generic hiding,
 *            jp: page has $jsonprune rules, p: block popups without a user gesture }
 */
var bridge = window.MomijiShield;
if (!bridge || !/^https?:$/.test(location.protocol) || location.hostname === 'appassets.androidplatform.net') return;

// Natives captured before any scriptlet or page script can replace them.
var nativeSetTimeout = window.setTimeout.bind(window);
var NativeMutationObserver = window.MutationObserver;
var nativeJSON = { parse: JSON.parse, stringify: JSON.stringify };
var NativeResponse = window.Response;
var NativeHeaders = window.Headers;
var NativeURL = window.URL;
var href = location.href;

var payload = null;
try { payload = nativeJSON.parse(bridge.page(href) || 'null'); } catch (e) { payload = null; }
if (!payload || payload.v !== 1 || (payload.f & 1)) return;

function guard(action) { try { action(); } catch (e) { /* one broken part never stops the rest */ } }

function absolute(url) {
    try { return new NativeURL(String(url), href).href; } catch (e) { return String(url); }
}

function whenRoot(action) {
    if (document.documentElement) { action(); return; }
    var observer = new NativeMutationObserver(function () {
        if (!document.documentElement) return;
        observer.disconnect();
        action();
    });
    observer.observe(document, { childList: true, subtree: true });
}

// --- Scriptlets ---------------------------------------------------------------------------------
guard(function () {
    (payload.s || []).forEach(function (call) {
        var name = call[0];
        var scriptlet = SCRIPTLETS[name] || SCRIPTLETS[SCRIPTLET_ALIASES[name]];
        if (!scriptlet) return;
        var args = call.slice(1);
        var source = { name: name, args: args, engine: 'corelibs', version: SCRIPTLETS_VERSION, verbose: false,
            ruleText: '', domainName: location.hostname, uniqueId: 'momiji' };
        try { scriptlet(source, args); } catch (e) { /* the page keeps working without this one */ }
    });
});

// --- Element hiding and injected styles ---------------------------------------------------------
var sheets = [];
function adopt(sheet) {
    var current = document.adoptedStyleSheets;
    if (current.indexOf(sheet) < 0) document.adoptedStyleSheets = current.concat([sheet]);
}
function makeSheet(text) {
    // Constructed sheets are invisible to the DOM and not subject to the page's style-src CSP.
    if (document.adoptedStyleSheets && window.CSSStyleSheet && CSSStyleSheet.prototype.replaceSync) {
        try {
            var sheet = new CSSStyleSheet();
            sheet.replaceSync(text);
            adopt(sheet);
            sheets.push(sheet);
            return sheet;
        } catch (e) { /* fall back to a style element */ }
    }
    var style = document.createElement('style');
    style.textContent = text;
    whenRoot(function () { (document.head || document.documentElement).appendChild(style); });
    return style.sheet ? style.sheet : { insertRule: function (rule) { style.textContent += '\n' + rule; }, cssRules: [] };
}
guard(function () { if (payload.css) makeSheet(payload.css); });

// Generic `##.class` / `##[id]` selectors: report the classes and ids this frame uses, inject matches.
guard(function () {
    if (!payload.g) return;
    var seen = new Set();
    var queue = [];
    var pending = false;
    var genericSheet = null;
    function flush() {
        pending = false;
        while (queue.length) {
            var css = '';
            try { css = bridge.cosmetic(queue.splice(0, 800).join(' '), href) || ''; } catch (e) { css = ''; }
            if (!css) continue;
            if (!genericSheet) genericSheet = makeSheet('');
            css.split('\n').forEach(function (rule) {
                if (!rule) return;
                try { genericSheet.insertRule(rule, genericSheet.cssRules.length); } catch (e) { /* invalid selector */ }
            });
        }
        sheets.forEach(function (sheet) { try { adopt(sheet); } catch (e) {} });
    }
    function push(token) {
        if (seen.has(token) || seen.size > 20000) return;
        seen.add(token);
        queue.push(token);
        if (!pending) { pending = true; nativeSetTimeout(flush, seen.size < 50 ? 0 : 60); }
    }
    function collect(element) {
        var id = element.id;
        if (id && typeof id === 'string') push('#' + id);
        var classes = element.classList;
        if (classes) for (var i = 0; i < classes.length; i++) push('.' + classes[i]);
    }
    function scan(node) {
        if (node.nodeType !== 1) return;
        collect(node);
        var all = node.querySelectorAll('[id],[class]');
        for (var i = 0; i < all.length; i++) collect(all[i]);
    }
    new NativeMutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
            var record = records[i];
            if (record.type === 'attributes') { if (record.target.nodeType === 1) collect(record.target); continue; }
            for (var j = 0; j < record.addedNodes.length; j++) scan(record.addedNodes[j]);
        }
    }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
    whenRoot(function () { scan(document.documentElement); });
});

// --- Extended CSS (:has-text, :upward, :matches-css, remove...) via AdGuard ExtendedCss ------------
guard(function () {
    var rules = payload.ext || [];
    if (!rules.length || typeof ExtendedCss !== 'function') return;
    whenRoot(function () {
        try {
            new ExtendedCss({ styleSheet: rules.join('\n') }).apply();
        } catch (e) {
            rules.forEach(function (rule) { try { new ExtendedCss({ styleSheet: rule }).apply(); } catch (x) {} });
        }
    });
});

// --- Network responses: HLS ad segments and $jsonprune ------------------------------------------
var PLAYLIST = /\.m3u8(?:[?#]|$)/i;
function note(url, method, kind) {
    try { bridge.note(url, String(method || 'GET'), kind); } catch (e) {}
}
function plan(url, contentType) {
    var hls = PLAYLIST.test(url) || /mpegurl/i.test(contentType || '');
    var prune = null;
    if (payload.jp && !hls) {
        try { var answer = bridge.response(url, href, 'xmlhttprequest'); if (answer) prune = nativeJSON.parse(answer); } catch (e) { prune = null; }
    }
    return hls || (prune && prune.length) ? { hls: hls, prune: prune } : null;
}
function rewriteText(text, url, how) {
    if (typeof text !== 'string') return null;
    if (how.hls) {
        if (text.slice(0, 7) !== '#EXTM3U') return null;
        try { return bridge.hls(text, url, href) || null; } catch (e) { return null; }
    }
    if (how.prune) {
        var value;
        try { value = nativeJSON.parse(text); } catch (e) { return null; }
        return jsonPrune(value, how.prune) ? nativeJSON.stringify(value) : null;
    }
    return null;
}

guard(function () {
    var nativeFetch = window.fetch;
    if (!nativeFetch || !NativeResponse) return;
    function forge(response, text) {
        var headers = new NativeHeaders(response.headers);
        headers.delete('content-length');
        var copy = new NativeResponse(text, { status: response.status, statusText: response.statusText, headers: headers });
        Object.defineProperties(copy, {
            url: { value: response.url }, redirected: { value: response.redirected }, type: { value: response.type }
        });
        return copy;
    }
    window.fetch = new Proxy(nativeFetch, {
        apply: function (target, thisArg, args) {
            var input = args[0];
            var init = args[1] || {};
            var url = absolute(typeof input === 'string' || input instanceof NativeURL ? input : input && input.url);
            note(url, init.method || input && input.method, 'fetch');
            return Reflect.apply(target, thisArg, args).then(function (response) {
                var how;
                try { how = response.ok && response.type !== 'opaque' && plan(response.url || url, response.headers.get('content-type')); } catch (e) { how = null; }
                if (!how) return response;
                return response.clone().text().then(function (text) {
                    var changed = rewriteText(text, response.url || url, how);
                    if (changed == null) return response;
                    try { return forge(response, changed); } catch (e) { return response; }
                }, function () { return response; });
            });
        }
    });
});

guard(function () {
    var XHR = window.XMLHttpRequest;
    if (!XHR) return;
    var proto = XHR.prototype;
    var requests = new WeakMap();
    var textGetter = Object.getOwnPropertyDescriptor(proto, 'responseText').get;
    var responseGetter = Object.getOwnPropertyDescriptor(proto, 'response').get;
    proto.open = new Proxy(proto.open, {
        apply: function (target, xhr, args) {
            requests.set(xhr, { url: absolute(args[1]), method: String(args[0] || 'GET'), done: false, text: null, value: undefined });
            return Reflect.apply(target, xhr, args);
        }
    });
    proto.send = new Proxy(proto.send, {
        apply: function (target, xhr, args) {
            var request = requests.get(xhr);
            if (request) { request.done = false; note(request.url, request.method, 'xhr'); }
            return Reflect.apply(target, xhr, args);
        }
    });
    // Computed once per finished request; both getters then serve the rewritten body.
    function result(xhr) {
        var request = requests.get(xhr);
        if (!request || xhr.readyState !== 4) return null;
        if (!request.done) {
            request.done = true;
            var type = xhr.responseType;
            if ((type === '' || type === 'text' || type === 'json') && xhr.status >= 200 && xhr.status < 300) {
                var how = plan(xhr.responseURL || request.url, xhr.getResponseHeader('content-type'));
                if (how) {
                    if (type === 'json') {
                        var value = Reflect.apply(responseGetter, xhr, []);
                        if (how.prune && value && jsonPrune(value, how.prune)) request.value = value;
                    } else {
                        request.text = rewriteText(Reflect.apply(textGetter, xhr, []), xhr.responseURL || request.url, how);
                    }
                }
            }
        }
        return request;
    }
    function patch(name, getter) {
        var descriptor = Object.getOwnPropertyDescriptor(proto, name);
        Object.defineProperty(proto, name, {
            configurable: true, enumerable: descriptor.enumerable, set: descriptor.set,
            get: new Proxy(getter, {
                apply: function (target, xhr, args) {
                    var value = Reflect.apply(target, xhr, args);
                    var request;
                    try { request = result(xhr); } catch (e) { request = null; }
                    if (!request) return value;
                    if (request.value !== undefined && name === 'response') return request.value;
                    return request.text != null ? request.text : value;
                }
            })
        });
    }
    patch('responseText', textGetter);
    patch('response', responseGetter);

    if (navigator.sendBeacon) {
        navigator.sendBeacon = new Proxy(navigator.sendBeacon, {
            apply: function (target, thisArg, args) { note(absolute(args[0]), 'POST', 'beacon'); return Reflect.apply(target, thisArg, args); }
        });
    }
});

// --- Popups without a user gesture --------------------------------------------------------------
guard(function () {
    if (!payload.p || !window.open) return;
    window.open = new Proxy(window.open, {
        apply: function (target, thisArg, args) {
            if (navigator.userActivation && !navigator.userActivation.isActive) return null;
            return Reflect.apply(target, thisArg, args);
        }
    });
});

/*
 * AdGuard $jsonprune paths: $.a.b, $..key, $..[a, b], .* / [*], and [?(has <path>)],
 * [?(key-eq <path> <value>)], [?(key-substr <path> <text>)] filters. Returns true when anything was removed.
 */
function jsonPrune(root, paths) {
    var removed = false;
    for (var i = 0; i < paths.length; i++) {
        var matches;
        try { matches = selectPath(root, paths[i]); } catch (e) { continue; }
        var byParent = new Map();
        matches.forEach(function (match) {
            if (match.parent == null) return;
            if (!byParent.has(match.parent)) byParent.set(match.parent, []);
            byParent.get(match.parent).push(match.key);
        });
        byParent.forEach(function (keys, parent) {
            if (Array.isArray(parent)) {
                keys.map(Number).sort(function (a, b) { return b - a; }).forEach(function (index) {
                    if (index >= 0 && index < parent.length) { parent.splice(index, 1); removed = true; }
                });
            } else {
                keys.forEach(function (key) {
                    if (Object.prototype.hasOwnProperty.call(parent, key)) { delete parent[key]; removed = true; }
                });
            }
        });
    }
    return removed;
}

function parsePath(path) {
    var text = String(path).trim();
    if (text[0] !== '$') text = '$.' + text;
    var steps = [];
    var i = 1;
    while (i < text.length) {
        var deep = false;
        if (text.substr(i, 2) === '..') { deep = true; i += 2; }
        else if (text[i] === '.') i += 1;
        else if (text[i] !== '[') throw new Error('Bad path');
        if (text[i] === '[') {
            var end = closing(text, i);
            var inner = text.slice(i + 1, end).trim();
            i = end + 1;
            if (inner === '*') steps.push({ deep: deep, any: true });
            else if (inner.slice(0, 2) === '?(' && inner.slice(-1) === ')') steps.push({ deep: deep, filter: parseFilter(inner.slice(2, -1)) });
            else steps.push({ deep: deep, keys: splitList(inner).map(unquote) });
        } else {
            var start = i;
            while (i < text.length && text[i] !== '.' && text[i] !== '[') i++;
            var name = text.slice(start, i);
            if (!name) throw new Error('Bad path');
            steps.push(name === '*' ? { deep: deep, any: true } : { deep: deep, keys: [name] });
        }
    }
    return steps;
}

function closing(text, open) {
    var depth = 0;
    var quote = null;
    for (var i = open; i < text.length; i++) {
        var ch = text[i];
        if (quote) { if (ch === '\\') i++; else if (ch === quote) quote = null; continue; }
        if (ch === '"' || ch === "'") quote = ch;
        else if (ch === '[' || ch === '(') depth++;
        else if (ch === ']' || ch === ')') { depth--; if (depth === 0) return i; }
    }
    throw new Error('Unbalanced path');
}

function splitList(text) {
    var parts = [];
    var current = '';
    var quote = null;
    for (var i = 0; i < text.length; i++) {
        var ch = text[i];
        if (quote) { current += ch; if (ch === quote) quote = null; continue; }
        if (ch === '"' || ch === "'") { quote = ch; current += ch; continue; }
        if (ch === ',') { parts.push(current.trim()); current = ''; continue; }
        current += ch;
    }
    if (current.trim()) parts.push(current.trim());
    return parts;
}

function unquote(token) {
    var text = String(token).trim();
    var first = text[0];
    if ((first === '"' || first === "'") && text.slice(-1) === first) return text.slice(1, -1).replace(/\\(.)/g, '$1');
    return text;
}

function readToken(text, at) {
    while (text[at] === ' ') at++;
    var first = text[at];
    if (first === '"' || first === "'") {
        var end = at + 1;
        while (end < text.length && text[end] !== first) { if (text[end] === '\\') end++; end++; }
        return { value: text.slice(at + 1, end).replace(/\\(.)/g, '$1'), next: end + 1, quoted: true };
    }
    var stop = at;
    while (stop < text.length && text[stop] !== ' ') stop++;
    return { value: text.slice(at, stop), next: stop, quoted: false };
}

function parseFilter(expression) {
    var text = expression.trim();
    var operator = readToken(text, 0);
    var path = readToken(text, operator.next);
    var subPath = parsePath(path.value);
    if (operator.value === 'has') {
        return function (node) { return evaluate(node, subPath).length > 0; };
    }
    var operand = readToken(text, path.next);
    if (operator.value === 'key-eq') {
        var expected = operand.quoted ? operand.value : nativeJSON.parse(operand.value);
        return function (node) { return evaluate(node, subPath).some(function (m) { return m.value === expected; }); };
    }
    if (operator.value === 'key-substr') {
        return function (node) {
            return evaluate(node, subPath).some(function (m) { return typeof m.value === 'string' && m.value.indexOf(operand.value) >= 0; });
        };
    }
    throw new Error('Unsupported filter');
}

function children(value) {
    var list = [];
    if (value && typeof value === 'object') {
        if (Array.isArray(value)) for (var i = 0; i < value.length; i++) list.push({ value: value[i], parent: value, key: i });
        else for (var key in value) if (Object.prototype.hasOwnProperty.call(value, key)) list.push({ value: value[key], parent: value, key: key });
    }
    return list;
}

function evaluate(root, steps) {
    var nodes = [{ value: root, parent: null, key: null }];
    var budget = 200000;
    steps.forEach(function (step) {
        var next = [];
        function visit(node, deep) {
            if (--budget < 0) throw new Error('Path too expensive');
            children(node.value).forEach(function (child) {
                var hit = step.any || step.keys && step.keys.indexOf(String(child.key)) >= 0 || step.filter && step.filter(child.value);
                if (hit) next.push(child);
                if (deep) visit(child, true);
            });
        }
        nodes.forEach(function (node) { visit(node, step.deep); });
        nodes = next;
    });
    return nodes;
}

function selectPath(root, path) { return evaluate(root, parsePath(path)); }
