/* Brief brand greeting; content and scrolling stay available throughout. */
(function(){
  var el = document.getElementById('v6Intro');
  if (!el) return;
  var motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  var desktop = window.matchMedia('(min-width: 900px) and (pointer: fine)');
  var seen = false;
  try { seen = sessionStorage.getItem('cretasIntroSeen') === '1'; } catch(e){}
  window.dispatchEvent(new Event('v6:enter'));
  if (seen || motion.matches || !desktop.matches) { el.remove(); return; }
  el.classList.add('is-greeting');
  try { sessionStorage.setItem('cretasIntroSeen', '1'); } catch(e){}
  var timer;
  function dismiss(){
    clearTimeout(timer);
    window.removeEventListener('wheel', dismiss);
    window.removeEventListener('keydown', dismiss);
    window.removeEventListener('pointerdown', dismiss);
    motion.removeEventListener('change', dismiss);
    desktop.removeEventListener('change', dismiss);
    el.remove();
  }
  window.addEventListener('wheel', dismiss, {passive:true});
  window.addEventListener('keydown', dismiss);
  window.addEventListener('pointerdown', dismiss);
  motion.addEventListener('change', dismiss);
  desktop.addEventListener('change', dismiss);
  timer = setTimeout(dismiss, 1200);
})();
