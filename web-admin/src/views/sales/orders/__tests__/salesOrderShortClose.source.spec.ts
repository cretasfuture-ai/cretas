import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * T-SHORT-SHIP (owner 2026-09-04 拍板): 短交必须同时支持「补发」和「关单/短交结案」两条路，
 * 且每一条都必须是显式确认点击 (⛔ 不许任何一条隐式发生)。
 *
 * detail.vue 是 3000+ 行的巨型 SFC —— 挂载测整棵组件树成本很高, 这里跟随
 * salesOrderDeliveryMode.source.spec.ts 已验证过的模式: 直接对源码字符串做「形状」断言,
 * 而不是「出现过」断言 (measurement-and-wiring 硬约束 C″/形态 C — 变异要打在被守的行为上,
 * 不是随手改一行字面量)。
 */
const detail = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
const guards = readFileSync(resolve(import.meta.dirname, '../salesOrderGuards.ts'), 'utf8');

describe('短交结案 — 两个按钮都是显式点击, 没有默认/自动的那一条', () => {
  it('补发和结案两个按钮共用同一个门禁 showShortShipmentActions', () => {
    const reshipButton = detail.indexOf('@click="handleReshipOutstanding"');
    const closeButton = detail.indexOf('@click="openShortCloseDialog"');
    expect(reshipButton).toBeGreaterThan(-1);
    expect(closeButton).toBeGreaterThan(-1);

    // 各自往前找最近的 v-if, 必须都是 showShortShipmentActions —— 不能有一个按钮
    // 悄悄换成别的判据 (那样两条路径就不再"同时出现"了)。
    const reshipVIf = detail.lastIndexOf('v-if="showShortShipmentActions"', reshipButton);
    const closeVIf = detail.lastIndexOf('v-if="showShortShipmentActions"', closeButton);
    expect(reshipVIf).toBeGreaterThan(-1);
    expect(closeVIf).toBeGreaterThan(-1);
    // 两个按钮各自最近的 v-if 之间不能隔着别的 el-button (否则门禁挂到了别的元素上)。
    expect(detail.slice(reshipVIf, reshipButton)).not.toContain('</el-button>');
    expect(detail.slice(closeVIf, closeButton)).not.toContain('</el-button>');
  });

  it('门禁本身要求 PARTIAL_DELIVERED 且实发缺口 > 0 —— 两个条件都不能少', () => {
    const gateIndex = detail.indexOf('const showShortShipmentActions = computed(');
    expect(gateIndex).toBeGreaterThan(-1);
    const gateBody = detail.slice(gateIndex, detail.indexOf(';', gateIndex) + 1);
    expect(gateBody).toContain("order.value?.status === 'PARTIAL_DELIVERED'");
    expect(gateBody).toContain('shortShipment.value.outstanding >');
  });

  it('缺口用【实发】口径 shortShipmentSummary, 不是【安排量】口径 deliveryCapacityByLine', () => {
    // 阴性对照: shortShipment 这个 computed 不应该依赖 deliveryCapacity —— 母单占满
    // arranged 不代表已经发出去了 (见 salesOrderGuards.spec.ts 的实拍样本)。
    const summaryIndex = detail.indexOf('const shortShipment = computed(');
    expect(summaryIndex).toBeGreaterThan(-1);
    const summaryBody = detail.slice(summaryIndex, detail.indexOf('));', summaryIndex));
    expect(summaryBody).toContain('shortShipmentSummary(');
    expect(summaryBody).not.toContain('deliveryCapacity');
  });

  it('「补发剩余」复用新建分批发运对话框 (openShipmentDialog), 不是另起一套', () => {
    const handlerIndex = detail.indexOf('function handleReshipOutstanding()');
    expect(handlerIndex).toBeGreaterThan(-1);
    const handlerBody = detail.slice(handlerIndex, detail.indexOf('\n}', handlerIndex));
    expect(handlerBody).toContain('pendingReshipmentMaster(');
    expect(handlerBody).toContain('openShipmentDialog(target)');
    // 找不到可复用母单时要有退路, 不能什么都不做假装点了
    expect(handlerBody).toContain('openDeliveryDialog()');
  });

  it('「短交结案」提交按钮在勾选确认前必须 disabled —— 不许仅靠 warning 消息拦', () => {
    const dialogIndex = detail.indexOf('v-model="shortCloseDialogVisible"');
    expect(dialogIndex).toBeGreaterThan(-1);
    const dialogEnd = detail.indexOf('</el-dialog>', dialogIndex);
    const dialogBody = detail.slice(dialogIndex, dialogEnd);
    expect(dialogBody).toContain('v-model="shortCloseAcknowledged"');
    expect(dialogBody).toContain(':disabled="!shortCloseAcknowledged"');
    expect(dialogBody).toContain('@click="submitShortClose"');
  });

  it('submitShortClose 在真正调用后端前, 代码路径上要先校验 shortCloseAcknowledged', () => {
    const fnIndex = detail.indexOf('async function submitShortClose()');
    expect(fnIndex).toBeGreaterThan(-1);
    const fnEnd = detail.indexOf('\n}', detail.indexOf('finally {', fnIndex));
    const fnBody = detail.slice(fnIndex, fnEnd);
    const guardIndex = fnBody.indexOf('shortCloseAcknowledged.value');
    const postIndex = fnBody.indexOf('close-short-shipment');
    expect(guardIndex).toBeGreaterThan(-1);
    expect(postIndex).toBeGreaterThan(-1);
    expect(guardIndex).toBeLessThan(postIndex);
  });

  it('salesOrderGuards 导出的补发目标查找函数排除已取消/已退回的子单', () => {
    const fnIndex = guards.indexOf('export function masterDeliveryRemainingByLine(');
    expect(fnIndex).toBeGreaterThan(-1);
    const fnBody = guards.slice(fnIndex, guards.indexOf('\n}', fnIndex));
    expect(fnBody).toContain("'CANCELLED', 'RETURNED'");
  });
});
