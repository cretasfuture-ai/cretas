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
    //
    // 2026-09-01: 原用 'path: \'/warehouse/stocktakes\'' 当锚点。LIUSHANMEN
    // 仓储「库存盘点」菜单收拢 (owner 拍板) 把它与另外 5 条合并进 2 个 hub
    // 入口，这个字面量已经从 menuConfig.ts 里消失 (旧路径仍可访问，只是改成了
    // router redirect，不再是菜单项，见 menuConfig.spec.ts 的收拢说明)。换一个
    // 不受这次合并影响的锚点。
    expect(MENU).toContain("path: '/warehouse/materials'");
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
    const upload = PAGE.slice(PAGE.indexOf('async function handleImageUpload'), PAGE.indexOf('/** AI 不可用时'));
    expect(upload).not.toContain('persist(');
    expect(upload.length).toBeGreaterThan(200);
    expect(PAGE).toContain('@click="applyBackgroundOnly"');
  });
});

describe('图标按钮必须有可访问名（BUG-10）', () => {
  it('新增区域的圆形 + 按钮不再是一个没有名字的按钮', () => {
    expect(PAGE).toContain('aria-label="添加储存区域"');
  });
});

/**
 * 2026-08-28 仓库平面图设计器 —— 「接上没有」的源码闸。
 *
 * ⚠️ 这一层守的是**接线**，不是行为：
 *   · 几何算术由 warehouseFloorPlan.spec.ts 验；
 *   · 指针交互由 WarehouseFloorOverlay.spec.ts 用真实 DOM 事件驱动验；
 *   · 「页面真的把叠加层接进画布、真的把权限传下去」只有读源码看得见 ——
 *     组件自己的单测对「谁调它」是盲的。
 */
const OVERLAY = readFileSync(resolve(__dirname, 'WarehouseFloorOverlay.vue'), 'utf8');

describe('全屏编辑不许再引入第二个 el-dialog 过渡（BUG-2 的同一条通道）', () => {
  it('全屏是「同一块画布加一个 class」，不是弹窗', () => {
    expect(PAGE).toContain("'is-fullscreen': mapFullscreen");
    expect(PAGE).toContain('function openMapFullscreen');
    expect(PAGE).toContain('function closeMapFullscreen');
  });

  it('弹窗数量没有增加 —— 仍然是原来那 4 个，每个都带 :key', () => {
    // 原来 4 个：zone / location / move / recognize。多出第 5 个就是又开了一条
    // 「leave 过渡被打断 → el-overlay-dialog 卡在 is-closing」的通道。
    expect((PAGE.match(/<el-dialog/g) ?? []).length).toBe(4);
    expect((PAGE.match(/<el-dialog :key=/g) ?? []).length).toBe(4);
  });

  it('全屏时锁住页面滚动，并且离开页面一定还回去', () => {
    expect(PAGE).toContain("document.body.style.overflow = open ? 'hidden' : ''");
    // 全屏时直接路由走掉的话，不还回去整站都滚不动了
    const unmount = PAGE.slice(PAGE.indexOf('onUnmounted(() => {'), PAGE.indexOf('</script>'));
    expect(unmount).toContain("document.body.style.overflow = ''");
    expect(unmount.length).toBeGreaterThan(80);
  });
});

