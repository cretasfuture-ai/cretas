/**
 * 仓储「入库任务」的数据源 + 「提前关单」的回归闸（2026-08-20）。
 *
 * 两条都来自 Steve 真机走查：
 *
 * ① 「入库任务里不该有『质检中』」—— 根因不是状态映射不全（那是 M-23 的表层），
 *    而是**数据源选错了**：首页拉的是 `material_batches`（批次 = 入库**之后**产生的东西），
 *    拿它当待办，界面上自然会冒出批次生命周期状态。
 *    ⇒ 改成与「待收货任务」列表页同一个来源（按可收货状态拉采购单）。
 *
 * ② 「少于预计数量时要能提前关单」—— 后端在 M-14/M-15 那轮就做好了
 *    （`POST .../receiving/tasks/{id}/close-short`），App 侧一直没接。
 *
 * ⚠️ 口径：源码断言（本目录既有惯例），看不见运行时。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

/** ⛔ 剥注释再扫 —— 否则闸会把自己的说明当成被测对象（本仓当天栽过四次）。 */
const strip = (src: string) =>
  src
    .replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const home = strip(read('screens/warehouse/home/WHHomeScreen.tsx'));
const receipt = strip(read('screens/warehouse/inbound/WHReceiptCreateScreen.tsx'));
const client = strip(read('services/api/warehouseReceivingApiClient.ts'));

describe('① 入库任务的数据源', () => {
  it('前提：确实读到了首页源码', () => {
    expect(home).toContain('renderInboundCard');
  });

  it('首页不再把物料批次当作「入库任务」', () => {
    expect(home).not.toMatch(/getMaterialBatches\(\{[^)]*\}\)\s*,\s*$/m);
    // 批次生命周期状态不该再参与任务判定
    expect(home).not.toMatch(/mapBatchStatus\s*\(/);
  });

  it('改用与「待收货任务」列表页同一个来源', () => {
    expect(home).toContain('RECEIVABLE_STATUSES');
    expect(home).toContain('getOrdersByStatus');
  });

  it('🔴 可收货状态与列表页保持同一口径（两处漂开 = 首页计数和列表对不上）', () => {
    const listScreen = strip(read('screens/warehouse/inbound/WHPurchaseReceiveListScreen.tsx'));
    const grab = (src: string) =>
      (src.match(/RECEIVABLE_STATUSES[^=]*=\s*\[([\s\S]*?)\]/)?.[1] ?? '')
        .split(',')
        .map((x) => x.trim().replace(/['"]/g, ''))
        .filter(Boolean)
        .sort();
    const a = grab(home);
    const b = grab(listScreen);
    expect(a.length).toBeGreaterThan(0);      // 仪器自检：真抓到了
    expect(a).toEqual(b);
  });

  it('已收满的单子不算待办', () => {
    expect(home).toMatch(/filter\(\(task\) => task\.quantity > 0\)/);
  });
});

describe('② 提前关单', () => {
  it('client 接上了后端那条 close-short', () => {
    expect(client).toContain('closePurchaseReceivingTask');
    expect(client).toContain('/close-short');
  });

  it('原因码与后端/web 同一套五个', () => {
    for (const code of ['SUPPLIER_SHORT_SHIPMENT', 'QUALITY_REJECTION',
      'PURCHASE_BALANCE_CANCELLED', 'DEMAND_CHANGED', 'OTHER']) {
      expect(client).toContain(code);
    }
  });

  it('按钮只在短收时出现（收满/超收时后端自动关，显示它只会让人误按）', () => {
    expect(receipt).toContain('isShortReceipt');
    expect(receipt).toMatch(/\{isShortReceipt && \(/);
  });

  it('选「其他原因」必须填说明', () => {
    expect(receipt).toMatch(/closeReason === 'OTHER'[\s\S]{0,120}closeNotes/);
  });

  it('🔴 ⛔ 不许用 Alert.prompt —— 它是 iOS 独有的，仓管用安卓', () => {
    /*
     * 本仓已经为这一条建过闸（warehouseDeadEndsContract：「手动输入」用了 Alert.prompt，
     * 工人的安卓上只会弹「当前平台不支持」）。
     * ⚠️ 这次实现时我**又差点写成 Alert.prompt**，所以在这里再钉一遍。
     */
    expect(receipt).not.toContain('Alert.prompt');
    // 正面：用 Paper 的 Dialog，两端一致
    expect(receipt).toContain('<Dialog');
    expect(receipt).toContain('RadioButton.Group');
  });
});
