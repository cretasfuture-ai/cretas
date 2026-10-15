import { describe, expect, it } from 'vitest'
import type { FactoryBuildProgress, PlannedChange, SuggestedData } from '@/api/factoryOnboardingApi'
import {
  coverageBars,
  deriveReconciliationIssues,
  questionnaireItems,
  workflowLanes,
} from '../factoryAssemblyPresentation'

function progress(overrides: Partial<FactoryBuildProgress> = {}): FactoryBuildProgress {
  return {
    sessionId: 'session-1',
    factoryId: 'F006',
    status: 'COLLECTING',
    currentRound: 2,
    maxRounds: 20,
    remaining: 2,
    pending: [],
    pendingLabels: [],
    answered: [],
    collected: {},
    readyForGenerate: false,
    ...overrides,
  }
}

describe('工厂能力装配的展示契约', () => {
  it('问卷只使用服务端选项题，并排除逐项展开题', () => {
    const items = questionnaireItems(progress({
      pendingItems: [
        { name: 'industry', label: '行业', inputType: 'CHOICE', options: ['卤味', '烘焙'] },
        { name: 'productName1', label: '第1个产品名称', inputType: 'CHOICE', options: ['A'], repeatIndex: 1 },
        { name: 'description', label: '补充说明', inputType: 'NARRATIVE' },
      ],
    }))
    expect(items.map((item) => item.name)).toEqual(['industry'])
  })

  it('产品重名和数量不一致会成为阻断项，放弃项只警告', () => {
    const issues = deriveReconciliationIssues(progress({
      collected: { productCount: '3', productName1: '香辣鸡', productName2: '香辣鸡' },
      answeredLabels: {
        productCount: '成品数量',
        productName1: '第1个成品名称',
        productName2: '第2个成品名称',
      },
      skippedItems: [{ name: 'warehouse', label: '存放区', why: '两次追问仍未确认' }],
    }))
    expect(issues.filter((issue) => issue.severity === 'blocking')).toHaveLength(2)
    expect(issues.find((issue) => issue.id === 'skipped-warehouse')?.severity).toBe('warning')
  })

  it('覆盖图按服务端计划分组统计新建、覆盖和跳过', () => {
    const changes: PlannedChange[] = [
      { group: '产品类型', key: 'P1', detail: '产品一', exists: false, action: '新建' },
      { group: '产品类型', key: 'P2', detail: '产品二', exists: true, action: '覆盖' },
      { group: '工序', key: '切配', detail: '切配', exists: true, action: '跳过' },
    ]
    expect(coverageBars(changes)[0]).toMatchObject({ group: '产品类型', count: 2, create: 1, overwrite: 1 })
  })

  it('产品工艺图保留产品名称和服务端顺序', () => {
    const data: SuggestedData = {
      productTypes: [{ code: 'P1', name: '香辣鸡', unit: 'kg' }],
      materialTypes: [],
      conversionRates: [],
      productProcesses: [{ productCode: 'P1', processNames: ['解冻', '腌制', '熟制'] }],
    }
    expect(workflowLanes(data)).toEqual([{
      productCode: 'P1',
      productName: '香辣鸡',
      processNames: ['解冻', '腌制', '熟制'],
    }])
  })
})
