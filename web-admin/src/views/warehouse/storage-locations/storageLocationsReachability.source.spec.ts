import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 2026-08-25 生产验收发现的缺陷，逐条钉在源码上。
 *
 * 背景：PR#76 把「仓储空间 / 现场盘点 / 仓库平面图」发上了生产，Java、Web、OTA
 * 三方回执都是 deployed，但当天只读普查发现 `factory_feature_config` 里
 * `module_id = 'WAREHOUSE_LAYOUT'` **一行都没有** —— 全生产没有任何租户配置过。
 * 根因不是功能坏了，是**没有入口**：路由在、页面在、权限在，唯独 menuConfig
 * 里没有登记，只能手打 URL 才进得去（形态 B：机制在、没接上）。
 */
const MENU = readFileSync(resolve(__dirname, '../../../components/layout/menuConfig.ts'), 'utf8');
const PAGE = readFileSync(resolve(__dirname, 'index.vue'), 'utf8');

describe('仓储空间必须能被用户找到（BUG-1：上线即不可达）', () => {
  it('侧边栏仓储菜单里登记了 /warehouse/storage-locations', () => {
    expect(MENU).toContain("path: '/warehouse/storage-locations'");
  });

  it('阳性对照：这个闸确实在读那份真的菜单文件', () => {
    // 若路径写错读到空文件/别的文件，上面那条会因为「什么都不包含」而假绿地红，
    // 但反过来——闸读错文件时这条会先炸，指出是仪器问题而不是产品问题。
    expect(MENU).toContain("path: '/warehouse/stocktakes'");
    expect(MENU.length).toBeGreaterThan(2000);
  });
});

describe('弹窗不能卡死（BUG-2：保存后 150ms 内再点 + 就永久点不动）', () => {
  /**
   * 复现（生产实测，确定性）：点「保存」→ Element Plus 开始 leave 过渡 →
   * 150ms 内再点「+」→ 过渡被打断，`afterLeave` 不再触发，
   * `el-overlay-dialog` 永久停在 `is-closing`。此时 `elementFromPoint`
   * 落在遮罩上，保存/取消/× 全部失效，只能刷新页面。
   * 已用「摘掉 is-closing → 按钮恢复可点；加回去 → 又失效」的变异证明因果。
   *
   * 修法：每次打开都换 key，让 Vue 挂一个全新的 dialog 元素 ——
   * 全新元素不可能带着上一轮残留的 is-closing。
   */
  it.each(['zone', 'location', 'move', 'recognize'])('%s 弹窗带 :key，重新打开时重建元素', (name) => {
    expect(PAGE).toContain(':key="`' + name + '-${');
  });

  it('打开区域弹窗走 openZoneDialog()，不再直接把 visible 置 true', () => {
    expect(PAGE).toContain('@click="openZoneDialog"');
    expect(PAGE).not.toContain('@click="zoneDialogVisible = true"');
  });
});

describe('配置必须可逆（BUG-4/9：只能加不能改删）', () => {
  it('区域/位置/货架/楼层都有删除入口', () => {
    for (const fn of ['removeZone', 'removeLocation', 'removeRack', 'removeFloor']) {
      expect(PAGE, `${fn} 缺失`).toContain(`function ${fn}`);
      expect(PAGE, `${fn} 没有接到按钮上`).toContain(`${fn}(`);
    }
  });

  it('绑到货架层上的位置可以解绑', () => {
    // 原实现：绑定后该位置从**所有**下拉里消失（过滤 !item.rackId），
    // 而那一行只有一个「绑定位置」按钮 —— 绑错层就没有任何 UI 途径纠正。
    expect(PAGE).toContain('function unbindLocation');
    expect(PAGE).toContain('unbindLocation(location)');
  });

  it('删除是有护栏的，不做静默级联', () => {
    expect(PAGE).toContain('请先删除位置');
    expect(PAGE).toContain('请先把批次移走');
  });
});

describe('AI 识别不能未经确认就改配置（BUG-8）', () => {
  it('识别结果先进预览，确认后才写入', () => {
    expect(PAGE).toContain('recognizeDialogVisible');
    expect(PAGE).toContain('function applyRecognizedLayout');
    // 阴性对照：handleImageUpload 里不许再直接 persist
    const upload = PAGE.slice(PAGE.indexOf('async function handleImageUpload'), PAGE.indexOf('/** 识别结果里真正会被采纳的部分'));
    expect(upload).not.toContain('persist(');
    expect(upload.length).toBeGreaterThan(200);
  });
});

describe('图标按钮必须有可访问名（BUG-10）', () => {
  it('新增区域的圆形 + 按钮不再是一个没有名字的按钮', () => {
    expect(PAGE).toContain('aria-label="添加储存区域"');
  });
});
