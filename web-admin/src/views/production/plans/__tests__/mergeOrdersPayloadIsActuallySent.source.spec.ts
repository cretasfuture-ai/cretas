import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/production/plans/list.vue'), 'utf8',
);

/**
 * 测试同学 2026-08-03 报的「合并订单无法合并数量」(主10 关联5 → 计划只有 10)。
 *
 * ▎根因是最典型的一种「机制在、没接上」: 合并选择器一直在往
 * ▎`planForm.extraSourceOrderIds` 里塞订单, 而**没有任何请求体带上它** ——
 * ▎一个 write-only 的死状态。界面看起来完全正常(标签都显示出来了), 后端永远收不到。
 *
 * 这道闸就钉这一件事: 收集它的地方和发送它的地方必须同时存在。
 * 数量怎么汇总由后端的 ProductionPlanMergeQuantityContractTest 钉。
 */
/**
 * 剥掉注释再断言。
 * ⚠️ 实测踩过: 修复说明里写着 `extraSourceOrderIds`, 不剥的话把字段整行删掉这道闸**照样绿** ——
 * 它命中的是自己的注释。闸要量代码, 不能把说明文字也数进来。
 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

describe('生产计划: 合并订单必须真的发出去', () => {
  it('阳性对照: 合并选择器仍在收集 extraSourceOrderIds', () => {
    expect(source, '选择器没了 —— 本闸失去被测对象').toContain('planForm.extraSourceOrderIds.push(id)');
  });

  it('batch-from-so 的请求体必须带上 extraSourceOrderIds', () => {
    const callAt = source.indexOf('`/${factoryId.value}/production-plans/batch-from-so`');
    expect(callAt, 'batch-from-so 调用不见了').toBeGreaterThan(-1);

    // 该调用之前最近的那个 payload 构造块
    const payloadAt = source.lastIndexOf('const payload = {', callAt);
    expect(payloadAt, 'payload 构造块不见了').toBeGreaterThan(-1);
    const payload = stripComments(source.slice(payloadAt, callAt));

    expect(payload, '这里正是它被收集了却从不发送的地方')
      .toContain('extraSourceOrderIds');
    // 与它同一个 payload —— 确认锚点没漂到别的请求上
    expect(payload).toContain('sourceOrderId: planForm.value.sourceOrderId');
    expect(payload).toContain('itemIds: planForm.value.sourceOrderItemIds');
  });

  it('发送的是选择器收集的那个值, 不是写死的空数组', () => {
    const callAt = source.indexOf('`/${factoryId.value}/production-plans/batch-from-so`');
    const payloadAt = source.lastIndexOf('const payload = {', callAt);
    const payload = stripComments(source.slice(payloadAt, callAt));
    expect(payload).toMatch(/extraSourceOrderIds:[\s\S]{0,200}planForm\.value\.extraSourceOrderIds/);
  });
});
