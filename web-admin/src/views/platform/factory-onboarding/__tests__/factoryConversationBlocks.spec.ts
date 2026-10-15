import { describe, expect, it } from 'vitest'
import type { FactoryBuildProgress, PendingItem } from '@/api/factoryOnboardingApi'
import {
  activeFactoryDomainGroup,
  activeProcessDetailGroup,
  buildProductFlowRows,
  buildProcessSkeletonRows,
  buildRouteTopologyDraft,
  choiceReceiptEntries,
  extractionReceiptEntries,
  isSpecializedDomainField,
  mergeSuggestedAnswers,
  normalizeProductRouteShape,
  productConfigurationPending,
  productFlowAnswers,
  productFlowPersisted,
  productFlowSignature,
  processSkeletonAnswers,
  processSkeletonPersisted,
  processSkeletonSignature,
  routeTopologyAnswers,
  restoreConversationMessages,
  suggestedAnswerMatches,
  suggestedAnswersForItems,
} from '../factoryConversationBlocks'

function progress(collected: Record<string, string>): FactoryBuildProgress {
  return {
    sessionId: 'session-1', factoryId: 'F001', status: 'IN_PROGRESS', currentRound: 1,
    maxRounds: 20, remaining: 1, pending: [], pendingLabels: [], answered: [],
    collected, readyForGenerate: false,
  }
}

