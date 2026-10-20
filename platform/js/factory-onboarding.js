/* Progressive enhancement. All explanations and image links work without JavaScript. */
(() => {
  'use strict';
  const tabs = document.querySelector('.fb-proof-tabs');
  const proofs = document.querySelector('.fb-proofs');
  if (tabs && proofs) {
    const buttons = [...tabs.querySelectorAll('button[data-proof]')];
    const pairs = buttons.map(button => ({ button, panel: document.getElementById(button.dataset.proof) }));
    if (pairs.length && pairs.every(pair => pair.panel)) {
      tabs.setAttribute('role', 'tablist');
      const select = active => {
        pairs.forEach(({ button, panel }) => {
          const selected = button === active;
          button.setAttribute('role', 'tab');
          button.setAttribute('aria-controls', panel.id);
          button.setAttribute('aria-selected', String(selected));
          button.tabIndex = selected ? 0 : -1;
          panel.setAttribute('role', 'tabpanel');
          panel.setAttribute('aria-labelledby', button.id);
          panel.tabIndex = 0;
          panel.hidden = !selected;
        });
        // A panel that was hidden has stale scroll positions; re-measure so the
        // site's section motion (v6-premium) does not leave it faded out.
        if (window.ScrollTrigger) window.ScrollTrigger.refresh();
      };
      pairs.forEach(({ button }, index) => {
        button.addEventListener('click', () => select(button));
        button.addEventListener('keydown', event => {
          let next;
          if (event.key === 'ArrowRight') next = (index + 1) % buttons.length;
          if (event.key === 'ArrowLeft') next = (index + buttons.length - 1) % buttons.length;
          if (event.key === 'Home') next = 0;
          if (event.key === 'End') next = buttons.length - 1;
          if (next === undefined) return;
          event.preventDefault();
          select(buttons[next]);
          buttons[next].focus();
        });
      });
      select(buttons[0]);
      proofs.classList.add('is-enhanced');
      tabs.hidden = false;
    }
  }

  const viewer = document.querySelector('.fb-viewer');
  if (viewer && typeof viewer.showModal === 'function') {
    const image = viewer.querySelector('img');
    const title = viewer.querySelector('h2');
    const scale = viewer.querySelector('.fb-viewer-scale');
    const body = viewer.querySelector('.fb-viewer-body');
    const close = viewer.querySelector('.fb-viewer-close');
    let opener = null;
    document.querySelectorAll('a[data-image-title]').forEach(link => {
      link.addEventListener('click', event => {
        if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        opener = link;
        image.src = link.href;
        image.alt = link.dataset.imageTitle + '，本地模拟业务演示';
        title.textContent = link.dataset.imageTitle;
        viewer.classList.remove('is-original');
        scale.setAttribute('aria-pressed', 'false');
        scale.textContent = '原图大小';
        document.body.classList.add('fb-image-open');
        if (window.__v6lenis) window.__v6lenis.stop();   // smooth scroll would swallow the viewer's own scrolling
        viewer.showModal();
        body.scrollTo(0, 0);
        close.focus({ preventScroll: true });
      });
    });
    scale.addEventListener('click', () => {
      const original = viewer.classList.toggle('is-original');
      scale.setAttribute('aria-pressed', String(original));
      scale.textContent = original ? '适应窗口' : '原图大小';
      body.scrollTo(0, 0);
    });
    close.addEventListener('click', () => viewer.close());
    viewer.addEventListener('click', event => {
      const bounds = viewer.getBoundingClientRect();
      if (event.target === viewer && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) viewer.close();
    });
    viewer.addEventListener('close', () => {
      document.body.classList.remove('fb-image-open');
      if (window.__v6lenis) window.__v6lenis.start();
      image.removeAttribute('src');
      opener?.focus({ preventScroll: true });
    });
  }

  // In-page sub-nav: sticks below the site nav (which is only sticky on desktop),
  // highlights the section being read, and scrolls smoothly (through Lenis when it runs).
  const subnav = document.querySelector('.fb-subnav');
  const siteNav = document.querySelector('.v6-nav');
  if (subnav && siteNav) {
    const links = [...subnav.querySelectorAll('a[href^="#"]')];
    const targets = links.map(link => document.getElementById(link.hash.slice(1)));
    const root = document.documentElement;
    const navHeight = () => (getComputedStyle(siteNav).position === 'sticky' ? siteNav.offsetHeight : 0);
    const measure = () => root.style.setProperty('--fb-nav-h', navHeight() + 'px');
    let current = null;
    const mark = () => {
      const line = navHeight() + subnav.offsetHeight + 24;
      let active = null;
      targets.forEach((target, i) => { if (target && target.getBoundingClientRect().top <= line) active = links[i]; });
      if (active === current) return;
      if (current) current.removeAttribute('aria-current');
      current = active;
      if (!active) return;
      active.setAttribute('aria-current', 'true');
      const row = active.parentElement;
      // Keep the active pill in view on narrow screens (instant: it follows the page scroll anyway)
      if (row.scrollWidth > row.clientWidth) row.scrollLeft = active.offsetLeft - (row.clientWidth - active.offsetWidth) / 2;
    };
    let queued = false;
    const onScroll = () => { if (queued) return; queued = true; requestAnimationFrame(() => { queued = false; mark(); }); };
    measure(); mark();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', () => { measure(); onScroll(); });
    links.forEach((link, i) => link.addEventListener('click', event => {
      const target = targets[i];
      if (!target || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      // Absolute target from the real scrollY: Lenis' own position can lag a native scroll that just happened
      const top = target.getBoundingClientRect().top + scrollY - (navHeight() + subnav.offsetHeight + 16);
      if (window.__v6lenis) window.__v6lenis.scrollTo(top);
      else window.scrollTo({ top, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
      history.pushState(null, '', link.hash);
    }));
  }
})();
