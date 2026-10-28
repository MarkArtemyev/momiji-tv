(() => {
  'use strict';
  if (!/^https?:$/.test(location.protocol) || location.hostname === 'appassets.androidplatform.net' || window.__momijiPage) return;
  const selector = 'a[href],button,input:not([type="hidden"]),textarea,select,[role="button"],[role="link"],[tabindex="0"],video,iframe';
  const id = Math.random().toString(36).slice(2);
  let enabled = false, pointerMode = false, selected = null, candidates = [], dirty = true, scanned = 0, lastMove = 0;
  let geometry = [], geometryAt = 0, layoutDirty = true;
  let mediaNodes = [], frameNodes = [], mediaDirty = true, mediaState = null, mediaAt = 0;
  const frames = new Map();
  const editable = e => e && (e.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName));
  const visible = e => {
    const r = e.getBoundingClientRect();
    return r.width > 3 && r.height > 3 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth &&
      !e.disabled && e.getAttribute('aria-hidden') !== 'true' && getComputedStyle(e).visibility !== 'hidden';
  };
  function style() {
    if (document.getElementById('momiji-focus-style') || !document.documentElement) return;
    const s = document.createElement('style'); s.id = 'momiji-focus-style';
    s.textContent = '[data-momiji-focus]{outline:3px solid #ffb347!important;outline-offset:3px!important;box-shadow:0 0 0 5px #1b102099!important}' +
      'html[data-momiji-input="pointer"] :focus-visible:not(input):not(textarea):not(select):not([contenteditable]){outline:none!important}';
    document.documentElement.appendChild(s);
  }
  function setPointer(pointer) {
    if (pointerMode !== pointer) lastMove = 0;
    pointerMode = pointer;
    if (document.documentElement) document.documentElement.dataset.momijiInput = pointer ? 'pointer' : 'keys';
    if (pointer && selected) { selected.removeAttribute('data-momiji-focus'); selected = null; }
  }
  function choose(e) {
    if (selected && selected !== e) selected.removeAttribute('data-momiji-focus');
    selected = e; style(); e.setAttribute('data-momiji-focus', '');
    e.focus({ preventScroll: true });
    e.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
  }
  function move(dir) {
    const now = performance.now();
    if (now - lastMove < 85) return true;
    lastMove = now;
    // Cache the candidate list; measure rectangles only when a key is pressed.
    if ((dirty && now - scanned > 250) || !candidates.length || now - scanned > 2000) {
      candidates = Array.from(document.querySelectorAll(selector)).slice(0, 2500);
      dirty = false; scanned = now;
      layoutDirty = true;
    }
    if (layoutDirty || now - geometryAt > 250) {
      geometry = [];
      for (const e of candidates) {
        const r = e.getBoundingClientRect();
        if (r.width > 3 && r.height > 3 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth &&
          !e.disabled && e.getAttribute('aria-hidden') !== 'true' && getComputedStyle(e).visibility !== 'hidden') geometry.push({ e, r });
      }
      layoutDirty = false; geometryAt = now;
    }
    const list = geometry.map(item => item.e);
    const active = document.activeElement;
    if (editable(active)) return false;
    const current = list.includes(active) ? active : list.includes(selected) ? selected : null;
    if (!current) { if (list[0]) choose(list[0]); return !!list.length; }
    const a = geometry.find(item => item.e === current).r, ax = (a.left + a.right) / 2, ay = (a.top + a.bottom) / 2;
    let best = null, score = Infinity;
    for (const { e, r: b } of geometry) {
      if (e === current || current.contains(e) || e.contains(current)) continue;
      const dx = (b.left + b.right) / 2 - ax, dy = (b.top + b.bottom) / 2 - ay;
      const main = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      if (main <= 2) continue;
      const cross = dir === 'left' || dir === 'right' ? Math.max(0, b.top - a.bottom, a.top - b.bottom) : Math.max(0, b.left - a.right, a.left - b.right);
      const off = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      const s = main + cross * 4 + off * .12;
      if (s < score) { best = e; score = s; }
    }
    if (best) choose(best);
    else window.scrollBy({ left: dir === 'left' ? -innerWidth * .7 : dir === 'right' ? innerWidth * .7 : 0,
      top: dir === 'up' ? -innerHeight * .65 : dir === 'down' ? innerHeight * .65 : 0, behavior: 'instant' });
    return true;
  }
  function refreshMedia() {
    if (!mediaDirty) return;
    mediaNodes = Array.from(document.querySelectorAll('video')).slice(0, 64);
    frameNodes = Array.from(document.querySelectorAll('iframe')).slice(0, 32);
    mediaDirty = false;
  }
  function video() {
    refreshMedia();
    let best = null, score = -1;
    for (const v of mediaNodes) {
      if (!visible(v)) continue;
      const r = v.getBoundingClientRect(), next = (v.paused ? 0 : 1e8) + r.width * r.height;
      if (next > score) { best = v; score = next; }
    }
    return best;
  }
  function localMedia(action) {
    if (action === 'pause_all') {
      document.querySelectorAll('video,audio').forEach(v => v.pause());
      broadcast({ type: 'pause_all' });
      return true;
    }
    const v = video(); if (!v) return false;
    try {
      if (action === 'play_pause') { if (v.paused) v.play().catch(() => {}); else v.pause(); }
      else if (action === 'play') v.play().catch(() => {});
      else if (action === 'pause') v.pause();
      else if (action === 'seek_back' || action === 'seek_forward') {
        if (!v.seekable.length) return false;
        const start = v.seekable.start(0), end = v.seekable.end(v.seekable.length - 1);
        v.currentTime = Math.max(start, Math.min(end, v.currentTime + (action === 'seek_forward' ? 10 : -10)));
      } else return false;
      return true;
    } catch (_) { return false; }
  }
  function report() {
    const now = performance.now();
    if (mediaState && !mediaDirty && now - mediaAt < 500) return mediaState;
    refreshMedia();
    let playing = false, score = 0; const sources = [];
    for (const v of mediaNodes) {
      if (!visible(v)) continue;
      const r = v.getBoundingClientRect();
      playing = playing || (!v.paused && !v.ended && v.readyState >= 2);
      score = Math.max(score, (v.paused ? 0 : 1e8) + r.width * r.height);
      const src = v.currentSrc || v.src;
      if (sources.length < 8 && /^https?:\/\//i.test(src)) sources.push(src);
    }
    mediaAt = now;
    return mediaState = { id, playing, score, sources };
  }
  function broadcast(data) {
    refreshMedia();
    frameNodes.forEach(f => {
      if (visible(f)) { try { f.contentWindow.postMessage({ __momiji: true, ...data }, '*'); } catch (_) {} }
    });
  }
  function state() {
    broadcast({ type: 'poll' });
    const own = report(), now = Date.now();
    let playing = own.playing, sources = own.sources.slice();
    for (const [key, f] of frames) {
      if (now - f.at > 4500) { frames.delete(key); continue; }
      playing = playing || f.playing; sources.push(...f.sources);
    }
    return { playing, hasMedia: mediaNodes.length > 0 || frameNodes.length > 0, sources: [...new Set(sources)].slice(0, 12) };
  }
  function media(action) {
    const own = report(), now = Date.now();
    let best = null;
    for (const f of frames.values()) if (now - f.at < 4500 && f.score > (best ? best.score : own.score)) best = f;
    if (best) { best.source.postMessage({ __momiji: true, type: 'media', action }, '*'); return true; }
    return localMedia(action);
  }
  function configure(config) {
    enabled = !!config.focus;
    if (typeof config.pointer === 'boolean') setPointer(config.pointer);
    if (!enabled && selected) { selected.removeAttribute('data-momiji-focus'); selected = null; }
    broadcast({ type: 'config', config });
  }
  function type(text) {
    text = String(text).slice(0, 4096);
    let field = document.activeElement;
    while (field && field.shadowRoot && field.shadowRoot.activeElement) field = field.shadowRoot.activeElement;
    if (field && field.tagName === 'IFRAME') {
      const known = Array.from(frames.values()).some(f => f.source === field.contentWindow && Date.now() - f.at < 4500);
      if (known) field.contentWindow.postMessage({ __momiji: true, type: 'text', text }, '*');
      return known;
    }
    if (!editable(field) || field.tagName === 'SELECT' || field.disabled || field.readOnly) return false;
    if (field.isContentEditable) return document.execCommand('insertText', false, text);
    const value = field.value, start = field.selectionStart === null ? value.length : field.selectionStart;
    const end = field.selectionEnd === null ? start : field.selectionEnd;
    const prototype = field.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value').set.call(field, value.slice(0, start) + text + value.slice(end));
    try { field.setSelectionRange(start + text.length, start + text.length); } catch (_) {}
    field.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }));
    return true;
  }
  addEventListener('message', e => {
    const d = e.data; if (!d || d.__momiji !== true) return;
    if (d.type === 'report' && e.source !== window && typeof d.id === 'string') {
      if (frames.size < 32 || frames.has(d.id)) frames.set(d.id, { source: e.source, at: Date.now(), playing: d.playing === true,
        score: Math.max(0, Math.min(2e8, Number(d.score) || 0)), sources: Array.isArray(d.sources) ? d.sources.filter(s => typeof s === 'string' && s.length < 8192 && /^https?:\/\//i.test(s)).slice(0, 8) : [] });
    } else if (e.source === parent && window !== top) {
      if (d.type === 'poll') {
        const message = { __momiji: true, type: 'report', ...report() };
        top.postMessage(message, '*'); if (parent !== top) parent.postMessage(message, '*'); broadcast(d);
      }
      else if (d.type === 'config') configure(d.config || {});
      else if (d.type === 'pause_all') localMedia('pause_all');
      else if (d.type === 'text' && typeof d.text === 'string') type(d.text);
    }
    // Only the top frame routes playback commands to the selected video frame.
    if (d.type === 'media' && e.source === top && window !== top) localMedia(d.action);
  });
  addEventListener('keydown', e => {
    if (!enabled || editable(e.target) || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key.startsWith('Arrow') || e.key === 'Enter' || e.key === ' ' || e.key === 'Tab') setPointer(false);
    const dirs = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
    if (dirs[e.key] && move(dirs[e.key])) { e.preventDefault(); e.stopImmediatePropagation(); }
    else if ((e.key === 'Enter' || e.key === ' ') && selected && document.activeElement === selected) {
      e.preventDefault(); e.stopImmediatePropagation();
    }
  }, true);
  addEventListener('keyup', e => {
    if (!enabled || editable(e.target) || !selected || document.activeElement !== selected) return;
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault(); e.stopImmediatePropagation();
      if (selected.tagName === 'VIDEO') localMedia('play_pause');
      else selected.click();
    }
  }, true);
  const observe = () => {
    if (!document.documentElement) return;
    dirty = true; layoutDirty = true; mediaDirty = true; mediaState = null;
    style(); setPointer(pointerMode);
    new MutationObserver(() => { dirty = true; layoutDirty = true; mediaDirty = true; mediaState = null; })
      .observe(document.documentElement, { childList: true, subtree: true, attributes: true,
        attributeFilter: ['style', 'class', 'hidden', 'disabled', 'aria-hidden', 'tabindex', 'role', 'href', 'type', 'src'] });
  };
  if (document.documentElement) observe(); else addEventListener('DOMContentLoaded', observe, { once: true });
  const invalidateLayout = () => { layoutDirty = true; mediaState = null; };
  addEventListener('scroll', invalidateLayout, { capture: true, passive: true });
  addEventListener('resize', invalidateLayout, { passive: true });
  addEventListener('pointermove', () => setPointer(true), { passive: true });
  addEventListener('pointerdown', () => setPointer(true), { passive: true });
  for (const event of ['play', 'pause', 'ended', 'loadedmetadata', 'loadeddata', 'emptied']) {
    addEventListener(event, () => { mediaState = null; }, true);
  }
  window.__momijiPage = { move, configure, state, media, type, pauseAll: () => localMedia('pause_all') };
})();
