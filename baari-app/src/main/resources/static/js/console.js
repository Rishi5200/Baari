/* The staff queue console. Draws the board from the staff stream and sends every button press to
   the staff REST API; all rules (who may do what, which transition is legal) are on the server. */
(function () {
  'use strict';
  const root = document.getElementById('console');
  const T = window.BAARI_T;
  const api = root.dataset.api;
  const queueId = root.dataset.queueId;
  const csrfToken = document.querySelector('meta[name=csrf-token]').content;
  const csrfHeader = document.querySelector('meta[name=csrf-header]').content;
  const byId = function (id) { return document.getElementById(id); };
  const el = Baari.el;

  let board = null;
  // Predicted start per waiting token id. Only the stream carries it; the plain board fetch after a
  // button press does not, so the last value stays until the next stream snapshot.
  let estimates = {};
  let toastTimer = null;

  // --- talking to the server ---

  function toast(message) {
    const box = byId('toast');
    box.textContent = message;
    box.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { box.hidden = true; }, 6000);
  }

  /** POSTs JSON with the CSRF token. Resolves to the parsed body; rejects with the ProblemDetail. */
  async function post(path, body) {
    const headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader] = csrfToken;
    const response = await fetch(api + path, {
      method: 'POST',
      headers: headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) { throw data || { detail: T.error }; }
    return data;
  }

  /** Runs an action; shows the server's message if it is refused; refreshes the board either way. */
  async function act(path, body) {
    try {
      await post(path, body);
      return true;
    } catch (problem) {
      toast(problem.detail || T.error);
      return false;
    } finally {
      refresh();
    }
  }

  async function refresh() {
    try {
      const response = await fetch(api + '/queues/' + queueId + '/board');
      if (response.ok) { render(await response.json()); }
    } catch (e) { /* the stream banner already says we are offline */ }
  }

  function uuid() {
    if (window.crypto && crypto.randomUUID) { return crypto.randomUUID(); }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }

  // --- drawing ---

  function button(label, className, handler) {
    const b = el('button', className, label);
    b.type = 'button';
    b.addEventListener('click', handler);
    return b;
  }

  function doctorId() {
    const select = byId('doctor');
    return select && select.value ? { doctorId: select.value } : undefined;
  }

  async function noShow(token) {
    try {
      await post('/tokens/' + token.id + '/no-show', {});
    } catch (problem) {
      if (problem.type && problem.type.endsWith('grace-not-elapsed') && window.confirm(T.noShowConfirm)) {
        await act('/tokens/' + token.id + '/no-show', { force: true });
        return;
      }
      toast(problem.detail || T.error);
    }
    refresh();
  }

  function cancel(token) {
    if (window.confirm(T.cancelConfirm)) { act('/tokens/' + token.id + '/cancel'); }
  }

  function prioritySelect(token) {
    const select = el('select', 'priority');
    ['NORMAL', 'PRIORITY', 'EMERGENCY'].forEach(function (p) {
      const option = el('option', null, T.priority[p]);
      option.value = p;
      option.selected = p === token.priority;
      select.appendChild(option);
    });
    select.addEventListener('change', function () {
      act('/tokens/' + token.id + '/priority', { priority: select.value });
    });
    return select;
  }

  function row(token, actions) {
    const li = el('li', 'token ' + token.priority.toLowerCase());
    li.appendChild(el('strong', 'code', token.displayCode));
    li.appendChild(el('span', 'name', token.patientName));
    if (token.priority !== 'NORMAL') { li.appendChild(el('span', 'badge', T.priority[token.priority])); }
    li.appendChild(el('span', 'muted', T.visit[token.visitType] || ''));
    if (token.phoneLast4) { li.appendChild(el('span', 'muted', '···' + token.phoneLast4)); }
    const estimate = estimates[token.id];
    if (estimate) {
      const time = new Date(estimate.expectedStartAt)
        .toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      const minutes = Math.round(estimate.waitSeconds / 60);
      li.appendChild(el('span', 'muted eta', T.expected.replace('{0}', time).replace('{1}', minutes)));
    }
    const box = el('span', 'actions');
    actions.forEach(function (a) { box.appendChild(a); });
    li.appendChild(box);
    return li;
  }

  function fill(listId, tokens, actionsFor) {
    const list = byId(listId);
    list.replaceChildren();
    if (!tokens.length) {
      list.appendChild(el('li', 'none', T.empty));
      return;
    }
    tokens.forEach(function (t) { list.appendChild(row(t, actionsFor(t))); });
  }

  function render(next) {
    board = next;
    if (next.estimates) { estimates = next.estimates; }
    const status = board.day ? board.day.status : 'NOT_OPENED';
    byId('day-status').textContent = T.dayStatus[status];
    byId('day-status').className = 'pill ' + status.toLowerCase();
    byId('day-open').hidden = status !== 'NOT_OPENED';
    byId('day-pause').hidden = status !== 'OPEN';
    byId('day-resume').hidden = status !== 'PAUSED';
    byId('day-close').hidden = status !== 'OPEN' && status !== 'PAUSED';
    byId('call-next').disabled = status === 'NOT_OPENED' || status === 'CLOSED';
    byId('walkin').querySelector('button').disabled = status === 'NOT_OPENED' || status === 'CLOSED';

    fill('list-called', board.called, function (t) {
      return [
        button(T.start, '', function () { act('/tokens/' + t.id + '/start'); }),
        button(T.noShow, 'secondary', function () { noShow(t); }),
        button(T.cancel, 'secondary', function () { cancel(t); })
      ];
    });
    fill('list-consult', board.inConsultation, function (t) {
      return [button(T.complete + ' (C)', '', function () { act('/tokens/' + t.id + '/complete'); })];
    });
    fill('list-waiting', board.waiting, function (t) {
      return [
        prioritySelect(t),
        button(T.callThis, 'secondary', function () { act('/tokens/' + t.id + '/call', doctorId()); }),
        button(T.cancel, 'secondary', function () { cancel(t); })
      ];
    });
    fill('list-noshow', board.noShow, function (t) {
      return [button(T.recall, '', function () { act('/tokens/' + t.id + '/recall'); })];
    });
    // The newest ones first; a long day would otherwise push everything else off the screen.
    fill('list-finished', board.finished.slice().reverse().slice(0, 15), function (t) {
      return [el('span', 'muted', t.status)];
    });
  }

  // --- buttons ---

  ['open', 'pause', 'resume', 'close'].forEach(function (name) {
    byId('day-' + name).addEventListener('click', function () {
      if (name === 'close' && !window.confirm(T.closeConfirm)) { return; }
      act('/queues/' + queueId + '/days/today/' + name);
    });
  });

  function callNext() { act('/queues/' + queueId + '/call-next', doctorId()); }
  byId('call-next').addEventListener('click', callNext);

  byId('walkin').addEventListener('submit', async function (event) {
    event.preventDefault();
    const form = event.target;
    const phone = form.phone.value.trim();
    // The key makes a double click harmless: the server returns the same token for the same key.
    form.dataset.key = form.dataset.key || uuid();
    const ok = await act('/queues/' + queueId + '/tokens', {
      name: form.name.value.trim(),
      phone: phone || null,
      visitType: form.visitType.value,
      priority: form.priority.value,
      idempotencyKey: form.dataset.key
    });
    if (ok) {
      form.reset();
      delete form.dataset.key;
      form.name.focus();
    }
  });

  document.addEventListener('keydown', function (event) {
    const target = event.target;
    if (event.ctrlKey || event.metaKey || event.altKey) { return; }
    if (target.matches('input, select, textarea')) { return; }
    if (event.key === 'n' || event.key === 'N') {
      event.preventDefault();
      if (!byId('call-next').disabled) { callNext(); }
    } else if ((event.key === 'c' || event.key === 'C') && board && board.inConsultation.length) {
      event.preventDefault();
      act('/tokens/' + board.inConsultation[0].id + '/complete');
    }
  });

  Baari.stream(root.dataset.stream, render, function (state) {
    byId('conn').hidden = state === 'live';
  });
})();
