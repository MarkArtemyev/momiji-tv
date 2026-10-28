// Shield checks in real Chromium. The Kotlin bridge is replaced by a mock with the same methods, so
// this exercises the exact script the browser installs: AdGuard Scriptlets + ExtendedCss + shield.js.
//
//   node tests/web-shield/run.js            (needs the `playwright` package and its Chromium)
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const assets = path.join(__dirname, '../../momiji-browser/app/src/main/assets/protection');
const read = (name) => fs.readFileSync(path.join(assets, name), 'utf8');
// Same assembly as ShieldScript.kt.
const shield = '(function(){\n' + read('vendor/scriptlets.js') + '\n' + read('vendor/extended-css.js') + '\n' + read('shield.js') + '\n})();';
const redirects = JSON.parse(read('vendor/redirects.json'));
const redirect = (name) => redirects.find((r) => r.title === name || r.aliases.includes(name));
// Same decoding as RedirectResources.kt: `;base64` content types carry base64 bodies.
const resource = (name) => { const r = redirect(name); const base64 = /;base64$/.test(r.contentType);
    return { type: r.contentType.replace(/;base64$/, ''), body: base64 ? Buffer.from(r.content, 'base64') : Buffer.from(r.content, 'utf8') }; };

// Payloads the Kotlin core built from the bundled lists (written by the JVM suite), when available.
const realPayloads = (() => { try { return JSON.parse(fs.readFileSync(path.join(__dirname, '../network-core/build/payloads.json'), 'utf8')); } catch (e) { return {}; } })();
const payloads = {
    'site.test': {
        v: 1, f: 0, g: 1, jp: 1, p: 1,
        css: '.ad-banner{display:none!important}\n',
        ext: ['div.card:has-text(Sponsored) { display: none !important; }', '.promo-inner:upward(1) { display: none !important; }',
            '.overlay { remove: true; }'],
        s: [['set-constant', 'adsEnabled', 'false'], ['abort-on-property-read', 'popunderLib'], ['ubo-aopr.js', 'adBlockDetected'],
            ['json-prune', 'playerResponse.adPlacements'], ['prevent-window-open', 'casino']],
        js: [],
    },
    'strict.test': { v: 1, f: 0, g: 0, jp: 0, p: 0, css: '.ad-banner{display:none!important}\n', ext: [], s: [['set-constant', 'adsEnabled', 'false']], js: [] },
    'frame.test': { v: 1, f: 0, g: 0, jp: 0, p: 0, css: '.frame-ad{display:none!important}\n', ext: [], s: [], js: [] },
    // youtube.com is HSTS-preloaded, so its real payload is served under a plain-HTTP test host.
    ...(realPayloads['www.youtube.com'] ? { 'yt.test': realPayloads['www.youtube.com'] } : {}),
    'allowed.test': { v: 1, f: 255, g: 1, jp: 1, p: 1, css: '.ad-banner{display:none!important}\n', ext: [], s: [['set-constant', 'adsEnabled', 'false']], js: [] },
};
const prunePaths = {
    '/youtubei/v1/player': ['$..[adPlacements, adSlots, playerAds]'],
    '/api/feed': ['$.items[?(has "$.promoted")]', '$..[?(key-eq "$.type" "ad")]', '$.adBreak.*', '$.meta.tracking'],
    '/api/xhr-json': ['$..adSlots'],
};

