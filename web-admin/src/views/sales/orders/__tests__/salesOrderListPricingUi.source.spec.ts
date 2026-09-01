import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// 🔒 2026-09-02 Owner 裁定 ("一般…都是含税报价…不可能一开始就知道未税报价…
// 他填的指的是含税报价"): list.vue 是本裁定的主录入面 (PR #468 先修了
// OrderItemsEditor.vue, 那是次要编辑器; list.vue 才是用户真正在用的完整建单页)。
//
// list.vue 是 3000+ 行的单文件页面组件, 挂载测试成本极高且此前从未这样测过 ——
// 与 new.vue 的同类修复 (salesOrderNewPricingUi.source.spec.ts) 用同一套判据:
// 源码字符串断言钉住"接线正确"(entry 换成了 taxIncludedUnitPrice, 旧 entry
// 彻底消失), 而"算出来的数对不对"由 salesOrderNewLineContract.spec.ts 里对
// derivedUntaxedPriceOrNull 的正/负对照单测覆盖 (两个文件合起来才是完整证据链)。
const source = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');

describe('sales order list.vue pricing UI (primary order-creation surface)', () => {
  it('takes tax-inclusive price as the entry field and untaxed price as a read-only derived display', () => {
    // 录入项换成了含税单价.
    expect(source).toContain('v-model="item.taxIncludedUnitPrice"');
    expect(source).toContain('<span style="width: 130px">含税单价</span>');
    // ⛔ 阴性对照: 旧的"未税单价直接录入"绑定必须彻底消失, 不能只是改了标签、
    // v-model 还留着 (那种半改最容易漏, 见硬约束 8)。
    expect(source).not.toContain('v-model="item.unitPrice"');
    // 未税单价仍然展示 (裁定只禁止"让人填未税", 没有禁止展示未税) —— 展示函数
    // 严格区分"税率未知"(渲染 '-') 和"算出来是 0", 不允许用 `?? 0` / `|| 0` 兜底.
    expect(source).toContain('<span style="width: 130px">单价 (未税)</span>');
    expect(source).toContain('function displayUntaxedUnitPrice(item: OrderItem): string {');
    expect(source).toContain('derivedUntaxedPriceOrNull(item.taxIncludedUnitPrice, item.taxRate)');
    expect(source).toContain("return derived === null ? '-' : derived.toFixed(2);");
    expect(source).not.toContain('displayUntaxedUnitPrice(item) ?? 0');
  });

  it('reuses salesOrderNewLineContract.ts instead of inventing a fourth tax-math implementation', () => {
    expect(source).toContain("from './salesOrderNewLineContract'");
    expect(source).toContain('derivedUntaxedPriceOrNull');
    expect(source).toContain('initialTaxIncludedUnitPrice');
  });

  it('recalculates the submitted untaxed unitPrice whenever the tax-inclusive entry or the tax rate changes', () => {
    expect(source).toContain('function recalculateUnitPrice(item: OrderItem) {');
    expect(source).toContain('const derived = derivedUntaxedPriceOrNull(item.taxIncludedUnitPrice, item.taxRate);');
    expect(source).toContain('item.unitPrice = derived === null ? 0 : derived;');
    // 两个触发点都要接上 recalculateUnitPrice — 含税单价输入框, 以及税率下拉.
    expect(source).toContain('@update:model-value="() => recalculateUnitPrice(item)"');
    expect(source).toContain('@change="() => recalculateUnitPrice(item)"');
  });

  it('feeds the untaxed derived price — not the tax-inclusive entry — into the margin-redline check', () => {
    // 🔴 本任务最高风险点: 毛利红线用未税 standardCost 做比较基准, quotedPrice
    // 必须是未税 unitPrice, 不能是含税录入值 (会产出一个"看起来合理但基准错误"的假数).
    expect(source).toContain('{ productTypeId: item.productTypeId, quotedPrice: item.unitPrice }');
    expect(source).not.toContain('quotedPrice: item.taxIncludedUnitPrice');
  });

  it('converts the untaxed contract-price and price-memory hints into the tax-inclusive basis before applying them', () => {
    // fetchContractPrice (CUSTOMER 自动应用) 和 applyContractPriceHint (GLOBAL 点击采用)
    // 都必须先折算成含税再写回 taxIncludedUnitPrice, 不能把未税数字直接塞进含税字段.
    expect(source).toContain('function contractHintTaxIncludedPrice(item: OrderItem): number {');
    expect(source).toContain('function priceMemoryHintTaxIncludedPrice(item: OrderItem): number {');
    expect(source).toContain('function applyContractPriceHint(item: OrderItem): void {');
    expect(source).not.toContain('item.unitPrice = Number(item.contractPriceHint.price)');
    expect(source).not.toContain('item.unitPrice = Number(item.priceMemoryHint.unitPrice)');
    expect(source).toContain('@click="applyContractPriceHint(item)"');
    expect(source).toContain('@click="applyPriceMemory(item)"');
  });

  it('strips the UI-only taxIncludedUnitPrice field before the create/update payload leaves the browser', () => {
    // list.vue 用 canonicalSalesOrderItemPayload (salesOrderUnitContract.ts) 做唯一的
    // 提交前净化点 —— 断言它在, 具体的剥离逻辑由 salesOrderUnitContract.spec.ts 覆盖.
    expect(source).toContain('canonicalSalesOrderItemPayload');
  });

  it('treats the AI-entry drawer price the same as a manually typed price (also tax-inclusive)', () => {
    // handleAiFill 是填同一张 form 的第二条路径 —— 不修就是"半迁移"(见硬约束 8 同族缺陷).
    expect(source).toContain('const aiTaxIncludedUnitPrice = Number(item.unitPrice || 0);');
    expect(source).toContain('taxIncludedUnitPrice: aiTaxIncludedUnitPrice');
    expect(source).toContain('unitPrice: derivedUntaxedPriceOrNull(aiTaxIncludedUnitPrice, aiTaxRate) ?? 0');
  });

  it('seeds taxIncludedUnitPrice from the stored untaxed unitPrice when opening an existing order for edit', () => {
    expect(source).toContain('taxIncludedUnitPrice: initialTaxIncludedUnitPrice({ unitPrice, taxRate }),');
  });
});
