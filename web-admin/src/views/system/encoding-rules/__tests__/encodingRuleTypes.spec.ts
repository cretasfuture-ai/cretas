import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ENTITY_TYPE_LABELS, isSystemNumbered, selectableEntityTypes } from '../encodingRuleTypes';

/** PROTO-QC-NUMBER M2: 质检记录由系统自动编号, 编码规则页不能再让人配一条不生效的规则。 */
describe('编码规则可配类型', () => {
  it('阳性对照: 其它单据类型都还在可选列表里', () => {
    const codes = selectableEntityTypes().map((t) => t.value);
    expect(codes).toContain('SALES_ORDER');
    expect(codes).toContain('MATERIAL_BATCH');
    expect(codes.length).toBe(Object.keys(ENTITY_TYPE_LABELS).length - 1);
  });

  it('质检记录不在新建可选列表里', () => {
    expect(selectableEntityTypes().map((t) => t.value)).not.toContain('QUALITY_INSPECTION');
    expect(isSystemNumbered('QUALITY_INSPECTION')).toBe(true);
    expect(isSystemNumbered('quality_inspection')).toBe(true);
    expect(isSystemNumbered('SALES_ORDER')).toBe(false);
  });

  it('页面新建下拉用 selectableEntityTypes, 已有质检规则行标出「由系统自动编号」', () => {
    const page = readFileSync(resolve(process.cwd(), 'src/views/system/encoding-rules/list.vue'), 'utf-8');
    expect(page).toContain('selectableEntityTypes()');
    expect(page).toContain('isSystemNumbered(row.entityType)');
    expect(page).not.toContain('v-for="(v, k) in entityTypeMap"');
  });
});
