import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detail = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
const createPage = readFileSync(resolve(import.meta.dirname, '../new.vue'), 'utf8');
const listPage = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const autoChildHelper = readFileSync(resolve(import.meta.dirname, '../deliveryAutoChild.ts'), 'utf8');

describe('sales delivery mode contract', () => {
  it('offers explicit one-time full and partial modes', () => {
    expect(detail).toContain('deliveryMode: \'FULL\'');
    expect(detail).toContain('value="FULL"');
    expect(detail).toContain('value="PARTIAL"');
    expect(createPage).not.toContain('deliveryMode');
  });

  // 缺陷40 (#492 follow-up): 「建子发运单」这一步抽成共享 helper
  // (deliveryAutoChild.ts::createAutoFullChildShipment), detail.vue 与 list.vue 都调它 ——
  // 不许各留一份字面量 (硬约束 8 / 形態 D)。字面串因此从 detail.vue 挪到了 helper 里,
  // 断言也跟着挪; detail.vue 只需证明它仍然只在 FULL 模式下调用这个 helper。
  it('creates one child shipment for full mode through the shared auto-child helper', () => {
    expect(autoChildHelper).toContain('/sales/deliveries/${options.masterId}/shipments');
    expect(autoChildHelper).toContain('web-full-shipment-${options.masterId}');
    expect(detail).toContain('母单只负责计划');
    // 🔴 用 'createAutoFullChildShipment' (无括号) 只证明"这个名字在文件里出现过" ——
    // import 这一行本身就满足, 变异测试实测就算把调用点整段删掉这条断言也不会红 (断言在守空气,
    // 见 measurement-and-wiring.md 硬约束 C″)。必须认调用形状 `createAutoFullChildShipment({`,
    // 这个子串只在真正调用 (对象字面量入参) 时出现, import 语句里不会长这样。
    expect(detail).toContain('createAutoFullChildShipment({');
  });

  // 缺陷40: list 页「创建发货单」原来只建母单、没有全量子单 (#492 报告的两个前端入口之一漏掉了
  // 这一步) —— 现在两个入口共享同一个 helper, 而不是 list.vue 自己再抄一份端点字面量。
  it('list page reaches the same auto-child helper as detail page (缺陷40)', () => {
    // 同上: 必须断言调用形状, 不能只断言"这个词出现过"(import 那一行就会让这句话恒真)。
    expect(listPage).toContain('createAutoFullChildShipment({');
    expect(listPage).toContain('isFullQuickDeliveryPlan(');
    expect(listPage).not.toContain('/shipments`,');
  });

  // 负向对照: PARTIAL 模式不应该触发自动建子单 —— detail.vue 的调用点必须仍然落在
  // `deliveryMode === 'FULL'` 判断之后 (且只出现这一处, 没有第二条脱离守卫的调用),
  // 否则「只在 FULL 模式生效」这句话就是一句没人守着的注释。
  it('detail page only reaches the auto-child helper inside the FULL-mode branch (negative control for PARTIAL)', () => {
    const fullGuardIndex = detail.indexOf('deliveryForm.value.deliveryMode === \'FULL\')');
    const callIndex = detail.indexOf('createAutoFullChildShipment({');
    expect(fullGuardIndex).toBeGreaterThan(-1);
    expect(callIndex).toBeGreaterThan(fullGuardIndex);
    expect(detail.split('createAutoFullChildShipment({').length - 1).toBe(1);
  });

  // list 页没有 detail.vue 那种显式单选, FULL 判定靠 isFullQuickDeliveryPlan(items) 这道
  // 运行时闸 (真实行为已在 deliveryAutoChild.spec.ts 里用「改小一行数量 -> false」验证过) ——
  // 这里只钉调用顺序: 建子单调用必须落在这道闸判断之后, 不能绕过它。
  it('list page gates the auto-child helper behind isFullQuickDeliveryPlan (negative control for reduced quantities)', () => {
    const gateIndex = listPage.indexOf('isFullQuickDeliveryPlan(items)');
    const callIndex = listPage.indexOf('createAutoFullChildShipment({');
    expect(gateIndex).toBeGreaterThan(-1);
    expect(callIndex).toBeGreaterThan(gateIndex);
  });
});
