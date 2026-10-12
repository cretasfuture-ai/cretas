/* V6 motion system.
   Reveal philosophy: elements glide in early and softly (no pop), siblings cascade
   with a small stagger instead of section-blocks appearing one by one.
   Everything degrades to fully-visible static under prefers-reduced-motion / no-JS. */

function v6ReducedMotion(){
  return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/* Variant auto-assignment: element type -> reveal style, so one screen mixes
   several entrance gestures without any per-page markup changes.
   First matching rule wins; elements keep the default fade-up otherwise. */
function v6RevealVariants(){
  var MAP = [
    ['.kicker.v6-reveal', 'rv-clipx'],                                   /* mono 眉题: 扫描式横擦 */
    ['blockquote.v6-reveal', 'rv-blur'],                                 /* 引语: 模糊聚焦 */
    ['.lvis.v6-reveal, .rvis.v6-reveal, .fvis.v6-reveal, .addrchk.v6-reveal', 'rv-pop'], /* 证据拟物卡: 弹性放大 */
    ['.result.v6-reveal', 'rv-pop'],
    ['.tl .tstep.v6-reveal', 'rv-l'],                                    /* 时间线: 自左滑入 */
    ['.ba .col.before.v6-reveal', 'rv-l'],
    ['.ba .col.after.v6-reveal', 'rv-r'],
    ['.srow figure', 'rv-tilt'],                                         /* 实拍卡: 斜角起身 */
    ['.pick.v6-reveal', 'rv-l'],
    ['.lcopy .v6-reveal, .fcopy .v6-reveal', 'rv-l'],                    /* 双栏左叙事整列左入 */
    ['.dlist .drow:nth-child(odd)', 'rv-l'],                             /* demo 行: 左右交替 */
    ['.dlist .drow:nth-child(even)', 'rv-r'],
    ['.d-note.v6-reveal', 'rv-blur'],
  ];
  MAP.forEach(function(rule){
    document.querySelectorAll(rule[0]).forEach(function(el){
      if(!/(^| )rv-/.test(el.className)){
        el.classList.add('v6-reveal');   /* variants imply reveal (srow figures lack it) */
        el.classList.add(rule[1]);
      }
    });
  });
}

function v6RevealInit(){
  v6RevealVariants();
  var els = document.querySelectorAll('.v6-reveal');
  if(!('IntersectionObserver' in window) || v6ReducedMotion()){
    els.forEach(function(e){ e.classList.add('in'); });
    return;
  }
  /* Auto-stagger: siblings that reveal together cascade 60ms apart (capped),
     so a group reads as one gesture instead of separate blocks. */
  var groups = new Map();
  els.forEach(function(e){
    var p = e.parentElement;
    if(!groups.has(p)) groups.set(p, []);
    e.__v6i = groups.get(p).length;
    groups.get(p).push(e);
  });
  function v6Show(el){
    var d = Math.min(el.__v6i || 0, 5) * 55;
    el.style.transitionDelay = d + 'ms';
    el.classList.add('in');
    /* clear delay after it has played so hover/other transitions aren't lagged */
    setTimeout(function(){ el.style.transitionDelay = ''; }, d + 750);
  }
  /* ⚠ 变体若把元素的几何压成 0, IntersectionObserver 会永远报不相交 ——
     reveal 于是永不触发, 元素永久不可见。rv-clipx 的初始态是
     clip-path:inset(0 100% 0 0)(裁成 0 宽), 正好踩中: 实测同一元素同一位置,
     带 clip 时 isIntersecting=false / rect 0x0, 解除后 ratio 立刻是 1。
     这类元素改为观察其父元素, 视觉与时序不变。 */
  var proxied = new Map();
  var io = new IntersectionObserver(function(entries){
    entries.forEach(function(en){
      if(!en.isIntersecting) return;
      var t = en.target;
      if(t.classList.contains('v6-reveal')) v6Show(t);
      (proxied.get(t) || []).forEach(v6Show);
      io.unobserve(t);
    });
  }, { threshold: 0.01, rootMargin: '0px 0px -3% 0px' });
  els.forEach(function(e){
    var zeroGeom = /(^| )rv-clipx( |$)/.test(e.className);
    var target = (zeroGeom && e.parentElement) ? e.parentElement : e;
    if(target !== e){
      if(!proxied.has(target)) proxied.set(target, []);
      proxied.get(target).push(e);
    }
    io.observe(target);
  });
}

/* Ambient glow drifts slightly with scroll — continuity between sections. */
function v6GlowDrift(){
  if(v6ReducedMotion()) return;
  var glows = document.querySelectorAll('.v6-glow');
  if(!glows.length) return;
  var ticking = false;
  function frame(){
    ticking = false;
    var y = window.scrollY || 0;
    var t = Math.min(y * 0.06, 48);
    glows.forEach(function(g){ g.style.transform = 'translateY(' + t + 'px)'; });
  }
  window.addEventListener('scroll', function(){
    if(!ticking){ ticking = true; requestAnimationFrame(frame); }
  }, { passive: true });
}

function v6LiveTicker(el, items){
  if(!el || !items.length) return;
  var i = 0;
  if(v6ReducedMotion()){ el.textContent = items.join(' · '); return; }
  setInterval(function(){
    i = (i + 1) % items.length;
    el.classList.add('tick-out');                    /* roll up + fade */
    setTimeout(function(){
      el.textContent = items[i];
      el.classList.remove('tick-out');
      el.classList.add('tick-in');                   /* jump below, then ease in */
      void el.offsetWidth;
      el.classList.remove('tick-in');
    }, 300);
  }, 4200);
}

/* Thin scroll progress line at the very top */
function v6ProgressInit(){
  if(v6ReducedMotion()) return;
  var bar = document.createElement('div');
  bar.id = 'v6Progress';
  document.body.appendChild(bar);
  var ticking = false;
  function frame(){
    ticking = false;
    var h = document.documentElement.scrollHeight - window.innerHeight;
    bar.style.transform = 'scaleX(' + (h > 0 ? Math.min(1, window.scrollY / h) : 0) + ')';
  }
  window.addEventListener('scroll', function(){
    if(!ticking){ ticking = true; requestAnimationFrame(frame); }
  }, { passive: true });
  frame();
}

document.addEventListener('DOMContentLoaded', function(){
  v6RevealInit();
  v6GlowDrift();
  v6ProgressInit();
});

/* Chat replay: the AI demo answers questions on loop — the site itself is "working".
   sets: array of conversations; each = array of {t:'q'|'a'|'act', html}.
   Existing static bubbles stay for no-JS / reduced-motion. */
function v6ChatLoop(el, sets, beforeSel){
  if(!el || !sets || !sets.length || v6ReducedMotion()) return;
  var anchor = beforeSel ? el.querySelector(beforeSel) : null;
  el.querySelectorAll('.bub, .act, .a, .q').forEach(function(n){ n.setAttribute('data-chat',''); });
  var si = 0, timers = [];
  function put(node){ node.__fresh = true; anchor ? el.insertBefore(node, anchor) : el.appendChild(node); }
  function mk(m){
    var d = document.createElement('div');
    d.setAttribute('data-chat','');
    d.className = (m.t==='act') ? 'act v6-chat-in' : 'bub ' + (m.t==='q'?'bub-q q':'bub-a a') + ' v6-chat-in';
    d.innerHTML = m.html;
    return d;
  }
  function play(){
    var seq = sets[si]; si = (si+1) % sets.length;
    var cleared = false;
    function clearOld(){
      if(cleared) return; cleared = true;
      el.querySelectorAll('[data-chat]').forEach(function(n){ if(!n.__fresh) n.remove(); });
    }
    var t = 500;
    seq.forEach(function(m){
      if(m.t === 'a'){
        (function(tt){
          timers.push(setTimeout(function(){
            clearOld();
            var ty = document.createElement('div');
            ty.setAttribute('data-chat','');
            ty.className = 'bub ' + 'bub-a a' + ' v6-typing v6-chat-in';
            ty.innerHTML = '<span class="tdots"><i></i><i></i><i></i></span>';
            put(ty);
            timers.push(setTimeout(function(){ ty.remove(); put(mk(m)); }, 950));
          }, tt));
        })(t);
        t += 1600;
      } else {
        (function(tt, mm){ timers.push(setTimeout(function(){ clearOld(); put(mk(mm)); }, tt)); })(t, m);
        t += (m.t === 'act') ? 900 : 700;
      }
    });
    timers.push(setTimeout(function(){
      el.querySelectorAll('[data-chat]').forEach(function(n){ n.__fresh = false; });
      play();
    }, t + 3800));
  }
  play();
}

/* Roaming highlight: a different capability chip lights up every beat. */
function v6Roam(sel){
  if(v6ReducedMotion()) return;
  var chips = Array.prototype.slice.call(document.querySelectorAll(sel));
  if(chips.length < 4) return;
  var cur = -1;
  setInterval(function(){
    if(cur >= 0) chips[cur].classList.remove('v6-roam');
    var next;
    do { next = Math.floor(Math.random() * chips.length); } while(next === cur);
    cur = next;
    chips[cur].classList.add('v6-roam');
  }, 2100);
}
