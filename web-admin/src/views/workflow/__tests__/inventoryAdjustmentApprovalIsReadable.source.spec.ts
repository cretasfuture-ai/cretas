import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 2026-08-25 生产实测: 走通库存盘点数量审批时发现 ——
 * 厂长在 OA 待办里看到的是
 *
 *     业务类型  库存调整审批
 *     业务单号  INVENTORY_ADJUSTMENT 5a5f4e93-a8ac-461d-8e03-53f14952edae
 *     往来单位  —      金额  —
 *
 * 点「详情」得到「「库存调整审批」暂未接入单据详情页」, 而「通过」前的确认框标题
 * 写着「**通过前请确认单据信息**」, 展示的却还是那串 UUID。
 *
 * ⇒ 系统在要求审批人**在完全看不到批了什么的情况下**批准一次真实的库存数量调整。
 *   这多半也是这条链路上线后一次都没被走通过的原因之一。
 */
const PENDING = readFileSync(resolve(__dirname, '../pending.vue'), 'utf8');
const STOCKTAKES = readFileSync(resolve(__dirname, '../../warehouse/stocktakes/index.vue'), 'utf8');

describe('库存调整审批必须让审批人看得见自己在批什么', () => {
  it('「详情」接进了盘点单据, 不再回落到「暂未接入单据详情页」', () => {
    expect(PENDING).toContain('INVENTORY_ADJUSTMENT: `/warehouse/stocktakes');
  });

  it('阳性对照: 闸读到的确实是那份路由表, 既有模块仍在', () => {
    // 读错文件/读到空串时, 上面那条会因「什么都不包含」而红得莫名其妙;
    // 这条先炸, 指出是仪器问题而不是产品问题。
    expect(PENDING).toContain('PURCHASE_ORDER: `/procurement/orders/');
    expect(PENDING).toContain('SALES_ORDER: `/sales/orders/');
    expect(PENDING.length).toBeGreaterThan(3000);
  });

  it('深链带的单号在盘点页真的被消费, ⛔ 不是一个被忽略的 query 参数', () => {
    expect(STOCKTAKES).toContain('route.query.stocktakeId');
    expect(STOCKTAKES).toContain('openDetail(target)');
    // 找不到那一单时要说清楚, 不许静默什么都不做
    expect(STOCKTAKES).toContain('未在当前列表中找到该盘点单');
  });

  it('确认框仍复用同一份单号/金额口径, ⛔ 不许为审批框另拼一套', () => {
    // 与 pending.vue 里原有的纪律一致: businessCodeOf / amountOf 只有一份。
    expect(PENDING).toContain("['业务单号', businessCodeOf(row)]");
    expect(PENDING).toContain("['金额', formatAmount(amountOf(row))]");
  });
});
