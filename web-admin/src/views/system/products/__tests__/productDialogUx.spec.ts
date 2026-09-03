import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('SKU editor dialog UX', () => {
  it('keeps conversion labels readable and primary actions visible', () => {
    expect(source).toContain('class="product-edit-dialog"');
    expect(source).toContain('label-width="120px"');
    expect(source).toContain(':modal-class="\'product-edit-modal\'"');
    expect(source).toContain('.product-edit-modal .el-dialog__footer');
    expect(source).toContain('position: sticky');
    expect(source).toContain('label="基础规格"');
    expect(source).toContain('class="sku-measure-row"');
    expect(source).toContain('class="sku-measure-field"');
    expect(source).toContain('class="net-content-expression"');
    expect(source).toContain("每 1 {{ displayUnit(formData.unit) || '销售单位' }}");
    expect(source).toContain(':show-symbol="false"');
    expect(source).toContain('v-model="netContentUnit"');
    expect(source).toContain('g/kg/ml/L');
    expect(source).not.toContain('标准单位换算（1${formData.unit');
  });

  it('shows the authoritative creation timestamp returned by the SKU list API', () => {
    // 守的是「formatDateTime 来自共享的 @/utils/dateFormat」这件事,
    // ⛔ 不是那一行 import 的字面量 —— 同一条 import 里再并进别的名字
    // (2026-08-26 并进了 toDateString)是正常演进, 不该让这条闸变红。
    expect(source).toMatch(
      /import\s*\{[^}]*\bformatDateTime\b[^}]*\}\s*from\s*'@\/utils\/dateFormat'/,
    );
    expect(source).toContain('prop="createdAt" label="创建时间"');
    expect(source).toContain('{{ formatDateTime(row.createdAt) }}');
  });

  it('keeps semi-finished base units editable and exposes a real preview-first import flow', () => {
    expect(source).toContain('if (!formData.unit) formData.unit = \'kg\'');
    expect(source).not.toContain(':disabled="isSemiFinishedSku"');
    expect(source).toContain('v-model="importDialogVisible"');
    expect(source).toContain('/product-types/import/template');
    expect(source).toContain('/product-types/import/preview');
    expect(source).toContain('/product-types/import/confirm');
    expect(source).toContain('/upload/product-image');
    expect(source).toContain("formData.append('imageMappings', JSON.stringify(allMappings))");
    expect(source).toContain('accept=".xlsx"');
    expect(source).not.toContain('accept=".xlsx,.xls"');
    expect(source).toContain('prop="specification" label="生成规格"');
    expect(source).toContain("{{ row.specification || '—' }}");
  });

  it('keeps category authoritative, scopes suggestions, and explains multi-box rules', () => {
    const dialogSource = source.slice(source.indexOf('class="product-edit-dialog"'));
    expect(dialogSource.indexOf('label="产品大类"')).toBeLessThan(dialogSource.indexOf('label="产品名称"'));
    expect(source).toContain('params: { name, productCategory: formData.productCategory || undefined }');
    expect(source).toContain('名称建议只在当前大类内匹配，绝不反写或清空大类');
    expect(source).not.toContain('formData.productCategory = newCat');
    expect(source).toContain('const exactNameDuplicate = computed');
    expect(source).toContain('同厂已存在同名 SKU');
    expect(source).toContain('class="packaging-rule-note"');
    expect(source).toContain('一个 SKU 只有一个基本单位和一份标准克重');
    expect(source).toContain('添加多装箱包装规则');
    expect(source).toContain('reconcilePackagingSpecs(formData.productCategory');
    expect(source).toContain('isCurrentCategorySuggestion(');
    expect(source).toContain("recommendation?.source === 'PUBLISHED_WORKFLOW'");
    expect(source).toContain("recommendation?.reasonCode === 'COMPLETE_PUBLISHED_WORKFLOW'");
    expect(source).not.toContain("ElMessage.warning(res.message || res.data?.message || '暂未生成推荐工序");
  });

  /**
   * 🔴 2026-09-03: 这条原本守「快捷工序抽屉的搜索/筛选/分页 UX」。抽屉去掉了 ——
   * 它是 product_work_processes 的第二个编辑入口, 比 /system/product-processes
   * 少 update / copy-chain, 成品源 / 成本类别 / 包装模板 / 辅料分摊 一个都配不了
   * (客户 2026-09-03:「这个工序关联管理是不是没用了, 可以去掉了吧」)。
   *
   * ⇒ 断言从「抽屉长什么样」抬到「工序入口还在, 且指向那个全功能页面」——
   * ⛔ 不是删掉: 入口本身没了就成了 dead-end(防呆 Rule 5)。
   */
  it('工序入口指向产品-工序配置页, 并带上 productTypeId 自动选中', () => {
    expect(source).toContain('@click="goProcessConfig(row)"');
    expect(source).toContain("path: '/system/product-processes', query: { productTypeId: row.id }");
    // 阴性对照: 抽屉真的不在了(否则上面两条可能只是「两个入口并存」)。
    expect(source).not.toContain('processDrawerVisible');
    expect(source).not.toContain('handleConfigProcesses');
  });
});
