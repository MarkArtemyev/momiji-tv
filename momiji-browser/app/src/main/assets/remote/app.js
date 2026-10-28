(() => {
  const $ = s => document.querySelector(s);
  let token = '', queue = [], busy = false, timer = 0;
  const status = text => { $('#status').textContent = text; };
  async function post(path, data) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data), signal: AbortSignal.timeout(5000) });
  }
  $('#pair').addEventListener('submit', async e => {
    e.preventDefault(); $('#error').textContent = '';
    try {
      const r = await post('/pair', { code: $('#code').value });
      if (!r.ok) throw new Error(await r.text());
      token = (await r.json()).token;
      $('#pairing').hidden = true; $('#controls').hidden = false;
      status('Подключён к ТВ'); $('#pad').focus();
      clearInterval(timer); timer = setInterval(flush, 45);
    } catch (error) { $('#error').textContent = error.message || 'Не удалось подключиться'; }
  });
  function enqueue(event) {
    if (!token) return;
    const last = queue[queue.length - 1];
    if (event.type === 'move' && last && last.type === 'move') queue[queue.length - 1] = event;
    else if (queue.length < 80) queue.push(event);
  }
  async function flush() {
    if (busy || !token || !queue.length) return;
    busy = true;
    const events = queue.splice(0, 20);
    try {
      const r = await post('/input', { token, events });
      if (r.status === 401) disconnect();
      else if (r.status === 409) status('Открой Momiji на ТВ');
      else if (r.ok) status('Подключён к ТВ');
      else status('Соединение занято');
    } catch (_) { status('Нет связи с ТВ'); }
    finally { busy = false; }
  }
  const pad = $('#pad');
  function point(e) {
    const r = pad.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), y: Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) };
  }
  pad.addEventListener('pointermove', e => enqueue({ type: 'move', ...point(e) }));
  pad.addEventListener('pointerdown', e => { pad.focus(); if (e.pointerType !== 'mouse') pad.setPointerCapture(e.pointerId); });
  pad.addEventListener('click', e => { enqueue({ type: 'move', ...point(e) }); enqueue({ type: 'click' }); });
  pad.addEventListener('contextmenu', e => { e.preventDefault(); enqueue({ type: 'command', command: 'menu' }); });
  pad.addEventListener('wheel', e => { e.preventDefault(); enqueue({ type: 'scroll', x: e.deltaX / 100, y: e.deltaY / 100 }); }, { passive: false });
  pad.addEventListener('keydown', e => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter','Escape','Backspace','Tab','Delete','Home','End'].includes(e.key)) {
      e.preventDefault(); enqueue({ type: 'key', key: e.key, shift: e.shiftKey });
    } else if (e.key.length === 1) { e.preventDefault(); enqueue({ type: 'text', text: e.key }); }
  });
  pad.addEventListener('paste', e => { const text = e.clipboardData.getData('text/plain'); if (text) { e.preventDefault(); enqueue({ type: 'text', text: text.slice(0, 4096) }); } });
  document.querySelectorAll('[data-command]').forEach(b => b.addEventListener('click', () => enqueue({ type: 'command', command: b.dataset.command })));
  $('#text-form').addEventListener('submit', e => { e.preventDefault(); const text = $('#text').value.slice(0, 4096); if (text) enqueue({ type: 'text', text }); });
  $('#open-url').addEventListener('click', () => { const text = $('#text').value.trim().slice(0, 4096); if (text) enqueue({ type: 'open', text }); });
  function disconnect() {
    token = ''; queue = []; clearInterval(timer); timer = 0;
    $('#controls').hidden = true; $('#pairing').hidden = false; $('#code').value = ''; status('Отключён');
  }
  $('#disconnect').addEventListener('click', async () => {
    try { await post('/disconnect', { token }); } catch (_) {}
    disconnect();
  });
})();
