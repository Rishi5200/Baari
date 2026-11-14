/* Stops a double tap on "Get my token" from sending the form twice. The server would return the
   same token anyway (idempotency key); this just avoids the flicker. */
(function () {
  'use strict';
  const form = document.querySelector('form');
  if (!form) { return; }
  form.addEventListener('submit', function () {
    const button = form.querySelector('button[type=submit]');
    if (button) { setTimeout(function () { button.disabled = true; }, 0); }
  });
})();