const pages = {
    '/basic': `<!doctype html><html><head><title>t</title></head><body>
        <div class="ad-banner" id="static-ad">ad</div><div class="card" id="sponsored">Sponsored post</div><div class="card" id="normal">Normal post</div>
        <div id="promo-outer"><span class="promo-inner">x</span></div><div class="overlay" id="overlay">overlay</div>
        <iframe id="frame" src="http://frame.test:PORT/frame"></iframe>
        <script>
        window.adsEnabled = true;
        window.results = { adsEnabled: window.adsEnabled };
        try { popunderLib.run(); window.results.popunder = 'ran'; } catch (e) { window.results.popunder = 'aborted'; }
        try { if (adBlockDetected) {} window.results.detector = 'ran'; } catch (e) { window.results.detector = 'aborted'; }
        window.results.scriptedPopup = window.open('http://other.test:1/') === null;
        window.results.json = JSON.parse('{"playerResponse":{"adPlacements":[1],"videoDetails":{"id":"v"}}}');
        setTimeout(() => { const d = document.createElement('div'); d.className = 'lazy-ad'; d.id = 'lazy'; d.textContent = 'lazy'; document.body.appendChild(d); }, 300);
        </script></body></html>`,
    '/watch': `<!doctype html><html><body><div id="player"></div><script>
        var ytInitialPlayerResponse = {"adPlacements":[{"adPlacementRenderer":{}}],"playerAds":[{"x":1}],"adSlots":[1],"videoDetails":{"videoId":"abc"}};
        window.results = { ads: ytInitialPlayerResponse.adPlacements, slots: ytInitialPlayerResponse.adSlots, playerAds: ytInitialPlayerResponse.playerAds,
            video: ytInitialPlayerResponse.videoDetails.videoId };
        </script></body></html>`,
    '/frame': `<!doctype html><html><body><div class="frame-ad" id="fad">frame ad</div></body></html>`,
    '/strict': `<!doctype html><html><head></head><body><div class="ad-banner" id="static-ad">ad</div>
        <script nonce="n1" src="/strict.js"></script></body></html>`,
    '/strict.js': `window.adsEnabled = true; window.results = { adsEnabled: window.adsEnabled };`,
    '/allowed': `<!doctype html><html><body><div class="ad-banner" id="static-ad">ad</div><script>window.adsEnabled = true; window.results = { adsEnabled: window.adsEnabled };</script></body></html>`,
    '/vod.m3u8': '#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\ncontent1.ts\n#EXTINF:4,\nhttps://ads.test/ad1.ts\n#EXTINF:4,\ncontent2.ts\n#EXT-X-ENDLIST\n',
    '/youtubei/v1/player': '{"playerResponse":{"videoDetails":{"id":"v"}},"adPlacements":[1],"playerAds":[2],"nested":{"adSlots":[3],"keep":true}}',
    '/api/feed': '{"items":[{"id":1},{"id":2,"promoted":true},{"id":3}],"blocks":[{"type":"ad"},{"type":"post"}],"adBreak":{"a":1,"b":2},"meta":{"tracking":"x","page":1}}',
    '/api/xhr-json': '{"list":[{"adSlots":[1],"id":1}]}',
    '/ima': `<!doctype html><html><body><div id="ad"></div><video id="video"></video><script src="/ima3.js"></script><script>
        window.imaResult = 'pending';
        const container = new google.ima.AdDisplayContainer(document.getElementById('ad'), document.getElementById('video'));
        container.initialize();
        const loader = new google.ima.AdsLoader(container);
        loader.addEventListener(google.ima.AdsManagerLoadedEvent.Type.ADS_MANAGER_LOADED, () => { window.imaResult = 'loaded'; }, false);
        loader.addEventListener(google.ima.AdErrorEvent.Type.AD_ERROR, () => { window.imaResult = 'error'; }, false);
        const request = new google.ima.AdsRequest(); request.adTagUrl = 'https://ads.test/vast.xml';
        loader.requestAds(request);
        </script></body></html>`,
};

function mockBridge(port) {
    // Installed before the shield in every frame, standing in for ShieldBridge.kt.
    return `(() => {
        const payloads = ${JSON.stringify(payloads)};
        const prunePaths = ${JSON.stringify(prunePaths)};
        const notes = [];
        Object.defineProperty(window, '__momijiNotes', { value: notes });
        window.MomijiShield = {
            page(url) { const p = payloads[new URL(url).hostname]; return p ? JSON.stringify(p) : ''; },
            cosmetic(tokens) { return tokens.split(' ').includes('.lazy-ad') ? '.lazy-ad{display:none!important}\\n' : ''; },
            response(url) { const p = prunePaths[new URL(url).pathname]; return p ? JSON.stringify(p) : ''; },
            hls(text) { return text.split('\\n').filter((l, i, a) => !l.includes('ads.test') && !(a[i + 1] || '').includes('ads.test')).join('\\n'); },
            note(url, method, kind) { notes.push([url, method, kind]); },
            scripts() { return ''; },
        };
    })();`;
}

