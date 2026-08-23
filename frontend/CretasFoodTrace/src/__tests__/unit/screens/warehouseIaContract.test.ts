/**
 * R-3 · App 仓储信息架构重构的回归闸（2026-08-20）。
 *
 * 设计卡: `docs/superpowers/specs/2026-08-20-r3-app-warehouse-ia.md`
 * 台账:   `HANDOFF-2026-08-20-手动测试发现台账.md` → R-3
 *
 * ⚠️ 口径（代理判据，明写出来）：沿用本目录既有惯例 —— **读源码做契约**。
 * 它看不见运行时行为，只能钉住「源码里那个形状还在不在」。
 * 每条断言都在实现时用变异对照验过能红，⛔ 不是写完看它绿就算数。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

/** 仓管员真正会看到的那些文件（⛔ 不含其它模块 —— 见「故意不改的范围」）。 */
const WAREHOUSE_SURFACE = [
  'screens/warehouse',
  'navigation/warehouse',
  'navigation/WarehouseManagerNavigator.tsx',
  'navigation/WarehouseManagerTabNavigator.tsx',
  'navigation/WarehouseWorkerNavigator.tsx',
  'i18n/locales/zh-CN/warehouse.json',
  'i18n/locales/zh-CN/home.json',
];

function walk(rel: string): string[] {
  const abs = path.resolve(sourceRoot, rel);
  if (!fs.existsSync(abs)) return [];
  if (fs.statSync(abs).isFile()) return [abs];
  return fs.readdirSync(abs).flatMap((child) => walk(path.join(rel, child)));
}

const surfaceFiles = WAREHOUSE_SURFACE.flatMap(walk).filter((f) =>
  ['.ts', '.tsx', '.json'].includes(path.extname(f)),
);

/**
 * 剥掉注释再判断。
 *
 * 🔴 这个 helper 是被自己的闸抓出来才加的：第一版直接扫全文，于是
 *   · 「不再出现『出货』」被**我自己引用 Steve 原话的那句注释**判红；
 *   · 「不许放去调拨」被**我自己写的「⛔ 故意不放去调拨按钮」那条注释**判红。
 *
 * 两次都是同一个形状：**闸把自己的文档当成了被测对象**（本仓已记过多次）。
 * ⚠️ 而注释里保留用户原话和「为什么故意不做」是有价值的，不该为了让闸变绿去删。
 * ⇒ 该改的是尺子：量代码和用户可见文案，⛔ 不量注释。
 */
