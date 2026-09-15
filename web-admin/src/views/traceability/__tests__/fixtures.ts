import type { FullTraceResponse, LineageNode } from '@/api/traceability';
import type { SourceIndividualDetail } from '@/api/sourceIndividual';

/**
 * 溯源页测试样本。与手机端 `SourceIndividualBanner` 的判据用同一组形状:
 *   · 六头牛全带个体 ⇒ 横幅「本批来自 6 头牛」
 *   · 最远层混一个不带个体的原料(调料) ⇒ 不出横幅 (count 仍 > 0, 只有条件 2 不满足)
 * 变异探针 (`__tests__` 外的临时 spec) 也 import 这里, 所以样本不内联在 spec 里。
 */

export function cattleNode(i: number, overrides: Partial<LineageNode> = {}): LineageNode {
  return {
    nodeType: 'MATERIAL_BATCH',
    nodeId: `mb-${i}`,
    batchNumber: `MB-2026-00${i}`,
    name: '牛胴体',
    supplierName: '胜龙牧场',
    quantity: 250,
    unit: 'kg',
    resolved: true,
    sourceIndividual: {
      id: `si-${i}`,
      individualCode: `SL-00${i}`,
      tagNumber: `1234567${i}`,
      species: '牛',
      breed: '西门塔尔',
      originFarm: '胜龙一号场',
      dnaVerificationStatus: i % 2 === 0 ? 'MATCHED' : 'SAMPLED',
      tagNumberMasked: false,
    },
    ...overrides,
  };
}

export const SEASONING_NODE: LineageNode = {
  nodeType: 'MATERIAL_BATCH',
  nodeId: 'mb-salt',
  batchNumber: 'MB-SALT-01',
  name: '食盐',
  supplierName: '某盐业',
  quantity: 5,
  unit: 'kg',
  resolved: true,
  sourceIndividual: null,
};

export function baseTrace(overrides: Partial<FullTraceResponse> = {}): FullTraceResponse {
  return {
    production: {
      batchNumber: 'PB-2026-001',
      productName: '冷鲜牛腩',
      productType: 'FINISHED',
      productionDate: '2026-09-10',
      qualityStatus: 'QUALIFIED',
      factoryName: '胜龙牛业加工厂',
      factoryId: 'F006',
      supervisorName: '',
    },
    materials: [],
    processingStages: [
      { stageType: 'CUTTING', stageName: '分割', stageOrder: 1, operatorName: '张三', durationMinutes: 40 },
    ],
    qualityInspections: [
      { inspectionId: 'qi-00000001', inspectionDate: '2026-09-10T08:00:00', inspectorName: '李四', result: 'PASSED', passRate: 100 },
    ],
    shipments: [
      { shipmentNumber: 'SH-2026-001', shipmentDate: '2026-09-11', customerName: '某超市', status: 'delivered', quantity: 100, unit: 'kg' },
    ],
    traceCode: 'TC-PB-2026-001',
    queryTime: '2026-09-15T10:00:00',
    lineage: {
      rootType: 'PRODUCTION_BATCH',
      rootId: 'pb-1',
      ancestors: [{ depth: 1, nodes: [1, 2, 3, 4, 5, 6].map((i) => cattleNode(i)) }],
      descendants: null,
      maxDepth: 1,
      nodeCount: 6,
      sourceIndividualCount: 6,
    },
    ...overrides,
  };
}

/** ① 有祖先且全带个体 ⇒ 横幅。 */
export function sixCattleTrace(): FullTraceResponse {
  return baseTrace();
}

/** ② 最远层混一个不带个体的原料 ⇒ 不出横幅 (count 仍是 5, 只有「全部」这个条件不满足)。 */
export function mixedFarthestLayerTrace(): FullTraceResponse {
  return baseTrace({
    lineage: {
      rootType: 'PRODUCTION_BATCH',
      rootId: 'pb-1',
      ancestors: [
        // depth 1 全是带个体的牛 —— 这一层不是最远层, 不参与判据
        { depth: 1, nodes: [1, 2].map((i) => cattleNode(i)) },
        // depth 2 = 最远层, 混着一袋盐
        { depth: 2, nodes: [cattleNode(3), cattleNode(4), cattleNode(5), SEASONING_NODE] },
      ],
      descendants: null,
      maxDepth: 2,
      nodeCount: 6,
      sourceIndividualCount: 5,
    },
  });
}

/** ③ 没打过标签。 */
export function noLabelTrace(): FullTraceResponse {
  return baseTrace({ traceCode: null });
}

/** ⑤ 三个空数组。 */
export function noSectionsTrace(): FullTraceResponse {
  return baseTrace({ processingStages: [], qualityInspections: [], shipments: [] });
}

export function individualDetail(overrides: Partial<SourceIndividualDetail> = {}): SourceIndividualDetail {
  return {
    individual: {
      id: 'si-2',
      factoryId: 'F006',
      individualCode: 'SL-002',
      tagNumber: '12345672',
      species: '牛',
      breed: '西门塔尔',
      sex: '母',
      birthDate: '2024-03-01',
      originFarm: '胜龙一号场',
      dnaVerificationStatus: 'MATCHED',
      sireIndividualId: 'si-sire',
      damIndividualId: null,
    },
    sire: null, // 登记过 si-sire 而档案不可查
    dam: null,  // 没登记
    offspring: [],
    siblingsBySire: [
      { id: 'si-9', individualCode: 'SL-009', tagNumber: '99999999', species: '牛', dnaVerificationStatus: 'UNSAMPLED' },
    ],
    batches: [{ id: 'mb-2', batchNumber: 'MB-2026-002', materialName: '牛胴体', quantity: 250, unit: 'kg', receiptDate: '2026-09-09' }],
    downstream: {
      productionBatches: [{ nodeType: 'PRODUCTION_BATCH', nodeId: 'pb-1', batchNumber: 'PB-2026-001', name: '冷鲜牛腩', resolved: true }],
      finishedBatches: [],
      shipments: [],
    },
    ...overrides,
  };
}