describe('工厂装配对话结构化消息块', () => {
  it('让仅顺序与复杂路线形态保持互斥', () => {
    expect(normalizeProductRouteShape(['有返工', '仅顺序'])).toEqual(['仅顺序'])
    expect(normalizeProductRouteShape(['仅顺序', '有返工'])).toEqual(['有返工'])
    expect(normalizeProductRouteShape(['有并行', '有合流'])).toEqual(['有并行', '有合流'])
  })

  it('把 Agent 已理解的工序字段还原成可编辑表格', () => {
    const rows = buildProcessSkeletonRows(progress({
      stepCount: '2',
      'step1.name': '解冻修整',
      'step1.inputMaterial': '冷鲜牛肉',
      'step2.name': '腌制',
    }))
    expect(rows).toEqual([
      { sequence: 1, name: '解冻修整', inputMaterial: '冷鲜牛肉', output: '' },
      { sequence: 2, name: '腌制', inputMaterial: '', output: '' },
    ])
  })

  it('只把服务端参考值预填到空白流程字段且已收集真值优先', () => {
    const current = progress({
      stepCount: '2',
      'step1.name': '工厂确认的解冻',
    })
    current.suggestions = {
      'step1.name': '参考解冻',
      'step1.inputMaterial': '冻牛肉',
      'step1.output': '解冻牛肉',
      'step2.name': '滚揉',
    }
    expect(buildProcessSkeletonRows(current)).toEqual([
      { sequence: 1, name: '工厂确认的解冻', inputMaterial: '冻牛肉', output: '解冻牛肉' },
      { sequence: 2, name: '滚揉', inputMaterial: '', output: '' },
    ])
  })

  it('服务端已明确收集为空时也不会被参考值覆盖', () => {
    const current = progress({ stepCount: '1', 'step1.output': '' })
    current.suggestions = { 'step1.output': '参考产出' }
    expect(buildProcessSkeletonRows(current)[0]?.output).toBe('')
  })

  it('30 道真实工序不会在对话表格层被旧的 20 行上限截断', () => {
    const rows = buildProcessSkeletonRows(progress({ stepCount: '30' }))
    expect(rows).toHaveLength(30)
    expect(rows.at(-1)?.sequence).toBe(30)
  })

  it('结构化返回只提交非空字段并清理两侧空格', () => {
    expect(processSkeletonAnswers([
      { sequence: 1, name: ' 解冻 ', inputMaterial: '牛肉', output: '' },
      { sequence: 2, name: '腌制', inputMaterial: ' ', output: '腌制肉' },
    ])).toEqual({
      'step1.name': '解冻',
      'step1.inputMaterial': '牛肉',
      'step2.name': '腌制',
      'step2.output': '腌制肉',
    })
  })

  it('签名会忽略无意义空格但能识别真实编辑', () => {
    const base = [{ sequence: 1, name: '解冻', inputMaterial: '', output: '' }]
    expect(processSkeletonSignature([{ ...base[0], name: ' 解冻 ' }])).toBe(processSkeletonSignature(base))
    expect(processSkeletonSignature([{ ...base[0], name: '腌制' }])).not.toBe(processSkeletonSignature(base))
  })

  it('刷新时只把服务端持久化完整的流程骨架恢复为已确认', () => {
    expect(processSkeletonPersisted(progress({
      stepCount: '1',
      'step1.name': '解冻',
      'step1.inputMaterial': '冻牛肉',
      'step1.output': '解冻牛肉',
    }))).toBe(true)
    expect(processSkeletonPersisted(progress({
      stepCount: '1',
      'step1.name': '解冻',
      'step1.inputMaterial': '冻牛肉',
    }))).toBe(false)
  })

  it('案例建议即使填满流程骨架也仍然是待确认，不冒充服务端已收集答案', () => {
    const suggested = progress({ stepCount: '1' })
    suggested.suggestions = {
      'step1.name': '参考解冻',
      'step1.inputMaterial': '参考冻牛肉',
      'step1.output': '参考解冻牛肉',
    }
    expect(buildProcessSkeletonRows(suggested)[0]).toEqual({
      sequence: 1,
      name: '参考解冻',
      inputMaterial: '参考冻牛肉',
      output: '参考解冻牛肉',
    })
    expect(processSkeletonPersisted(suggested)).toBe(false)
  })

  it('选择卡回执使用服务端标签而不是内部字段名', () => {
    const items: PendingItem[] = [{ name: 'businessType', label: '业务类型', inputType: 'CHOICE' }]
    expect(choiceReceiptEntries(items, { businessType: ['熟食', '预制菜'] }))
      .toEqual([{ label: '业务类型', value: '熟食、预制菜' }])
  })

  it('参考答案只有通过服务端选项校验才进入待确认表单', () => {
    const items: PendingItem[] = [
      { name: 'single', label: '单选', inputType: 'CHOICE', options: ['卤味熟食', '烘焙'] },
      { name: 'multi', label: '多选', inputType: 'CHOICE', options: ['库存不足', '质检异常'], multiSelect: true },
      { name: 'narrative', label: '说明', inputType: 'NARRATIVE' },
      { name: 'invalid', label: '非法选项', inputType: 'CHOICE', options: ['是', '否'] },
    ]
    expect(suggestedAnswersForItems(items, {
      single: '卤味 熟食',
      multi: '库存不足、质检异常',
      narrative: '  冷藏入库  ',
      invalid: '也许',
    })).toEqual({
      single: '卤味熟食',
      multi: ['库存不足', '质检异常'],
      narrative: '冷藏入库',
    })
  })

  it('预填不会覆盖用户修改或用户主动清空的值', () => {
    expect(mergeSuggestedAnswers(
      { keep: '我改过了', cleared: '' },
      { keep: '参考值', cleared: '参考值', untouched: '可预填' },
    )).toEqual({ keep: '我改过了', cleared: '', untouched: '可预填' })
  })

  it('多选参考值按服务端选项匹配且不受逗号与选择顺序影响', () => {
    const item: PendingItem = {
      name: 'alerts', label: '提醒', inputType: 'CHOICE',
      options: ['库存不足', '质检异常'], multiSelect: true,
    }
    expect(suggestedAnswerMatches(
      item,
      ['质检异常', '库存不足'],
      { alerts: '库存不足, 质检异常' },
    )).toBe(true)
  })

  it('字段级回执区分接受、拒绝、未知与冲突且不暴露未知内部字段名', () => {
    const items: PendingItem[] = [
      { name: 'quantity', label: '每批数量', inputType: 'NARRATIVE' },
      { name: 'temperature', label: '加工温度', inputType: 'NARRATIVE' },
      { name: 'supplier', label: '供应商', inputType: 'NARRATIVE' },
    ]
    expect(extractionReceiptEntries(items, { stepCount: '工序道数' }, [
      { field: 'quantity', resolution: 'ACCEPTED', canonicalValue: '3' },
      { field: 'temperature', resolution: 'REJECTED' },
      { field: 'supplier', resolution: 'UNKNOWN' },
      { field: 'stepCount', resolution: 'ACCEPTED', canonicalValue: '2' },
      { field: 'providerOnlyField', resolution: 'CONFLICT' },
    ])).toEqual([
      { label: '每批数量', value: '3', state: 'accepted' },
      { label: '加工温度', value: '未保存，需要重新确认', state: 'rejected' },
      { label: '供应商', value: '保持未回答', state: 'unknown' },
      { label: '工序道数', value: '2', state: 'accepted' },
      { label: '无法识别的输入字段', value: '未保存，存在冲突', state: 'conflict' },
    ])
  })

  it('刷新恢复服务端持久化的字段回执与更正语义', () => {
    expect(restoreConversationMessages([{
      role: 'user',
      content: '应为9道，不是10道',
      extractionOutcome: 'SUCCESS',
      correctionApplied: true,
      fieldReceipts: [{ field: 'stepCount', resolution: 'ACCEPTED', canonicalValue: '9' }],
    }], [], { stepCount: '工序道数' })).toEqual([
      { role: 'me', text: '应为9道，不是10道' },
      {
        role: 'ai',
        receipt: {
          kind: 'field-card',
          title: '已恢复这轮更正后的字段结果',
          entries: [{ label: '工序道数', value: '9', state: 'accepted' }],
        },
      },
    ])
  })

  it('流程骨架确认后只展开当前一道工序的非核心细节', () => {
    const current = progress({
      stepCount: '2',
      'step1.name': '解冻修整',
      'step2.name': '腌制',
    })
    current.pendingItems = [
      { name: 'step1.inputMaterial', label: '第1道·投入主原料', inputType: 'NARRATIVE', repeatIndex: 1 },
      { name: 'step1.auxMaterials', label: '第1道·辅料', inputType: 'NARRATIVE', repeatIndex: 1, allowNone: true },
      { name: 'step1.params', label: '第1道·关键参数', inputType: 'NARRATIVE', repeatIndex: 1 },
      { name: 'step2.auxMaterials', label: '第2道·辅料', inputType: 'NARRATIVE', repeatIndex: 2 },
    ]
    expect(activeProcessDetailGroup(current, false)).toBeNull()
    expect(activeProcessDetailGroup(current, true)).toBeNull()
    current.pendingItems = current.pendingItems.slice(1)
    expect(activeProcessDetailGroup(current, true)).toEqual({
      sequence: 1,
      processName: '解冻修整',
      items: [current.pendingItems[0], current.pendingItems[1]],
    })
  })

  it('把每个成品及其工序链还原成可编辑表格', () => {
    const current = progress({
      productCount: '2',
      productSkus: '香卤牛肉每袋200g，一箱20袋',
      'product1.name': '香卤牛肉 200g',
      'product1.steps': '解冻修整→腌制→卤煮→包装',
      'product1.routeShape': '仅顺序',
      'product1.routeDetails': '没有',
      'product2.name': '五香牛腱 500g',
    })
    expect(buildProductFlowRows(current)).toEqual([
      { sequence: 1, name: '香卤牛肉 200g', steps: '解冻修整→腌制→卤煮→包装', routeShape: ['仅顺序'], routeDetails: '没有' },
      { sequence: 2, name: '五香牛腱 500g', steps: '', routeShape: [], routeDetails: '' },
    ])
  })

  it('成品表可带入服务端参考路线但不覆盖已确认成品', () => {
    const current = progress({
      productCount: '1',
      'product1.name': '工厂确认牛腱肉',
    })
    current.suggestions = {
      'product1.name': '参考牛腱肉',
      'product1.steps': '解冻→滚揉→熟制→包装',
      'product1.routeShape': '有条件分支、有返工',
      'product1.routeDetails': '质检不合格回滚揉',
    }
    expect(buildProductFlowRows(current)).toEqual([{
      sequence: 1,
      name: '工厂确认牛腱肉',
      steps: '解冻→滚揉→熟制→包装',
      routeShape: ['有条件分支', '有返工'],
      routeDetails: '质检不合格回滚揉',
    }])
  })

  it('成品表结构化返回规格和非空工序链并清理空格', () => {
    expect(productFlowAnswers([
      { sequence: 1, name: ' 香卤牛肉 ', steps: ' 解冻→卤煮 ', routeShape: [' 有返工 '], routeDetails: ' 金检失败回拆包 ' },
      { sequence: 2, name: '', steps: ' ', routeShape: [], routeDetails: ' ' },
    ], ' 一箱装20袋 ')).toEqual({
      productSkus: '一箱装20袋',
      'product1.name': '香卤牛肉',
      'product1.steps': '解冻→卤煮',
      'product1.routeShape': ['有返工'],
      'product1.routeDetails': '金检失败回拆包',
    })
  })

  it('仅顺序成品一次确认起终点并标记无需复杂拓扑卡', () => {
    expect(productFlowAnswers([{
      sequence: 1, name: '香卤牛肉', steps: '解冻 → 卤煮 → 包装',
      routeShape: ['仅顺序'], routeDetails: '没有',
    }], '每箱20袋')).toMatchObject({
      'product1.routeEntry': '解冻',
      'product1.routeTerminal': '包装',
      'product1.routeTopology': '按顺序自动确定',
    })
    const legacy = progress({
      productCount: '1', productSkus: '每箱20袋',
      'product1.name': '香卤牛肉', 'product1.steps': '解冻→包装',
      'product1.routeShape': '仅顺序', 'product1.routeDetails': '没有',
    })
    legacy.pendingItems = [{ name: 'product1.routeTopology', label: '逐条路线连线', inputType: 'NARRATIVE' }]
    expect(productConfigurationPending(legacy)).toBe(true)
    expect(productFlowPersisted(legacy)).toBe(false)
  })

  it('为待确认复杂路线生成可编辑拓扑卡并编码完整边事实', () => {
    const current = progress({
      productCount: '1',
      'product1.name': '调理肉制品',
      'product1.steps': '收料→金检→返修→包装',
      'product1.routeShape': '有条件分支、有返工',
      'product1.routeDetails': '金检不合格返修',
    })
    current.pendingItems = [
      { name: 'product1.routeEntry', label: '路线起点', inputType: 'NARRATIVE' },
      { name: 'product1.routeTerminal', label: '路线终点', inputType: 'NARRATIVE' },
      { name: 'product1.routeTopology', label: '逐条路线连线', inputType: 'NARRATIVE' },
    ]
    current.routePreviews = [{
      productIndex: 1, productName: '调理肉制品',
      routeShapes: ['有条件分支', '有返工'], routeDetails: '金检不合格返修',
      status: 'BLOCKED', previewKind: 'ORDER_SKELETON', publishState: 'NOT_PUBLISHED', topologyComplete: false,
      graph: {
        productCode: 'product-1', productName: '调理肉制品',
        nodes: ['收料', '金检', '返修', '包装'].map((label, index) => ({ id: `process-${index + 1}`, label })),
        edges: [
          { fromNodeId: 'process-1', toNodeId: 'process-2', type: 'FORWARD' },
          { fromNodeId: 'process-2', toNodeId: 'process-3', type: 'FORWARD' },
          { fromNodeId: 'process-3', toNodeId: 'process-4', type: 'FORWARD' },
        ],
        entryNodeIds: ['process-1'], terminalNodeIds: ['process-4'], validation: { valid: true, issues: [] },
      },
      issues: [], sourceFields: [],
    }]

    const draft = buildRouteTopologyDraft(current)
    expect(draft).toMatchObject({ entry: '收料', terminal: '包装', productIndex: 1 })
    expect(draft?.rows).toHaveLength(3)
    if (!draft) throw new Error('expected topology draft')
    draft.rows[1] = {
      ...draft.rows[1], type: 'REWORK', condition: '金检不合格', reworkExit: '包装',
    }
    expect(routeTopologyAnswers(draft)['product1.routeTopology'])
      .toContain('金检\t返修\tREWORK\t金检不合格\t包装')
    expect(productConfigurationPending(current)).toBe(false)
  })

  it('成品表签名忽略无意义空格但包含包装规格', () => {
    const rows = [{ sequence: 1, name: '香卤牛肉', steps: '解冻→卤煮', routeShape: ['有返工'], routeDetails: '金检失败回拆包' }]
    expect(productFlowSignature([{ ...rows[0], name: ' 香卤牛肉 ' }], ' 一箱20袋 '))
      .toBe(productFlowSignature(rows, '一箱20袋'))
    expect(productFlowSignature(rows, '一箱20袋')).not.toBe(productFlowSignature(rows, '一箱10袋'))
  })

  it('刷新时只把服务端持久化完整且无待答项的成品表恢复为已确认', () => {
    const complete = progress({
      productCount: '1',
      productSkus: '每箱20袋',
      'product1.name': '香卤牛肉',
      'product1.steps': '解冻→卤煮→包装',
      'product1.routeShape': '仅顺序',
      'product1.routeDetails': '没有',
    })
    complete.pendingItems = []
    expect(productFlowPersisted(complete)).toBe(true)

    complete.pendingItems = [{ name: 'product1.steps', label: '工序链', inputType: 'NARRATIVE' }]
    expect(productFlowPersisted(complete)).toBe(false)
  })

  it('案例建议填满成品行时也不会被恢复成已确认', () => {
    const suggested = progress({ productCount: '1', productSkus: '每箱20袋' })
    suggested.pendingItems = []
    suggested.suggestions = {
      'product1.name': '参考香卤牛肉',
      'product1.steps': '参考解冻→卤煮→包装',
      'product1.routeShape': '仅顺序',
      'product1.routeDetails': '没有',
    }
    expect(buildProductFlowRows(suggested)[0]).toEqual({
      sequence: 1,
      name: '参考香卤牛肉',
      steps: '参考解冻→卤煮→包装',
      routeShape: ['仅顺序'],
      routeDetails: '没有',
    })
    expect(productFlowPersisted(suggested)).toBe(false)
  })

  it('产品、系统范围和食品安全问题由专项卡接管而不重复出现在通用信息卡', () => {
    const current = progress({ productCount: '1' })
    current.pendingItems = [
      { name: 'productSkus', label: '成品规格', inputType: 'NARRATIVE' },
      { name: 'product1.name', label: '第1个·成品名称', inputType: 'NARRATIVE', repeatIndex: 1 },
      { name: 'moduleOptOut', label: '哪些功能用不上', inputType: 'CHOICE', multiSelect: true },
      { name: 'createAccounts', label: '登录账号', inputType: 'CHOICE' },
      { name: 'haccpPoints', label: '关键控制点', inputType: 'CHOICE', multiSelect: true },
      { name: 'haccpLimits', label: '临界限值', inputType: 'NARRATIVE', allowNone: true },
    ]

    expect(productConfigurationPending(current)).toBe(true)
    expect(isSpecializedDomainField('moduleOptOut')).toBe(true)
    expect(isSpecializedDomainField('businessType')).toBe(false)
    expect(activeFactoryDomainGroup(current, false)).toBeNull()
    expect(activeFactoryDomainGroup(current, true)).toMatchObject({
      kind: 'access',
      items: [current.pendingItems[2], current.pendingItems[3]],
    })

    current.pendingItems = current.pendingItems.slice(4)
    expect(activeFactoryDomainGroup(current, true)).toMatchObject({
      kind: 'quality',
      items: current.pendingItems,
    })
  })
})
