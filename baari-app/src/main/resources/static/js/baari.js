/* Shared helpers for the live pages (token, console, TV). Plain JavaScript, no framework. */
(function () {
  'use strict';

  /**
   * Opens a Server-Sent Events stream and calls onSnapshot(object) for every "snapshot" event.
   * Snapshots are complete state, so after a dropped connection the next snapshot makes the page
   * correct again; there is nothing to merge. EventSource reconnects by itself while the server is
   * unreachable; if the server answered with an error (the connection is CLOSED for good) we try
   * again after a few seconds. onState('live' | 'offline') drives the "reconnecting" banner.
   */
  function stream(url, onSnapshot, onState) {
    let source = null;
    let retry = null;

    function open() {
      clearTimeout(retry);
      if (source) { source.close(); }
      source = new EventSource(url);
      source.addEventListener('snapshot', function (event) {
        onState('live');
        onSnapshot(JSON.parse(event.data));
      });
      source.onerror = function () {
        onState('offline');
        if (source.readyState === EventSource.CLOSED) {
          retry = setTimeout(open, 5000);
        }
      };
    }

    // The phone woke up or the network came back: don't wait for the browser's own back-off.
    window.addEventListener('online', open);
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && (!source || source.readyState !== EventSource.OPEN)) { open(); }
    });
    open();
  }

  /** Builds an element. Text always goes in through textContent, never innerHTML: names are user input. */
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  window.Baari = { stream: stream, el: el };
})();
