import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { classifySettlementFailure, isNotSettledPayload } from '../settlementStatusFailure';

const HERE = dirname(fileURLToPath(import.meta.url));
const LIST_VIEW = resolve(HERE, '../list.vue');
const API = resolve(HERE, '../../../../api/productionPlan.ts');

describe('classifySettlementFailure —— 合法空态 vs 真失败', () => {
  it('🔴 404 = 该计划没有仓库确认单(存货生产走小结) —— 合法状态, ⛔ 不是错误', () => {
    expect(classifySettlementFailure({ status: 404, message: '该生产计划尚未结单, 不能确认入库' }))
      .toEqual({ kind: 'absent' });
  });

  it('其余状态码仍是真失败, 并保留原始信息', () => {
    expect(classifySettlementFailure({ status: 500, message: '系统处理异常' }))
      .toEqual({ kind: 'error', message: '系统处理异常' });
    expect(classifySettlementFailure({ status: 403, message: '权限不足' }))
      .toEqual({ kind: 'error', message: '权限不足' });
  });

  it('⛔ 认 status 不认 code —— 拦截器把后端 errorCode 换成了 NOT_FOUND, 认 code 永远匹配不上', () => {
    // 拦截器实际抛的形状: new ApiError(friendly, 'NOT_FOUND', 404)
    expect(classifySettlementFailure({ code: 'NOT_FOUND', status: 404, message: 'x' }))
      .toEqual({ kind: 'absent' });
    // 只有 code 没有 status 的东西不算 —— 那不是这个接口的 404
    expect(classifySettlementFailure({ code: 'PRODUCTION_SETTLEMENT_NOT_FOUND', message: 'y' }))
      .toEqual({ kind: 'error', message: 'y' });
  });

  it('拿不到消息时给可执行兜底, ⛔ 不显示 undefined', () => {
    expect(classifySettlementFailure({ status: 500 }))
      .toEqual({ kind: 'error', message: '入库状态加载失败，请刷新后重试' });
    expect(classifySettlementFailure(null))
      .toEqual({ kind: 'error', message: '入库状态加载失败，请刷新后重试' });
    expect(classifySettlementFailure(undefined))
      .toEqual({ kind: 'error', message: '入库状态加载失败，请刷新后重试' });
    expect(classifySettlementFailure({ status: 500, message: '   ' }))
      .toEqual({ kind: 'error', message: '入库状态加载失败，请刷新后重试' });
  });
});

describe('isNotSettledPayload —— 后端如实回答「没有仓库确认单」', () => {
  it('settled === false 才算没有', () => {
    expect(isNotSettledPayload({ settled: false, productionPlanId: 'p1' })).toBe(true);
  });

  it('有结单时不算', () => {
    expect(isNotSettledPayload({ settled: true, postingStatus: 'POSTED' })).toBe(false);
  });

  it('🔴 老后端不返回这个字段(undefined) —— ⛔ 不许判成「没有结单」', () => {
    // 写成 `!data.settled` 会把每一条都判成没有结单, 整列变成「无仓库确认单」。
    // 本仓形态: 兜底默认值把「我不知道」翻译成一个确定答案。
    expect(isNotSettledPayload({ postingStatus: 'PENDING_WAREHOUSE_RECEIPT' })).toBe(false);
    expect(isNotSettledPayload({ settled: undefined })).toBe(false);
    expect(isNotSettledPayload({ settled: null })).toBe(false);
  });

  it('非对象一律不算', () => {
    expect(isNotSettledPayload(null)).toBe(false);
    expect(isNotSettledPayload(undefined)).toBe(false);
    expect(isNotSettledPayload('settled')).toBe(false);
  });
});

/**
 * 接线断言 —— 只测 helper 不算数。
 *
 * ⚠️ 局限说清楚: 读的是 `list.vue` 源码, 不是挂载后的行为。该视图 4000+ 行、
 * 依赖大量 API, 挂载成本远高于本次改动。它挡得住「批量拉状态又开始弹全局红条」
 * 和「合法空态又被标成加载失败」, 挡不住渲染层被别的东西盖掉。
 */
describe('接线: 列表批量拉状态不许再弹全局红条', () => {
  const view = readFileSync(LIST_VIEW, 'utf8');
  const api = readFileSync(API, 'utf8');

  it('批量拉取必须传 _silent —— 它是尽力而为的增强, 失败已经逐行接住了', () => {
    expect(view).toContain("getProductionSettlement(factoryId.value, planId, { _silent: true })");
  });

  it('API 层必须允许透传 config, 否则 _silent 传不进去', () => {
    expect(api).toContain('config?: object');
    expect(api).toContain('/production-plans/${planId}/settlement`, config)');
  });

  it('⛔ 阴性对照: 404 那一支不许再走「加载失败」文案', () => {
    expect(view).toContain('isSettlementAbsent(row)');
    expect(view).toContain('无仓库确认单');
    // 「加载失败」本身要保留 —— 真失败仍然要说出来
    expect(view).toContain('加载失败');
  });

  it('三个调用点都要接住 settled=false —— ⛔ 少一个就会打开空对话框', () => {
    // 批量拉取 / 确认入库对话框 / 中转挂账对话框
    expect((view.match(/isNotSettledPayload\(/g) || []).length).toBe(3);
  });

  it('阳性对照: 锚点确实存在 —— 否则上面几条是恒真的', () => {
    expect(view).toContain('function loadSettlementStatuses');
    expect(view).toContain('settlementStatusAbsentMap');
    expect(view).toContain('function handleWarehouseReceipt');
  });
});
