import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import type { FactoryRouteCompilation } from '@/api/factoryOnboardingApi'
import { routePreviewStatus, summarizeRoutePreviews } from '../factoryRoutePreview'

const here = dirname(fileURLToPath(import.meta.url))
const component = readFileSync(resolve(here, '..', 'components', 'FactoryConversationRoutePreview.vue'), 'utf8')
const topologyCard = readFileSync(resolve(here, '..', 'components', 'FactoryConversationTopologyCard.vue'), 'utf8')
const page = readFileSync(resolve(here, '..', 'index.vue'), 'utf8')

function preview(status: string): FactoryRouteCompilation {
  return {
    productIndex: 1,
    productName: '香卤牛肉',
    routeShapes: ['仅顺序'],
    routeDetails: '没有',
    status,
    previewKind: status === 'READY' ? 'COMPILED_LINEAR' : 'ORDER_SKELETON',
    publishState: 'NOT_PUBLISHED',
    topologyComplete: status === 'READY',
    graph: {
      productCode: 'product-1', productName: '香卤牛肉',
      nodes: [{ id: 'process-1', label: '解冻' }], edges: [],
      entryNodeIds: ['process-1'], terminalNodeIds: ['process-1'],
      validation: { valid: true, issues: [] },
    },
    issues: [],
    sourceFields: ['product1.name'],
  }
}

describe('Factory B18 route compiler preview', () => {
  it('汇总 ready、blocked 与 incomplete，而不把 preview 当成发布态', () => {
    expect(summarizeRoutePreviews([preview('READY'), preview('BLOCKED'), preview('INCOMPLETE')]))
      .toEqual({ total: 3, ready: 1, blocked: 1, incomplete: 1 })
    expect(routePreviewStatus(preview('READY')).label).toBe('顺序路线已编译')
    expect(routePreviewStatus(preview('BLOCKED')).label).toBe('复杂拓扑待确认')
  })

  it('界面明确展示用户事实、顺序骨架、追问诊断与零发布写入', () => {
    expect(component).toContain('用户确认的路线形态')
    expect(component).toContain('用户补充的现场事实')
    expect(component).toContain('不包含系统猜测的分支、合流或返工连线')
    expect(component).toContain('followUpQuestion')
    expect(component).toContain('未发布 Workflow')
    expect(component).toContain('发布写入 0')
  })

  it('只在服务端回读事实已确认后挂载预览，不展示本地未保存编辑的旧结果', () => {
    expect(page).toContain('FactoryConversationRoutePreview')
    expect(page).toContain('productTableConfirmed && (progress?.routePreviews?.length ?? 0) > 0')
  })

  it('复杂路线使用独立确认卡收集起终点、逐边条件和返工出口', () => {
    expect(topologyCard).toContain('不会从一句话里猜连线')
    expect(topologyCard).toContain('整条路线起点')
    expect(topologyCard).toContain('整条路线终点')
    expect(topologyCard).toContain('触发条件（必填）')
    expect(topologyCard).toContain('返工复检通过后的放行出口')
    expect(topologyCard).toContain('任何未知工序、重复边、缺条件或断路都会整批拒绝')
    expect(page).toContain('FactoryConversationTopologyCard')
    expect(page).toContain('submitRouteTopology')
  })

  it('显式拓扑预览逐边显示条件且继续保持零发布写入', () => {
    expect(component).toContain('用户确认的逐条拓扑')
    expect(component).toContain('edge.condition')
    expect(component).toContain('放行出口')
    expect(component).toContain('发布写入 0')
  })
})
