(() => {
  const $ = (s) => document.querySelector(s);

  const DAYS = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];
  const MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  const LEAF_COLORS = ['#e2513a', '#ff7a3d', '#ffb347', '#c73a3a', '#f08a3c', '#d94a2b', '#ffcf6b'];
  const DIRS = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };

  let data = null;
  let current = null;
  let modal = null;
  let enterHeld = false;
  let longDone = false;
  let refocusIndex = -1;
  let signature = '';
  let pointerMode = false;

  const host = (u) => {
    try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return u; }
  };
  const favicon = (u) => 'https://www.google.com/s2/favicons?sz=64&domain=' + encodeURIComponent(host(u));
  const hue = (s) => {
    let h = 0;
    for (const c of s) h = (h * 31 + c.charCodeAt(0)) % 360;
    return h;
  };
  const cmd = (name, params) => {
    location.href = 'momiji://' + name + (params ? '?' + new URLSearchParams(params) : '');
  };
  const afterLoad = (fn) => {
    if (document.readyState === 'complete') fn();
    else addEventListener('load', fn, { once: true });
  };

  function clock() {
    const d = new Date();
    const h = d.getHours();
    $('#time').textContent = String(h).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    $('#date').textContent = DAYS[d.getDay()] + ', ' + d.getDate() + ' ' + MONTHS[d.getMonth()];
    const [ru, jp] = h < 5 ? ['Доброй ночи', '秋の夜']
      : h < 12 ? ['Доброе утро', '秋の朝']
      : h < 18 ? ['Добрый день', '秋の昼']
      : ['Добрый вечер', '秋の夕暮れ'];
    $('#greet .ru').textContent = ru;
    $('#greet .jp').textContent = jp;
  }

  function leaves() {
    const box = $('#leaves');
    for (let i = 0; i < 14; i++) {
      const el = document.createElement('div');
      el.className = 'leaf';
      const size = 16 + Math.random() * 22;
      const fall = 10 + Math.random() * 10;
      el.style.left = (Math.random() * 100).toFixed(1) + '%';
      el.style.width = el.style.height = size.toFixed(0) + 'px';
      el.style.animationDuration = fall.toFixed(1) + 's';
      el.style.animationDelay = (-Math.random() * fall).toFixed(1) + 's';
      el.style.opacity = (0.7 + Math.random() * 0.3).toFixed(2);
      el.style.setProperty('--drift', (Math.random() * 160 - 80).toFixed(0) + 'px');
      el.innerHTML = '<svg viewBox="0 0 100 100"><use href="#leaf" fill="' + LEAF_COLORS[i % LEAF_COLORS.length] + '"/></svg>';
      el.firstChild.style.animationDuration = (3 + Math.random() * 4).toFixed(1) + 's';
      box.appendChild(el);
    }
  }

  const rnd = (min, max) => min + Math.random() * (max - min);
  const still = () => {
    const c = document.documentElement.classList;
    return document.hidden || c.contains('reduce-motion') || c.contains('page-hidden');
  };
  const once = (el) => el.addEventListener('animationend', (e) => { if (e.target === el) el.remove(); });

  function every(min, max, fn) {
    setTimeout(() => {
      if (!still()) fn();
      every(min, max, fn);
    }, rnd(min, max) * 1000);
  }

  function sparks() {
    const box = $('#sparks');
    for (let i = 0; i < 18; i++) {
      const el = document.createElement('div');
      el.className = 'spark';
      const size = rnd(5, 14);
      const time = rnd(9, 17);
      el.style.left = (i % 3 ? rnd(45, 98) : rnd(4, 60)).toFixed(1) + '%';
      el.style.top = rnd(48, 100).toFixed(1) + '%';
      el.style.width = el.style.height = size.toFixed(0) + 'px';
      el.style.animationDuration = time.toFixed(1) + 's';
      el.style.animationDelay = (-rnd(0, time)).toFixed(1) + 's';
      el.style.setProperty('--sway', rnd(-40, 40).toFixed(0) + 'px');
      el.innerHTML = '<i></i>';
      el.firstChild.style.animationDuration = rnd(1.2, 2.8).toFixed(1) + 's';
      box.appendChild(el);
    }
  }

  function gust() {
    const box = $('#leaves');
    const count = 5 + Math.floor(Math.random() * 4);
    for (let i = 0; i < count; i++) {
      const el = document.createElement('div');
      el.className = 'gust';
      el.style.left = rnd(70, 98).toFixed(1) + '%';
      el.style.top = rnd(0, 32).toFixed(1) + '%';
      el.style.width = el.style.height = rnd(14, 34).toFixed(0) + 'px';
      el.style.animationDuration = rnd(5, 8).toFixed(1) + 's';
      el.style.animationDelay = (i * 0.18 + rnd(0, 0.3)).toFixed(2) + 's';
      el.style.setProperty('--dx', rnd(-105, -55).toFixed(0) + 'vw');
      el.style.setProperty('--dy', rnd(30, 75).toFixed(0) + 'vh');
      el.innerHTML = '<svg viewBox="0 0 100 100"><use href="#leaf" fill="' + LEAF_COLORS[Math.floor(Math.random() * LEAF_COLORS.length)] + '"/></svg>';
      el.firstChild.style.animationDuration = rnd(1.1, 2.2).toFixed(1) + 's';
      once(el);
      box.appendChild(el);
    }
  }

  function birds() {
    const box = $('#birds');
    const count = 3 + Math.floor(Math.random() * 3);
    const top = rnd(10, 30);
    const time = rnd(24, 32);
    const rise = rnd(-12, 4);
    for (let i = 0; i < count; i++) {
      const el = document.createElement('div');
      el.className = 'bird';
      const scale = i ? rnd(0.65, 0.9) : 1;
      el.style.top = (top + (i ? rnd(-4, 5) : 0)).toFixed(1) + '%';
      el.style.width = (22 * scale).toFixed(0) + 'px';
      el.style.height = (10 * scale).toFixed(0) + 'px';
      el.style.animationDuration = time.toFixed(1) + 's';
      el.style.animationDelay = (i * rnd(0.5, 1.1)).toFixed(2) + 's';
      el.style.setProperty('--bob', rnd(-3, 3).toFixed(1) + 'vh');
      el.style.setProperty('--rise', (rise + rnd(-2, 2)).toFixed(1) + 'vh');
      el.innerHTML = '<svg viewBox="0 0 40 16"><path d="M0 9Q9 1 20 9Q31 1 40 9Q31 5 20 13Q9 5 0 9Z"/></svg>';
      el.firstChild.style.animationDuration = rnd(0.38, 0.55).toFixed(2) + 's';
      once(el);
      box.appendChild(el);
    }
  }

  function icon(img, url, onOk) {
    img.alt = '';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.referrerPolicy = 'no-referrer';
    img.onload = () => (img.naturalWidth > 16 ? onOk() : img.remove());
    img.onerror = () => img.remove();
    afterLoad(() => { img.src = favicon(url); });
  }

  function tile(t) {
    const b = document.createElement('button');
    b.className = 'nav tile';
    b.dataset.key = 't:' + t.url;
    const label = t.title || host(t.url);
    const ico = document.createElement('div');
    ico.className = 'ico';
    ico.style.setProperty('--h', hue(host(t.url)));
    ico.textContent = label.trim().charAt(0).toUpperCase();
    const img = new Image();
    icon(img, t.url, () => ico.classList.add('has-img'));
    ico.appendChild(img);
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = label;
    b.append(ico, name);
    b._go = () => { location.href = t.url; };
    b._long = () => ask(t, b);
    return b;
  }

  function chip(t) {
    const b = document.createElement('button');
    b.className = 'nav chip';
    b.dataset.key = 'r:' + t.url;
    const img = new Image();
    icon(img, t.url, () => {});
    const s = document.createElement('span');
    s.textContent = t.title || host(t.url);
    b.append(img, s);
    b._go = () => { location.href = t.url; };
    return b;
  }

  function render(d) {
    const nextSignature = JSON.stringify(d);
    if (signature === nextSignature) return;
    signature = nextSignature;
    document.documentElement.classList.toggle('reduce-motion', !!d.reducedMotion);
    data = d;
    const keep = current && current.dataset.key;
    const grid = $('#tiles');
    grid.replaceChildren(...d.tiles.map(tile));
    const recent = d.recent || [];
    $('#recent').replaceChildren(...recent.map(chip));
    $('#recentWrap').hidden = recent.length === 0;
    $('#engine').textContent = 'Поиск: ' + (d.engine === 'yandex' ? 'Яндекс' : 'Google');

    let next = null;
    if (refocusIndex >= 0) {
      next = grid.children[Math.min(refocusIndex, grid.children.length - 1)] || null;
      refocusIndex = -1;
    } else if (keep) {
      next = document.querySelector('[data-key="' + CSS.escape(keep) + '"]');
    }
    if (!next && current && current.isConnected && !modal) next = current;
    focus(next || $('#search'));
  }

  function focus(el) {
    if (!el) return;
    if (current) current.classList.remove('focus', 'press');
    current = el;
    if (pointerMode) return;
    el.classList.add('focus');
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  function setInputMode(mode) {
    const pointer = mode === 'pointer';
    if (pointerMode === pointer) return;
    pointerMode = pointer;
    enterHeld = false;
    longDone = false;
    document.documentElement.classList.toggle('pointer-input', pointer);
    document.querySelectorAll('.nav.focus,.nav.press').forEach(el => el.classList.remove('focus', 'press'));
    if (pointer) {
      if (document.activeElement && document.activeElement.matches('.nav')) document.activeElement.blur();
    } else {
      focus(current && current.isConnected ? current : (modal || $('main')).querySelector('.nav'));
    }
  }

  function move(dir) {
    const scope = modal || $('main');
    const list = [...scope.querySelectorAll('.nav')].filter((e) => e.offsetParent !== null);
    if (!current || !list.includes(current)) {
      focus(list[0]);
      return;
    }
    const a = current.getBoundingClientRect();
    let best = null;
    let score = Infinity;
    for (const el of list) {
      if (el === current) continue;
      const b = el.getBoundingClientRect();
      let gap;
      let cross;
      let off;
      if (dir === 'left' || dir === 'right') {
        gap = dir === 'left' ? a.left - b.right : b.left - a.right;
        cross = Math.max(0, b.top - a.bottom, a.top - b.bottom);
        off = Math.abs((b.top + b.bottom) / 2 - (a.top + a.bottom) / 2);
      } else {
        gap = dir === 'up' ? a.top - b.bottom : b.top - a.bottom;
        cross = Math.max(0, b.left - a.right, a.left - b.right);
        off = Math.abs((b.left + b.right) / 2 - (a.left + a.right) / 2);
      }
      if (gap < -4) continue;
      const s = gap + cross * 3 + off * 0.05;
      if (s < score) {
        score = s;
        best = el;
      }
    }
    if (best) focus(best);
  }

  function ask(t, el) {
    modal = $('#confirm');
    $('#confirmText').textContent = 'Убрать «' + (t.title || host(t.url)) + '» с главной?';
    modal.hidden = false;
    $('#yes')._go = () => {
      refocusIndex = [...$('#tiles').children].indexOf(el);
      close();
      focus(el);
      cmd('remove', { url: t.url });
    };
    $('#no')._go = () => {
      close();
      focus(el);
    };
    focus($('#no'));
  }

  function close() {
    $('#confirm').hidden = true;
    modal = null;
  }

  addEventListener('keydown', (e) => {
    if (DIRS[e.key] || e.key === 'Enter' || e.key === ' ' || e.key === 'Tab') setInputMode('keys');
    if (e.key === 'Tab') {
      e.preventDefault();
      const list = [...(modal || $('main')).querySelectorAll('.nav')].filter(el => el.offsetParent !== null);
      if (list.length) {
        const index = list.indexOf(current), step = e.shiftKey ? -1 : 1;
        focus(list[(index + step + list.length) % list.length]);
      }
      return;
    }
    if (DIRS[e.key]) {
      e.preventDefault();
      if (!enterHeld) move(DIRS[e.key]);
      return;
    }
    if (e.key !== 'Enter' && e.key !== ' ') return;
    e.preventDefault();
    if (!e.repeat) {
      enterHeld = true;
      longDone = false;
      if (current) current.classList.add('press');
    } else if (enterHeld && !longDone) {
      longDone = true;
      if (current) {
        current.classList.remove('press');
        if (current._long) current._long();
      }
    }
  });

  addEventListener('keyup', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    e.preventDefault();
    if (current) current.classList.remove('press');
    if (enterHeld && !longDone && current && current._go) current._go();
    enterHeld = false;
  });

  $('#search')._go = () => cmd('search');
  $('#voice')._go = () => cmd('voice');
  $('#engine')._go = () => cmd('engine');
  $('#agent')._go = () => cmd('agent');
  $('#remote')._go = () => cmd('remote');
  addEventListener('click', e => {
    const el = e.target.closest('.nav');
    if (el && el._go) { setInputMode('pointer'); focus(el); el._go(); }
  });
  addEventListener('pointermove', () => setInputMode('pointer'), { passive: true });
  addEventListener('pointerdown', () => setInputMode('pointer'), { passive: true });
  addEventListener('focusin', e => {
    if (!pointerMode && e.target.matches('.nav') && current !== e.target) focus(e.target);
  });

  window.momiji = {
    render,
    setInputMode,
    setActive(active) { document.documentElement.classList.toggle('page-hidden', !active); },
    back() {
      if (!modal) return false;
      $('#no')._go();
      return true;
    },
  };

  clock();
  setInterval(clock, 15000);
  leaves();
  sparks();
  focus($('#search'));
  setTimeout(() => { if (!still()) gust(); }, 3500);
  every(16, 32, gust);

  afterLoad(() => {
    const art = new Image();
    art.onload = () => {
      $('#art').style.backgroundImage = 'url(art.jpg)';
      $('#scene').classList.add('art');
      setTimeout(() => { if (!still()) birds(); }, 6000);
      every(45, 75, birds);
    };
    art.src = 'art.jpg';
  });

  if (location.protocol === 'file:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1') {
    render({
      engine: 'google',
      tiles: [
        { title: 'YouTube', url: 'https://www.youtube.com/' },
        { title: 'VK Видео', url: 'https://vkvideo.ru/' },
        { title: 'Rutube', url: 'https://rutube.ru/' },
        { title: 'Twitch', url: 'https://www.twitch.tv/' },
        { title: 'Shikimori', url: 'https://shikimori.io/' },
        { title: 'Яндекс', url: 'https://ya.ru/' },
        { title: 'Google', url: 'https://www.google.com/' },
        { title: 'Википедия', url: 'https://ru.wikipedia.org/' },
        { title: 'Reddit', url: 'https://www.reddit.com/' },
        { title: 'Pinterest', url: 'https://www.pinterest.com/' },
        { title: 'Пикабу', url: 'https://pikabu.ru/' },
        { title: 'Дзен', url: 'https://dzen.ru/' },
      ],
      recent: [
        { title: 'Аниме осени 2026', url: 'https://shikimori.io/animes' },
        { title: 'Lo-fi радио', url: 'https://www.youtube.com/' },
      ],
    });
  }
})();
