import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 🔴 2026-08-28 生产事故的回归闸。
 *
 * 症状: 「新建原料类型」的**必填**字段「储存类型」下拉显示「无数据」⇒ 所有工厂都建不了原料类型。
 * 成因: 该下拉的**唯一**数据源是 `system_enums`, 而那张表在 prod 上整表 0 行
 *       (V20260506_01 于 2026-05-08 seed 成功, 行后来不知去向), 且**没有任何界面**能补它。
 *
 * 这道闸守的**行为**是: 「储存类型下拉在外部数据全空时仍然有选项」——
 * 不是守某一行代码怎么写。
 */
const SOURCE = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

/** 兜底常量的源码 —— 单独读, 免得把「常量为空」这种恒真式放过去。 */
const CONSTANTS = readFileSync(
  resolve(process.cwd(), 'src/utils/materialCategory.ts'),
  'utf8',
);

describe('储存类型下拉不能只依赖 system_enums', () => {
  it('平台内置兜底值非空, 且下拉把它并入', () => {
    // 阳性: 兜底常量本身得有东西, 否则下面那条断言是恒真式
    const decl = /MATERIAL_STORAGE_TYPE_ENUM_VALUES = \[([^\]]*)\]/.exec(CONSTANTS);
    expect(decl).not.toBeNull();
    expect((decl as RegExpExecArray)[1].split(',').filter((x) => x.trim()).length).toBeGreaterThan(0);
    expect(SOURCE).toContain('MATERIAL_STORAGE_TYPE_ENUM_VALUES.forEach');
  });

  it('本厂储存区域(仓库平面图 zones)并入下拉 —— 客户配的东西必须看得见', () => {
    expect(SOURCE).toContain('getWarehouseLayout');
    expect(SOURCE).toContain('storageZoneNames.value.forEach');
  });

  it('存量取值并入下拉, 历史值不会消失', () => {
    expect(SOURCE).toContain('seenStorageTypes.value.forEach');
  });

  it('⛔ 阴性对照: 默认值不许再从(可能为空的)字典首项取', () => {
    // 修复前是 `storageTypeOptions.value[0]?.enumLabel` —— 字典空时它恒为 ''
    expect(SOURCE).not.toContain('storageTypeOptions.value[0]?.enumLabel');
  });

  it('⛔ 阴性对照: 字典加载失败不许再被静默吞掉', () => {
    expect(SOURCE).not.toContain("console.warn('字典加载失败, 用空选项'");
  });
});
