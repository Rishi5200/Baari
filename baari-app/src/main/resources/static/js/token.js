/* The patient's token page: renders every snapshot the server pushes. */
(function () {
  'use strict';
  const root = document.getElementById('token');
  const T = window.BAARI_T;
  const byId = function (id) { return document.getElementById(id); };
  let lastStatus = root.dataset.status;

  function beep() {
    // Browsers only allow sound after the person has touched the page; fail silently otherwise.
    try {
      const context = new (window.AudioContext || window.webkitAudioContext)();
      const tone = context.createOscillator();
      tone.frequency.value = 880;
      tone.connect(context.destination);
      tone.start();
      tone.stop(context.currentTime + 0.4);
    } catch (e) { /* no sound */ }
  }

  function render(view) {
    byId('status').textContent = T.status[view.status] || view.status;
    byId('code').textContent = view.displayCode;

    let ahead = '';
    if (view.status === 'WAITING') {
      ahead = view.peopleAhead === 0 ? T.next : T.ahead.replace('{0}', view.peopleAhead);
    }
    byId('ahead').textContent = ahead;
    byId('serving').textContent = view.nowServing.length ? view.nowServing.join(', ') : T.nobody;
    byId('closed').hidden = view.queueStatus !== 'CLOSED';
    byId('paused').hidden = view.queueStatus !== 'PAUSED';
    byId('cancel-form').hidden = view.status !== 'WAITING' && view.status !== 'CALLED';

    const called = view.status === 'CALLED';
    byId('called').hidden = !called;
    byId('called-code').textContent = view.displayCode;
    byId('room').textContent = view.room ? T.room.replace('{0}', view.room) : T.noRoom;
    if (called && lastStatus !== 'CALLED') {
      if (navigator.vibrate) { navigator.vibrate([300, 150, 300, 150, 600]); }
      beep();
    }
    lastStatus = view.status;
    root.dataset.status = view.status;
  }

  byId('cancel-form').addEventListener('submit', function (event) {
    if (!window.confirm(T.cancelConfirm)) { event.preventDefault(); }
  });

  Baari.stream(root.dataset.stream, render, function (state) {
    byId('conn').hidden = state === 'live';
  });
})();
