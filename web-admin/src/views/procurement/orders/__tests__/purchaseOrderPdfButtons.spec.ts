import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const viewDir = resolve(__dirname, '..');

function readView(name: string): string {
  return readFileSync(resolve(viewDir, name), 'utf8');
}

describe('procurement order PDF buttons', () => {
  /**
   * 2026-08-20 改写 (⛔ 不是删除): 原用例断言详情页**同时**提供对外版和内部审批版
   * 两个下载入口。owner 实测内部审批版「下载下来是一个我打不开的东西」并判定它没用,
   * 已移除 —— 所以那条断言守的是一个**现在判定为多余**的行为, 按「改写不删除」翻成
   * 阴性对照。真实坏因记在 detail.vue 的 handleDownloadSupplyOrderPdf 注释里
   * (后端 PurchaseOrderPdfServiceImpl 累加 grandTotal 时对 null lineAmount NPE)。
   */
  it('detail page offers only the external supplier PDF, and it is pinned to external=true', () => {
    const source = readView('detail.vue');

    expect(source).toContain('handleDownloadSupplyOrderPdf()');
    expect(source).toContain('params: { external: true }');

    // 阴性对照: 内部审批版不许悄悄回来 (按钮 / 调用 / 文件名 三处一起钉)
    expect(source).not.toContain('handleDownloadPdf(false)');
    expect(source).not.toContain('external: externalVersion');
    expect(source).not.toContain('采购订单_内部_');
  });

  it('price columns in the detail table stay behind the price-view permission', () => {
    // 原用例里的 `v-if="canViewPrice"` 断言本意是「价格入口受 procurement:price:view 保护」。
    // 内部审批版按钮移除后, 让它继续钉在**仍然存在**的价格列上, 而不是靠页面里
    // 随便哪一处 canViewPrice 巧合匹配。
    const source = readView('detail.vue');

    expect(source).toContain('v-if="canViewPrice" prop="unitPrice"');
    expect(source).toContain('v-if="canViewPrice" label="小计"');
    expect(source).toContain('<el-collapse v-if="canViewPrice"');
  });

  it('list shortcut always downloads the external supplier PDF', () => {
    const source = readView('list.vue');

    expect(source).toContain('params: { external: true }');
    expect(source).toContain('handleDownloadPdf(row)">对外供货单</el-button>');
  });
});
