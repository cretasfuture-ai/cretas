'use strict';
const { test, expect } = require('@playwright/test');
const { inspectMotion } = require('../scenarios/sidebar-motion-readonly');
const { installMutationGuard } = require('../core/mutation-guard');
const { sanitizeValue } = require('../core/sanitizer');

function fixture({ fade = true, drift = false } = {}) {
  return `<!doctype html><html><head><style>
    *{box-sizing:border-box}body{margin:0}.app-sidebar{position:fixed;left:0;top:0;bottom:0;width:220px;transition:width 300ms ease-in-out}
    .app-sidebar.is-collapsed{width:64px}.app-main{margin-left:220px;transition:margin-left 300ms ease-in-out}
    body:has(.is-collapsed) .app-main{margin-left:64px}.el-sub-menu__title{display:flex;align-items:center;padding-left:20px}
    .el-icon{flex-shrink:0;width:24px;height:24px}.menu-title-copy,.logo-copy{opacity:1;transition:opacity ${fade ? '140ms' : '0ms'} ease-out}
    .is-collapsed .menu-title-copy,.is-collapsed .logo-copy{opacity:0}
    ${drift ? '.is-collapsed .el-sub-menu__title{padding-left:30px}' : ''}
    @media(prefers-reduced-motion:reduce){.app-sidebar,.app-main,.menu-title-copy,.logo-copy{transition:none}}
    @media(max-width:768px){.app-sidebar,.app-sidebar.is-collapsed{width:260px;transform:translateX(-100%)}
      .app-sidebar.mobile-open{transform:none}.app-sidebar .menu-title-copy,.app-sidebar .logo-copy{opacity:1}.app-main{margin-left:0!important}}
    </style></head><body><aside class="app-sidebar"><span class="logo-copy">本地测试品牌</span><div class="sidebar-menu">
    <div class="el-menu-item"><span class="menu-title-copy">首页</span></div><div class="el-sub-menu"><div class="el-sub-menu__title"><i class="el-icon"></i><span class="menu-title-copy">生产管理</span></div></div>
    </div></aside><main class="app-main"><button class="collapse-btn">切换导航</button><p>本地测试工作台</p></main>
    <script>const rail=document.querySelector('.app-sidebar');const menu=document.querySelector('.sidebar-menu');
    document.querySelector('.collapse-btn').onclick=()=>{if(innerWidth<768)rail.classList.toggle('mobile-open');else{rail.classList.toggle('is-collapsed');menu.classList.toggle('el-menu--collapse',rail.classList.contains('is-collapsed'))}};
    addEventListener('resize',()=>{if(innerWidth<768){rail.classList.remove('is-collapsed');menu.classList.remove('el-menu--collapse')}});</script></body></html>`;
}

for (const [name, options, result, message] of [
  ['accepts real intermediate frames and reversible layout', {}, 'PASS', null],
  ['rejects text that jumps without fading', { fade: false }, 'CONFIRMED_DEFECT', 'fade out through intermediate opacity'],
  ['rejects an icon that shifts during collapse', { drift: true }, 'CONFIRMED_DEFECT', 'horizontal alignment'],
]) {
  test(name, async ({ page }, testInfo) => {
    await page.setViewportSize({width:1440,height:900});
    await page.setContent(fixture(options));
    const guard = await installMutationGuard(page.context());
    try {
      const evidence = await inspectMotion(page, '', { mutationGuard: guard,
        screenshot: async label => { const file = testInfo.outputPath(label+'.png'); await page.screenshot({path:file}); return file; } });
      expect(evidence.assessment.result).toBe(result);
      if(result === 'PASS') {
        const saved = sanitizeValue({scenarios:[{pageEvidence:[evidence]}]}).scenarios[0].pageEvidence[0];
        expect(saved.closingOpacities.some(value => typeof value === 'number' && value > 0 && value < 1)).toBe(true);
        expect(saved.closingWidths.at(-1)).toBe(64);
        expect(saved.maximumIconShiftPx).toBe(0);
      }
      if(message) expect(evidence.error).toContain(message);
      expect(guard.snapshot().actualBusinessWrites).toBe(0);
      expect(guard.snapshot().blockedMutationAttempts).toHaveLength(0);
    } finally { await guard.dispose(); }
  });
}