describe('叠加层真的接在画布上（形态 B：机制在、没接上）', () => {
  it('WarehouseFloorOverlay 挂在 VueFlow 里面，并且拿到了两层数据', () => {
    expect(PAGE).toContain('<WarehouseFloorOverlay');
    expect(PAGE).toContain(':floor="activeFloor"');
    expect(PAGE).toContain('@zone-shape="applyZoneShape"');
    expect(PAGE).toContain('@annotation-add="addAnnotation"');
    expect(PAGE).toContain('@text-request="requestAnnotationText"');
  });

  it('可编辑性 = 有权限 且 全屏 —— 缩略图状态下叠加层不吃指针事件', () => {
    expect(PAGE).toContain(':interactive="canDesignMap"');
    expect(PAGE).toContain('const canDesignMap = computed(() => canEditLayout.value && mapFullscreen.value)');
    // 阴性对照：权限判据本身没被放宽
    expect(PAGE).toContain("['factory_super_admin', 'warehouse_manager'].includes(auth.currentRole)");
  });

  it('Controls 必须排在叠加层之后 —— 两者同为 z-index:5，后写的在上', () => {
    const overlayAt = PAGE.indexOf('<WarehouseFloorOverlay');
    const controlsAt = PAGE.indexOf('<Controls');
    expect(overlayAt).toBeGreaterThan(-1);
    expect(controlsAt).toBeGreaterThan(-1);
    // 顺序反了的话，落笔状态下缩放按钮会被落笔面盖住，点不到
    expect(controlsAt).toBeGreaterThan(overlayAt);
  });

  it('叠加层的 z 分层写死在组件里：区域在货架下、标注在货架上', () => {
    expect(OVERLAY).toContain('.floor-overlay--base{z-index:auto}');
    expect(OVERLAY).toContain('.floor-overlay--top{z-index:5}');
  });
});

describe('三种拖拽共用同一个 600ms 保存节流（persist 打的是整份 layout）', () => {
  it('货架拖拽的节流没有被这次改动摘掉', () => {
    expect(PAGE).toContain('createTrailingDebouncer(600)');
    const rackDrag = PAGE.slice(PAGE.indexOf('function onRackDrag'), PAGE.indexOf('async function bindLocation'));
    expect(rackDrag).toContain('layoutPersistDebouncer.schedule');
    expect(rackDrag.length).toBeGreaterThan(120);
  });

  it('区域几何与标注的拖拽走的是同一个调度器，⛔ 不是各自新建一个', () => {
    // 各配一个防抖器会在同一个 600ms 窗口里打出三份整份 PUT。
    expect((PAGE.match(/createTrailingDebouncer\(/g) ?? []).length).toBe(1);
    for (const fn of ['applyZoneShape', 'addAnnotation', 'updateAnnotation']) {
      const body = PAGE.slice(PAGE.indexOf(`function ${fn}(`));
      expect(body.slice(0, 900), `${fn} 没有接到保存节流上`).toContain('layoutPersistDebouncer.schedule');
    }
  });
});

describe('区域几何是可逆的，并且删它的时候要说清删的是什么', () => {
  it('删区域时它在每一层图上的面积都跟着删，不留孤块', () => {
    const removeZone = PAGE.slice(PAGE.indexOf('async function removeZone'), PAGE.indexOf('async function removeLocation'));
    expect(removeZone).toContain('floor.zoneShapes = floorZoneShapes(floor).filter');
    expect(removeZone.length).toBeGreaterThan(200);
  });

  it('从图上移除区域几何 ≠ 删除区域，确认文案必须讲明白', () => {
    expect(PAGE).toContain('区域本身和它下面的储位都保留');
  });

  it('删楼层会带走图上的面积和标注 —— 说出来，⛔ 不做无声的连带删除', () => {
    const removeFloor = PAGE.slice(PAGE.indexOf('async function removeFloor'), PAGE.indexOf('async function moveBatch'));
    expect(removeFloor).toContain('块区域面积');
    expect(removeFloor).toContain('条标注');
  });
});

describe('新增的按钮都有可访问名（BUG-10 同口径）', () => {
  it('缩略图全屏入口有可访问名，并按权限说编辑或查看', () => {
    expect(PAGE).toContain("const mapFullscreenLabel = computed(() => canEditLayout.value ? '全屏编辑' : '全屏查看')");
    expect(PAGE).toContain('const mapFullscreenAriaLabel = computed(() => `${mapFullscreenLabel.value}平面图`)');
    expect(PAGE).toContain(':aria-label="mapFullscreenAriaLabel"');
    expect(PAGE).not.toContain('aria-label="全屏编辑平面图"');
  });

  it('颜色色块有名字', () => {
    expect(PAGE).toContain(':aria-label="`标注颜色 ${color}`"');
  });
});
