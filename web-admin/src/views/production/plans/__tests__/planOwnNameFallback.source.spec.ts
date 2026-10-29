import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const src = readFileSync(resolve(process.cwd(), 'src/views/production/plans/list.vue'), 'utf8')

// D-S2-09: 目录查不到时, 单产品计划用计划自带的品名, 不再写「未知产品」。
describe('生产计划列表: 生产成品名的兜底', () => {
  it('单产品计划在目录查不到时回落到计划自带品名, 多产出计划不乱配', () => {
    expect(src).toContain('const UNKNOWN_OUTPUT_PRODUCT_LABEL')
    expect(src).toMatch(/label === UNKNOWN_OUTPUT_PRODUCT_LABEL && usableOwnName && targetIds\.length === 1/)
    expect(src).toContain("String(row.productTypeId ?? '') === targetIds[0]")
  })

  it('兜底常量与解析函数里的字面量一致', () => {
    expect(src).toContain("const UNKNOWN_OUTPUT_PRODUCT_LABEL = '未知产品(产品档案查不到)'")
    expect(src).toContain("return '未知产品(产品档案查不到)';")
  })

  it('产品目录还在加载时显示中性占位, 不写「产品档案查不到」', () => {
    expect(src).toContain("const PRODUCT_CATALOG_LOADING_LABEL = '产品名加载中…'")
    expect(src).toMatch(/label === UNKNOWN_OUTPUT_PRODUCT_LABEL && !productCatalogLoaded\.value\s*\? PRODUCT_CATALOG_LOADING_LABEL/)
    // 两个目录无论成功失败都要“结算”, 否则占位会永远挂着
    expect(src).toContain('productTypesSettled.value = true')
    expect(src).toContain('rawMaterialTypesSettled.value = true')
    expect(src).toContain('productTypesSettled.value && rawMaterialTypesSettled.value')
  })

  it('原料锚点不当产品名用', () => {
    expect(src).toContain('ownName && !isRawWorkflowOwnerAnchorName(ownName)')
  })
})
