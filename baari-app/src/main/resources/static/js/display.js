/* The waiting-room TV: shows display codes only (the server never sends names to this stream). */
(function () {
  'use strict';
  const root = document.getElementById('board');
  const T = window.BAARI_T;
  const byId = function (id) { return document.getElementById(id); };

  function fill(list, items, build) {
    list.replaceChildren();
    if (!items.length) {
      list.appendChild(Baari.el('li', 'none', T.nobody));
      return;
    }
    items.forEach(function (item) { list.appendChild(build(item)); });
  }

  function render(board) {
    byId('queue-name').textContent = board.queueName;
    const notices = { null: T.notOpen, PAUSED: T.paused, CLOSED: T.closed };
    byId('notice').textContent = notices[board.status] || '';

    fill(byId('serving'), board.nowServing, function (s) {
      const li = Baari.el('li');
      li.appendChild(Baari.el('span', 'big-code', s.code));
      if (s.room) { li.appendChild(Baari.el('span', 'room', s.room)); }
      return li;
    });
    fill(byId('next'), board.next, function (code) { return Baari.el('li', null, code); });
    byId('waiting').textContent = board.waiting;
  }

  Baari.stream(root.dataset.stream, render, function (state) {
    byId('conn').hidden = state === 'live';
  });
})();

// Rotating QR (PLAN §9.3): a link is accepted for about two minutes, so fetch the next one every 20 s.
(function () {
  const join = document.getElementById('join');
  if (!join) {
    return;
  }
  const url = document.getElementById('join-url');
  async function refresh() {
    try {
      const response = await fetch(join.dataset.src, { cache: 'no-store' });
      if (response.ok) {
        const link = await response.json();
        url.textContent = join.dataset.base + link.path;
      }
    } catch (e) {
      // offline for a moment: keep the current link and try again on the next tick
    }
  }
  setInterval(refresh, 20000);
})();
