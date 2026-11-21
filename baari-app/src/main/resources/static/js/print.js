// The poster's Print button. A separate file because the Content Security Policy allows no inline handlers.
document.getElementById('print').addEventListener('click', () => window.print());
