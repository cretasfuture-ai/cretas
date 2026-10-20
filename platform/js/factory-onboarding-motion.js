/* 对话建厂页动效层 —— 在 v6-premium.js 之后加载 (需要 gsap / ScrollTrigger / Lenis 已就绪)。
   减少动态或缺少 gsap 时什么都不做: 页面保持原样且内容全部可见。
   ⚠ 由 gsap 接管 transform 的元素, 必须先摘掉 .v6-reveal: 否则 gsap 会把 reveal 的
     translateY(130px) 读进缓存并写成内联样式 (首页业务卡错位 130px 的同一个坑)。 */
(() => {
  'use strict';
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const gsap = window.gsap, ST = window.ScrollTrigger;
  if (reduce || !gsap || !ST || !document.body.classList.contains('fb-page')) return;
  document.documentElement.classList.add('fbm');
  const desktop = window.matchMedia('(min-width: 901px)').matches;
  const fine = window.matchMedia('(pointer: fine)').matches;
  const takeOver = el => { el.classList.remove('v6-reveal'); el.classList.add('in'); el.style.transitionDelay = ''; };
  /* v6-premium 给每个 <section> 挂了「离场淡出」; 被我们 pin 住的区块必须摘掉它,
     否则 pin 期间区块会边播边淡到透明 (与首页固定段空屏同一类问题)。 */
  const exemptFromExit = el => {
    ST.getAll().forEach(t => {
      if (t.trigger === el && !t.pin && t.animation) { t.animation.progress(0); t.animation.kill(); t.kill(); }
    });
    gsap.set(el, { clearProps: 'opacity,transform,scale,y' });
  };
  const safely = (name, fn) => { try { fn(); } catch (err) { console.warn('[fbm] ' + name + ' skipped:', err); } };

  /* 液态玻璃导航: 离开页顶后变成毛玻璃 */
  safely('glass-nav', () => {
    const nav = document.querySelector('.fb-page .v6-nav');
    if (!nav) return;
    ST.create({ start: 24, end: 'max', onToggle: self => nav.classList.toggle('is-glass', self.isActive) });
  });

  /* 首屏标题: 逐字错峰入场 (保留 <br> 与绿色 span; 读屏读整句) */
  safely('hero-title', () => {
    const h1 = document.getElementById('fb-title');
    if (!h1) return;
    h1.setAttribute('aria-label', h1.textContent.replace(/\s+/g, ''));
    const split = node => {
      [...node.childNodes].forEach(child => {
        if (child.nodeType === 3) {
          const frag = document.createDocumentFragment();
          [...child.textContent].forEach(ch => {
            if (!ch.trim()) { frag.appendChild(document.createTextNode(ch)); return; }
            const s = document.createElement('span');
            s.className = 'fbm-ch'; s.setAttribute('aria-hidden', 'true'); s.textContent = ch;
            frag.appendChild(s);
          });
          child.replaceWith(frag);
        } else if (child.nodeType === 1 && child.tagName !== 'BR') {
          split(child);
        }
      });
    };
    split(h1);
    gsap.from(h1.querySelectorAll('.fbm-ch'), {
      yPercent: 70, opacity: 0, rotate: 6, filter: 'blur(6px)',
      duration: .9, ease: 'expo.out', stagger: .035, delay: .1, clearProps: 'filter',
    });
  });

  /* 首屏截图: 3D 倾斜 → 随滚动展平; 指针聚光 */
  safely('hero-shot', () => {
    const link = document.querySelector('.fb-hero-visual .fb-image-link');
    if (!link) return;
    const spot = document.createElement('span');
    spot.className = 'fbm-spot'; spot.setAttribute('aria-hidden', 'true');
    link.appendChild(spot);
    if (desktop) {
      gsap.fromTo(link,
        { rotateX: 16, rotateY: -14, rotateZ: 1.5, scale: .94, transformOrigin: '50% 60%' },
        { rotateX: 0, rotateY: 0, rotateZ: 0, scale: 1, ease: 'none',
          scrollTrigger: { trigger: '.fb-hero', start: 'top top', end: 'bottom 35%', scrub: .6 } });
    }
    if (fine) {
      link.addEventListener('pointermove', e => {
        const r = link.getBoundingClientRect();
        link.style.setProperty('--fbm-x', (e.clientX - r.left) + 'px');
        spot.style.setProperty('--fbm-x', (e.clientX - r.left) + 'px');
        spot.style.setProperty('--fbm-y', (e.clientY - r.top) + 'px');
      });
    }
  });

  /* 四步: 滚动联动进度线 + 逐步点亮 */
  safely('method-rail', () => {
    const band = document.querySelector('.fb-method');
    const ol = band && band.querySelector('ol');
    if (!ol) return;
    const steps = [...ol.children];
    if (!desktop) { steps.forEach(li => li.classList.add('is-lit')); return; }
    steps.forEach(takeOver);
    exemptFromExit(band);
    const rail = document.createElement('span');
    rail.className = 'fbm-rail'; rail.setAttribute('aria-hidden', 'true');
    rail.appendChild(document.createElement('i'));
    ol.prepend(rail);
    const fill = rail.firstChild;
    const paint = p => {
      gsap.set(fill, { scaleX: p });
      steps.forEach((li, i) => li.classList.toggle('is-lit', p >= (i / steps.length) + .02));
    };
    paint(0);
    /* 不 pin: 这条带只有 ~230px 高, pin 在视口中间时上下各留出大片空白 (实测截图)。
       改为随它自然穿过视口填满进度线, 上下文始终在屏上。 */
    ST.create({
      trigger: band, start: 'top 78%', end: 'bottom 30%',
      scrub: true, onUpdate: self => paint(self.progress),
      onLeave: () => paint(1),
    });
  });

  /* 三层卡: 从叠在一起展开成三列 (桌面) */
  safely('layers-fan', () => {
    const wrap = document.querySelector('.fb-layers');
    if (!wrap || !desktop) return;
    const cards = [...wrap.children];
    if (cards.length !== 3) return;
    cards.forEach(takeOver);
    const w = wrap.getBoundingClientRect().width / 3;
    /* 展开要在卡片到达阅读位置 (视口中部) 之前完成: 停在半途时文字互相压住就读不了 (实测截图) */
    gsap.fromTo(cards,
      { x: i => (1 - i) * w * .55, rotate: i => (i - 1) * 4, y: i => Math.abs(i - 1) * 16, scale: .94, opacity: i => (i === 1 ? 1 : .5) },
      { x: 0, rotate: 0, y: 0, scale: 1, opacity: 1, ease: 'power1.out',
        scrollTrigger: { trigger: wrap, start: 'top 100%', end: 'top 72%', scrub: .4 } });
  });

  /* 工厂配合: 当前步骤 (穿过视口中线的那一步) 高亮 */
  safely('cooperate-current', () => {
    document.querySelectorAll('.fb-cooperate li').forEach(li => {
      ST.create({ trigger: li, start: 'top 55%', end: 'bottom 55%', toggleClass: { targets: li, className: 'is-current' } });
    });
  });

  /* 主按钮: 磁吸 (从当前位置随时改向; 松手平滑回位, 不回弹) */
  safely('magnetic', () => {
    if (!fine) return;
    document.querySelectorAll('.fb-page .fb-primary').forEach(btn => {
      const x = gsap.quickTo(btn, 'x', { duration: .45, ease: 'power3.out' });
      const y = gsap.quickTo(btn, 'y', { duration: .45, ease: 'power3.out' });
      btn.addEventListener('pointermove', e => {
        const r = btn.getBoundingClientRect();
        x((e.clientX - r.left - r.width / 2) * .25);
        y((e.clientY - r.top - r.height / 2) * .35);
      });
      btn.addEventListener('pointerleave', () => { x(0); y(0); });
    });
  });

  /* 演示标签: 用 View Transitions 让截图与说明在切换时形变过渡 (浏览器不支持就照常切换) */
  safely('proof-morph', () => {
    if (typeof document.startViewTransition !== 'function') return;
    document.querySelectorAll('.fb-proof-tabs button[data-proof]').forEach(btn => {
      btn.addEventListener('click', e => {
        if (btn.getAttribute('aria-selected') === 'true' || btn.dataset.fbmMorph) return;
        e.stopImmediatePropagation();
        btn.dataset.fbmMorph = '1';
        const vt = document.startViewTransition(() => { btn.click(); });
        vt.finished.finally(() => { delete btn.dataset.fbmMorph; });
      }, true);
    });
  });

  window.addEventListener('load', () => ST.refresh());
})();