const stripComments = (src: string) =>
  src
    .replace(/\/\*[\s\S]*?\*\//g, '')       // 块注释 + JSX 的 {/* ... */}
    .replace(/(^|[^:])\/\/.*$/gm, '$1');    // 行注释（避开 https:// 里的双斜杠）

const occurrences = (word: string) =>
  surfaceFiles.reduce(
    (n, f) => n + (stripComments(fs.readFileSync(f, 'utf8')).split(word).length - 1),
    0,
  );

describe('R-3 · 仓储 App 信息架构', () => {
  it('前提：扫到的文件数是合理的（⛔ 否则下面每条「没有出现」都是恒真式）', () => {
    // 🔴 这条是仪器自检。没有它，只要路径写错，后面所有 not.toContain 都会因为
    //    「一个文件都没读到」而全绿 —— 那是最经典的假绿。
    expect(surfaceFiles.length).toBeGreaterThan(30);
    // 阳性对照：这批文件里必然有「入库」二字，读得到才说明内容真的被读进来了
    expect(occurrences('入库')).toBeGreaterThan(0);
  });

  describe('① 术语统一：发货 / 出货 是一件事', () => {
    it('仓管员看得到的地方不再出现「出货」', () => {
      expect(occurrences('出货')).toBe(0);
    });

    it('🔴 阴性对照：「出库」一处不许被顺手替换 —— 它是另一个概念', () => {
      /*
       * 出库 = 批次离开仓库这个**库存动作**（领料出库 / 扫码出库 / FIFO 优先出库 / 出库数量）
       * 发货 = 对着销售订单**发给客户**
       *
       * 把它们合并会让「领料出库」被读成「发货」—— 那是货真价实的误操作，
       * 比术语不统一严重得多。这条断言存在的意义就是拦住「统一用词」时的顺手替换。
       */
      // 2026-08-20 实测 47 处（剥注释后的代码/文案计数；含注释是 70）。
      // 阈值取 40 而不是钉死 47：正常增删几处不该弄红这道闸，
      // 而「顺手把出库也替换成发货」会让它掉到 0 左右 —— 那正是要拦的。
      expect(occurrences('出库')).toBeGreaterThan(40);
      expect(read('screens/warehouse/shared/WHScanOperationScreen.tsx')).toContain('出库');
    });

    it('旧 SH-* 链路靠**显式限定语**区分，⛔ 不靠近义词', () => {
      /*
       * 「手工出货登记」原本是用「出货 vs 发货」这两个近义词去承载
       * 「不扣库存 vs 扣库存」这个安全边界 —— 用户根本读不出来。
       * 统一用词之后，这个区分必须由限定语本身说清楚。
       */
      const outboundList = read('screens/warehouse/outbound/WHOutboundListScreen.tsx');
      expect(outboundList).toContain('手工发货登记');
      const confirmList = read('screens/warehouse/outbound/WHDeliveryConfirmListScreen.tsx');
      expect(confirmList).toMatch(/不扣库存|旧流程/);
    });
  });

  describe('② 首页只做业务分流', () => {
    const home = read('screens/warehouse/home/WHHomeScreen.tsx');

    it('不加载统计和任务，只提供入库、发货两个大入口', () => {
      expect(home).toContain('testID="wh-home-inbound"');
      expect(home).toContain('testID="wh-home-outbound"');
      expect(home).not.toContain('statsGrid');
      expect(home).not.toContain('dashboardAPI');
      expect(home).not.toContain('shipmentApiClient');
    });
  });

  describe('③ 发货被挡住时的出路', () => {
    const confirm = stripComments(read('screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx'));

    /*
     * 🔴 2026-08-20 这一节**翻过面**，记一笔：
     *
     * 原来它守的是「未分配批次那张红卡里要有一个原地可点的动作」——
     * 因为界面只留了一句「请去网页端分配」，而仓管在货堆边。
     *
     * 后来 owner 拍板把「批次」整个从 App 拿掉（原话：「批次他们自己都管不过来」，
     * 追溯改按订单号做）⇒ 那张卡连同它的按钮一起没了，这几条断言随之失效。
     *
     * ⚠️ 形态 C‴：断言好好地守着一个**现在判定为多余**的行为。需求变了就改断言，
     * ⛔ 但不能删掉了事 —— 翻成阴性对照，钉住这条死路不许回来。
     */
    it('⛔ 不再拿「批次没分配」挡住发货', () => {
      expect(confirm).not.toContain('未分配');
      expect(confirm).not.toContain('分配批次');
    });

    it('⛔ 也不再把人指向手机上做不到的地方', () => {
      expect(confirm).not.toMatch(/网页端/);
    });

    it('阳性对照：这一屏仍然在做它该做的事（否则上面两条是恒真的）', () => {
      expect(confirm).toContain('确认并扣库存');
      expect(confirm).toContain('canSubmitRows');
    });
  });

  describe('④ 首页减负', () => {
    const home = read('screens/warehouse/home/WHHomeScreen.tsx');

    it('不再渲染「库存状态」业务流程图', () => {
      // 判据是「不再引入这个组件」——注释里提到它是允许的（那是历史说明）
      expect(home).not.toMatch(/import\s*\{[^}]*WorkflowVisualizer[^}]*\}\s*from/);
      expect(home).not.toMatch(/<WorkflowVisualizer/);
    });

    it('🔴 阴性对照：组件本身保留，其它角色还在用', () => {
      const visualizer = path.resolve(sourceRoot, 'components/workflow/WorkflowVisualizer.tsx');
      expect(fs.existsSync(visualizer)).toBe(true);
    });
  });

  describe('M-21 · 首页不再显示无法保证口径的统计', () => {
    const home = read('screens/warehouse/home/WHHomeScreen.tsx');

    it('旧的伪统计与聚合状态全部不可达', () => {
      expect(home).not.toMatch(/todayOutbound:\s*overview\.todayStats\?\.ordersCompleted/);
      expect(home).not.toMatch(/pendingOutbound:\s*overview\.summary\?\.activeBatches/);
      expect(home).not.toContain('setPendingOutboundCount');
      expect(home).not.toContain('alertCount');
    });
  });
});