async function main() {
    const server = http.createServer((req, res) => {
        const url = new URL(req.url, 'http://x');
        if (url.pathname === '/ima3.js') { const ima = resource('google-ima3'); res.writeHead(200, { 'content-type': ima.type }); res.end(ima.body); return; }
        if (url.pathname === '/beacon') { res.writeHead(204); res.end(); return; }
        const body = pages[url.pathname];
        if (body == null) { res.writeHead(404); res.end(); return; }
        const headers = { 'content-type': url.pathname.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' :
            url.pathname.startsWith('/api') || url.pathname.startsWith('/youtubei') ? 'application/json' :
            url.pathname.endsWith('.js') ? 'text/javascript' : 'text/html' };
        if (url.pathname.startsWith('/strict')) headers['content-security-policy'] = "default-src 'self'; script-src 'self' 'nonce-n1'; style-src 'self'";
        res.writeHead(200, headers);
        res.end(body.replace(/PORT/g, String(server.address().port)));
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port;
    const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP *.test 127.0.0.1'] });
    const context = await browser.newContext();
    await context.addInitScript(mockBridge(port));
    await context.addInitScript(shield);
    const results = [];
    async function test(name, action) {
        try { await action(); results.push(name); console.log('PASS ' + name); }
        catch (error) { console.log('FAIL ' + name + ': ' + error.message); process.exitCode = 1; }
    }
    function check(condition, message) { if (!condition) throw new Error(message || 'check failed'); }
    const base = (host) => `http://${host}:${port}`;
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));

    await test('scriptlets_run_before_page_scripts', async () => {
        await page.goto(base('site.test') + '/basic');
        const r = await page.evaluate(() => window.results);
        check(r.adsEnabled === false, 'set-constant ' + JSON.stringify(r));
        check(r.popunder === 'aborted' && r.detector === 'aborted', 'abort-on-property-read ' + JSON.stringify(r));
        check(!('adPlacements' in r.json.playerResponse) && r.json.playerResponse.videoDetails.id === 'v', 'json-prune ' + JSON.stringify(r.json));
    });

    await test('element_hiding_extended_css_and_generic_tokens', async () => {
        const hidden = (selector) => page.evaluate((s) => { const e = document.querySelector(s); return !e || getComputedStyle(e).display === 'none'; }, selector);
        check(await hidden('#static-ad'), 'site css');
        await page.waitForFunction(() => getComputedStyle(document.getElementById('sponsored')).display === 'none', null, { timeout: 3000 });
        check(!(await hidden('#normal')), 'normal card hidden');
        check(await hidden('#promo-outer'), 'upward');
        check(await page.evaluate(() => !document.getElementById('overlay')), 'remove');
        await page.waitForFunction(() => { const e = document.getElementById('lazy'); return e && getComputedStyle(e).display === 'none'; }, null, { timeout: 3000 });
        check(await page.evaluate(() => document.querySelectorAll('style').length === 0), 'styles stay out of the DOM');
        const frame = page.frames().find((f) => f.url().includes('frame.test'));
        check(frame && await frame.evaluate(() => getComputedStyle(document.getElementById('fad')).display === 'none'), 'iframe payload');
    });

    await test('hls_playlists_pruned_for_fetch_and_xhr_players', async () => {
        const r = await page.evaluate(async () => {
            const res = await fetch('/vod.m3u8');
            const viaFetch = await res.text();
            const viaXhr = await new Promise((resolve) => {
                const xhr = new XMLHttpRequest(); xhr.open('GET', '/vod.m3u8'); xhr.responseType = 'text';
                xhr.onload = () => resolve({ response: xhr.response, text: xhr.responseText, url: xhr.responseURL }); xhr.send();
            });
            return { viaFetch, fetchUrl: res.url, viaXhr };
        });
        check(!r.viaFetch.includes('ads.test') && r.viaFetch.includes('content2.ts'), 'fetch ' + r.viaFetch);
        check(r.fetchUrl.endsWith('/vod.m3u8'), 'forged response keeps url for relative segments');
        check(!r.viaXhr.response.includes('ads.test') && r.viaXhr.response === r.viaXhr.text, 'xhr ' + JSON.stringify(r.viaXhr));
    });

    await test('jsonprune_rules_on_fetch_and_xhr', async () => {
        const r = await page.evaluate(async () => {
            const player = await (await fetch('/youtubei/v1/player', { method: 'POST', body: '{}' })).json();
            const feed = await (await fetch('/api/feed')).json();
            const xhrJson = await new Promise((resolve) => {
                const xhr = new XMLHttpRequest(); xhr.open('GET', '/api/xhr-json'); xhr.responseType = 'json';
                xhr.onload = () => resolve(xhr.response); xhr.send();
            });
            return { player, feed, xhrJson };
        });
        check(!('adPlacements' in r.player) && !('playerAds' in r.player) && !('adSlots' in r.player.nested) && r.player.nested.keep, JSON.stringify(r.player));
        check(r.feed.items.map((i) => i.id).join() === '1,3', 'filter has ' + JSON.stringify(r.feed));
        check(r.feed.blocks.length === 1 && r.feed.blocks[0].type === 'post', 'filter key-eq ' + JSON.stringify(r.feed));
        check(Object.keys(r.feed.adBreak).length === 0 && !('tracking' in r.feed.meta) && r.feed.meta.page === 1, 'wildcard/path ' + JSON.stringify(r.feed));
        check(!('adSlots' in r.xhrJson.list[0]) && r.xhrJson.list[0].id === 1, 'xhr json ' + JSON.stringify(r.xhrJson));
    });

    await test('request_type_hints_and_native_looking_hooks', async () => {
        const r = await page.evaluate(async () => {
            await fetch('/api/feed');
            navigator.sendBeacon('/beacon', 'x');
            return {
                notes: window.__momijiNotes.map((n) => n[2]),
                fetchNative: Function.prototype.toString.call(window.fetch).includes('[native code]'),
                openNative: Function.prototype.toString.call(XMLHttpRequest.prototype.open).includes('[native code]'),
                getterNative: Function.prototype.toString.call(Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText').get).includes('[native code]'),
            };
        });
        check(r.notes.includes('fetch') && r.notes.includes('xhr') && r.notes.includes('beacon'), JSON.stringify(r.notes));
        check(r.fetchNative && r.openNative && r.getterNative, JSON.stringify(r));
    });

    await test('popups_need_a_gesture_and_casino_is_refused', async () => {
        let popups = 0;
        page.on('popup', () => { popups++; });
        const scripted = await page.evaluate(() => window.results.scriptedPopup);
        await page.evaluate(() => { document.body.insertAdjacentHTML('beforeend', '<button id="b1">open</button><button id="b2">casino</button>');
            document.getElementById('b1').onclick = () => { window.open('about:blank'); };
            document.getElementById('b2').onclick = () => { window.__casino = window.open('https://casino.test/'); }; });
        await page.click('#b2');
        await page.click('#b1');
        await page.waitForTimeout(500);
        check(scripted, 'scripted popup allowed');
        check(popups === 1, 'popups=' + popups);
    });

    await test('strict_csp_page_still_protected', async () => {
        const strict = await context.newPage();
        const violations = [];
        strict.on('console', (m) => { if (/Content Security Policy/i.test(m.text())) violations.push(m.text()); });
        await strict.goto(base('strict.test') + '/strict');
        const r = await strict.evaluate(() => ({ results: window.results, hidden: getComputedStyle(document.getElementById('static-ad')).display }));
        check(r.results.adsEnabled === false && r.hidden === 'none', JSON.stringify(r));
        check(violations.length === 0, violations.join('\n'));
        await strict.close();
    });

    await test('allowlisted_site_is_left_alone', async () => {
        const allowed = await context.newPage();
        await allowed.goto(base('allowed.test') + '/allowed');
        const r = await allowed.evaluate(() => ({ ads: window.results.adsEnabled, display: getComputedStyle(document.getElementById('static-ad')).display }));
        check(r.ads === true && r.display !== 'none', JSON.stringify(r));
        await allowed.close();
    });

    await test('vendored_ima_surrogate_lets_players_continue', async () => {
        const ima = await context.newPage();
        await ima.goto(base('strict.test').replace('strict', 'player') + '/ima');
        await ima.waitForFunction(() => window.imaResult !== 'pending', null, { timeout: 5000 });
        check(await ima.evaluate(() => window.imaResult) === 'loaded' || await ima.evaluate(() => window.imaResult) === 'error', 'ima event');
        const vast = resource('noopvast-4.0').body.toString('utf8');
        const parsed = await ima.evaluate((xml) => new DOMParser().parseFromString(xml, 'text/xml').documentElement.nodeName, vast);
        check(parsed === 'VAST', 'noopvast root ' + parsed);
        await ima.close();
    });

    await test('real_youtube_payload_strips_player_ads', async () => {
        if (!realPayloads['www.youtube.com']) throw new Error('run the JVM suite first: tests/network-core/build/payloads.json is missing');
        const yt = await context.newPage();
        const ytErrors = [];
        yt.on('pageerror', (e) => ytErrors.push(e.message));
        await yt.goto(base('yt.test') + '/watch');
        const r = await yt.evaluate(() => window.results);
        check(r.ads === undefined && r.slots === undefined && r.playerAds === undefined && r.video === 'abc', JSON.stringify(r));
        check(realPayloads['www.youtube.com'].s.length > 10, 'payload scriptlets ' + realPayloads['www.youtube.com'].s.length);
        await yt.waitForTimeout(500);
        check(ytErrors.length === 0, ytErrors.join('\n'));
        await yt.close();
    });

    await test('bundled_scriptlets_resolve_and_raw_rules_parse', async () => {
        // The compiled list cache is what the app serves; every scriptlet it names must exist in the vendored
        // library, and every raw JS rule must parse, or ShieldScript.raw() would lose the whole raw script.
        const zlib = require('zlib');
        const compiled = zlib.gunzipSync(fs.readFileSync(path.join(assets, 'cosmetic.txt.gz'))).toString('utf8').split('\n');
        const names = new Set(Object.keys(JSON.parse('{' + read('vendor/scriptlets.js').match(/var SCRIPTLET_ALIASES=(\{.*\});/)[1].slice(1, -1) + '}')));
        const library = new Function(read('vendor/scriptlets.js') + '; return SCRIPTLETS;')();
        const unknown = new Set();
        const broken = [];
        for (const line of compiled) {
            const [kind, , raw] = line.split('\t');
            if (raw == null) continue;
            const body = raw.replace(/\\(.)/g, (m, c) => (c === 't' ? '\t' : c)); // CosmeticRules.unescape
            if (kind === 'S') { const name = JSON.parse(body)[0]; if (!library[name] && !names.has(name)) unknown.add(name); }
            if (kind === 'J') { try { new Function(body); } catch (e) { broken.push(body.slice(0, 80)); } }
        }
        check(unknown.size === 0, 'unknown scriptlets ' + [...unknown].join());
        check(broken.length === 0, broken.length + ' raw rules do not parse: ' + broken.slice(0, 3).join(' | '));
    });

    await test('no_page_errors_from_the_shield', async () => {
        check(errors.length === 0, errors.join('\n'));
        check(shield.length < 900 * 1024, 'shield size ' + shield.length);
    });

    await browser.close();
    server.close();
    console.log(`WEB_SHIELD_PASSED=${results.length}`);
}

main().catch((error) => { console.error(error); process.exit(1); });
