import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');

/**
 * 进页面直接全屏（owner 2026-09-01 指定）。
 *
 * 🔴 这一版**第一次做的时候是坏的，而且看着是好的**：
 * 只给 `.workflow-editor` 加 `position: fixed; inset: 0; z-index: 1500`，
 * 画布宽度量出来 100%、`is-immersive` 也是 true —— 每一项读数都对。
 * 但工具栏按钮点下去命中的是顶栏（`.app-header`，z-index **99**）：
 * 祖先链上有栈上下文，子元素的 z-index 在外层比不过，**拉到 9999 也没用**，
 * 把节点挪到 body 下当场就好。
 *
 * ⇒ 判据必须钉「怎么实现的」，⛔ 不能钉「铺满没有」——
 *   铺满是这个缺陷的**症状之一**，而它当时是正常的。
 */
describe('§ 沉浸式画布', () => {
  it('🔴 靠**收起外框**实现，⛔ 不靠盖住它', () => {
    // 这条判据换过两次，每次都是因为前一版**真的坏了**：
    //   v1 编辑器 position:fixed + z-index:1500 覆盖 → 顶栏(z-index 99)反而在上面,
    //      工具栏按钮点下去命中顶栏; z-index 拉到 9999 都没用(祖先栈上下文)。
    //   v2 改 Teleport 到 body → 层级好了, 但内容被挪出组件树: 父组件 class 丢掉
    //      (20 条警告), 10 个既有测试的 wrapper.find 读空。
    //   v3(现在) 收起 .app-header 与侧边菜单 —— 没有覆盖就没有层级之争, 组件树不动。
    expect(EDITOR).toContain("document.body.classList.toggle('workflow-immersive'");
    expect(EDITOR).toContain('body.workflow-immersive .app-header { display: none !important; }');
    // ⛔ 两条走不通的老路不许回来
    expect(EDITOR).not.toContain('<Teleport');
    expect(EDITOR).not.toMatch(/is-immersive\s*\{[^}]*z-index/);
  });

  it('🔴 离开本页必须还原 —— 否则整个应用的顶栏和菜单会一直消失', () => {
    // body 上的 class 是**全局**副作用: 组件卸载了它还在, 后果比不全屏严重得多。
    expect(EDITOR).toContain('onUnmounted(() => applyImmersiveBodyClass(false))');
    // 挂载时也要按当前状态刷一次, 否则默认开的全屏第一次不生效
    expect(EDITOR).toContain('{ immediate: true }');
  });

  it('🔴 全屏盖住导航 ⇒ 出口必须点得到', () => {
    // 实测：出口按钮第一版 z-index:5，被工具栏里的兄弟元素盖住 ——
    // 全屏态下它是**唯一**的鼠标出口，点不到就是把用户关在里面。
    expect(EDITOR).toContain('data-testid="workflow-immersive-exit"');
    const rule = EDITOR.slice(EDITOR.indexOf('.workflow-immersive-exit, .workflow-immersive-enter {'));
    expect(rule).toMatch(/z-index:\s*(6[0-9]|[7-9][0-9]|[1-9][0-9]{2,})/);   // >= 60
    // 键盘出口也要有
    expect(EDITOR).toContain("if (event.key !== 'Escape'");
  });

  it('⛔ Esc 不许从弹窗手里抢走', () => {
    // 有弹窗/抽屉开着时那一下 Esc 是要关弹窗的，抢过来会让用户「关不掉弹窗，反而退了全屏」。
    const fn = EDITOR.slice(
      EDITOR.indexOf('function onImmersiveEscape'),
      EDITOR.indexOf('function onImmersiveEscape') + 600,
    );
    expect(fn).toContain('el-overlay');
    expect(fn).toContain('return');
  });

  it('⛔ 不用浏览器原生全屏 API', () => {
    // 它必须由用户手势触发（进页面自动调会被拒），而且 Esc 退出后应用不知道，状态会对不上。
    expect(EDITOR).not.toContain('requestFullscreen');
    expect(EDITOR).not.toContain('webkitRequestFullscreen');
  });

  it('记住用户的选择，且读不到时默认**开**', () => {
    // 「读不到 localStorage」不等于「用户关过」——默认 false 会让 owner 指定的
    // 「进页面直接全屏」在无痕模式/首次访问下静默失效。
    expect(EDITOR).toContain("getItem(IMMERSIVE_STORAGE_KEY) !== 'off'");
    expect(EDITOR).toContain('IMMERSIVE_STORAGE_KEY');
  });
});
