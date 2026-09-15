/**
 * 溯源详情去噪（FEAT-RN-TRACE-DENOISE-AND-HOME-GROUPS-20260915 / P1）—— 跑在**真实屏幕**上。
 *
 * 守的行为（每条阴性断言都配一条同屏「必发生」的读数，硬约束 9）：
 * ① 有多跳祖先 ⇒ 一跳「原材料溯源」不再列一遍（横幅 + 来源链在）
 * ② 没有血缘（null / ancestors 空）⇒ 退回一跳列表
 * ③ qualityStatus 走 QUALITY_STATUS_LABEL；未知值**原样**显示，⛔ 不翻成「未知」
 * ④ 加工/质检/出货 为空 ⇒ 整卡不渲染，底部一行「暂无…记录」只列真正为空的
 * ⑤ 生产信息里空值行（负责人/设备）不渲染，⛔ 没有占位横杠
 * ⑥ 一跳列表里后端兜的「未知供应商」不渲染成一行
 * ⑦ 溯源码卡：批次号在下面一行；路由带了不同的标签码时两个码都显示并各自贴标签
 */
import React from 'react';
import { render, waitFor } from '@testing-library/react-native';

import TraceabilityDetailScreen, {
  QUALITY_STATUS_LABEL,
  qualityStatusLabel,
  emptySectionsLine,
} from '../../screens/traceability/TraceabilityDetailScreen';
import { SOURCE_INDIVIDUAL_BANNER_TEST_ID } from '../../screens/traceability/components/SourceIndividualBanner';
import {
  LINEAGE_TIMELINE_TEST_ID,
  LINEAGE_EMPTY_TEST_ID,
  LINEAGE_UNAVAILABLE_TEST_ID,
} from '../../screens/traceability/components/LineageChainTimeline';
import type {
  FullTraceResponse,
  LineageSection,
  MaterialInfo,
} from '../../services/api/traceabilityApiClient';

const BATCH = 'SL-DEMO-BATCH-V1';
const SYSTEM_CODE = 'TR-CATTLE_3701_001-20260915-8125';
const LABEL_CODE = 'TRACE-SL-DEMO-BATCH-V1-SEED01';

const mockGetFullTrace = jest.fn();
// jest.mock 工厂会被提升，只能引用 mock 前缀的变量 —— 用一个可变容器让每条用例改路由参数。
const mockRoute: { params: Record<string, unknown> } = { params: { batchNumber: BATCH } };

jest.mock('@react-navigation/native', () => ({
  useRoute: () => mockRoute,
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
}));

// 2026-09-16 起屏幕的标题栏走 useTranslation('common')；单测里没有 i18n 实例，
// 直接查 zh-CN 的 common bundle（同 SourceIndividualDetailScreen.test 的做法）。
jest.mock('react-i18next', () => {
  const bundle = require('../../i18n/locales/zh-CN/common.json');
  const lookup = (key: string): string => {
    const raw = key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle);
    return typeof raw === 'string' ? raw : key;
  };
  return { useTranslation: () => ({ t: lookup }) };
});

jest.mock('../../services/api/traceabilityApiClient', () => ({
  traceabilityApiClient: {
    getFullTrace: (...args: unknown[]) => mockGetFullTrace(...args),
  },
}));

function lineageWithAncestors(): LineageSection {
  return {
    rootType: 'PRODUCTION_BATCH',
    rootId: '8125',
    maxDepth: 5,
    nodeCount: 2,
    sourceIndividualCount: 2,
    ancestors: [
      {
        depth: 1,
        nodes: [
          {
            nodeType: 'MATERIAL_BATCH',
            nodeId: 'mb-a',
            batchNumber: 'MB-A-001',
            name: '整牛',
            resolved: true,
            sourceIndividual: {
              tagNumber: '*********7788',
              tagNumberMasked: true,
              species: '牛',
              breed: '西门塔尔',
              originFarm: '胜龙一号牧场',
              dnaVerificationStatus: 'MATCHED',
            },
          },
          {
            nodeType: 'MATERIAL_BATCH',
            nodeId: 'mb-b',
            batchNumber: 'MB-B-001',
            name: '整牛',
            resolved: true,
            sourceIndividual: {
              tagNumber: '*********1234',
              tagNumberMasked: true,
              species: '牛',
              breed: '安格斯',
              originFarm: '胜龙二号牧场',
              dnaVerificationStatus: 'SAMPLED',
            },
          },
        ],
      },
    ],
  };
}

function material(overrides: Partial<MaterialInfo> = {}): MaterialInfo {
  return {
    batchNumber: 'MB-A-001',
    materialName: '整牛',
    materialType: '主料',
    supplierName: '未知供应商', // 后端 buildMaterialInfoList 在没有 supplierId 时兜出来的字面串
    receiptDate: '2026-09-10',
    quantity: 1,
    unit: '头',
    status: 'CONSUMED',
    ...overrides,
  };
}

function traceResponse(overrides: Partial<FullTraceResponse> = {}): FullTraceResponse {
  return {
    production: {
      batchNumber: BATCH,
      productName: '分割牛肉',
      productType: 'PT-1',
      productionDate: '2026-09-15',
      qualityStatus: 'PENDING',
      factoryName: '胜龙牛业',
      factoryId: 'CATTLE_3701_001',
    },
    materials: [material()],
    processingStages: [],
    qualityInspections: [],
    shipments: [],
    traceCode: SYSTEM_CODE,
    queryTime: '2026-09-15T00:00:00',
    lineage: lineageWithAncestors(),
    ...overrides,
  } as FullTraceResponse;
}

beforeEach(() => {
  mockGetFullTrace.mockReset();
  mockRoute.params = { batchNumber: BATCH };
});

describe('① 有多跳祖先时不再列一跳「原材料溯源」', () => {
  it('横幅 + 来源链在；「原材料溯源」标题与一跳卡都不在', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByTestId, queryByText, queryByTestId } = render(<TraceabilityDetailScreen />);

    // 「必发生」：横幅与来源链渲染出来了 —— 没有它们，「找不到一跳列表」在整屏没加载时同样成立
    await waitFor(() => expect(getByTestId(SOURCE_INDIVIDUAL_BANNER_TEST_ID)).toBeTruthy());
    expect(getByTestId(LINEAGE_TIMELINE_TEST_ID)).toBeTruthy();

    expect(queryByText('原材料溯源')).toBeNull();
    expect(queryByTestId('materials-one-hop-card')).toBeNull();
  });
});

describe('② 没有血缘时退回一跳列表', () => {
  it('lineage = null（引擎没拿到）⇒ 一跳列表出现，且来源链卡显示「取不到」', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse({ lineage: null }));
    const { getByTestId, getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('materials-one-hop-card')).toBeTruthy());
    expect(getByText('原材料溯源')).toBeTruthy();
    expect(getByText('整牛')).toBeTruthy();
    expect(getByTestId(LINEAGE_UNAVAILABLE_TEST_ID)).toBeTruthy();
  });

  it('ancestors = []（确实没有上游）⇒ 一跳列表出现，且来源链卡显示「无上游」', async () => {
    const empty = lineageWithAncestors();
    empty.ancestors = [];
    empty.nodeCount = 0;
    empty.sourceIndividualCount = 0;
    mockGetFullTrace.mockResolvedValue(traceResponse({ lineage: empty }));
    const { getByTestId, getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('materials-one-hop-card')).toBeTruthy());
    expect(getByText('原材料溯源')).toBeTruthy();
    expect(getByTestId(LINEAGE_EMPTY_TEST_ID)).toBeTruthy();
  });
});

describe('③ 质量状态映射', () => {
  it('PENDING ⇒「待检验」', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByText, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('质量状态')).toBeTruthy());
    expect(getByText('待检验')).toBeTruthy();
    expect(queryByText('PENDING')).toBeNull();
  });

  it('🔴 未知值 WHATEVER 原样显示，⛔ 不翻成「未知」', async () => {
    mockGetFullTrace.mockResolvedValue(
      traceResponse({ production: { ...traceResponse().production, qualityStatus: 'WHATEVER' } }),
    );
    const { getByText, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('质量状态')).toBeTruthy());
    expect(getByText('WHATEVER')).toBeTruthy();
    expect(queryByText('未知')).toBeNull();
  });

  it('映射表覆盖后端 QualityStatus 全部 11 个枚举名 + PENDING 占位；纯函数对未知值透传', () => {
    // 与 backend entity/enums/QualityStatus.java 逐一对上 —— 少一个就是后端加了值前端没跟
    const backendEnum = [
      'PENDING_INSPECTION', 'INSPECTING', 'NEEDS_REVIEW', 'PASSED', 'QUALIFIED', 'FAILED',
      'PARTIAL_PASS', 'REWORK_REQUIRED', 'REWORKING', 'REWORK_COMPLETED', 'SCRAPPED',
    ];
    for (const k of backendEnum) expect(QUALITY_STATUS_LABEL).toHaveProperty(k);
    expect(QUALITY_STATUS_LABEL).toHaveProperty('PENDING');
    expect(Object.keys(QUALITY_STATUS_LABEL)).toHaveLength(backendEnum.length + 1);

    expect(qualityStatusLabel('passed')).toBe('已通过'); // 大小写不敏感
    expect(qualityStatusLabel('WHATEVER')).toBe('WHATEVER');
    expect(qualityStatusLabel('')).toBeNull();
    expect(qualityStatusLabel(null)).toBeNull();
  });
});

describe('④ 空卡折叠', () => {
  it('三段全空 ⇒ 一行「暂无加工/质检/出货记录」，三个卡标题都不出现', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByTestId, getByText, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('empty-sections-line')).toBeTruthy());
    expect(getByText('暂无加工/质检/出货记录')).toBeTruthy();
    // 「必发生」：生产信息那张卡在 —— 证明不是整屏空白
    expect(getByText('生产信息')).toBeTruthy();
    expect(queryByText('加工环节')).toBeNull();
    expect(queryByText('质检记录')).toBeNull();
    expect(queryByText('出货记录')).toBeNull();
  });

  it('只有出货为空 ⇒ 「暂无出货记录」，加工/质检两张卡照常', async () => {
    mockGetFullTrace.mockResolvedValue(
      traceResponse({
        processingStages: [
          { stageType: 'SLAUGHTER', stageName: '屠宰', stageOrder: 1 } as any,
        ],
        qualityInspections: [
          {
            inspectionId: 'QI-00000001',
            inspectionDate: '2026-09-15T08:00:00',
            inspectorName: '质检员甲',
            result: 'PASSED',
          },
        ],
        shipments: [],
      }),
    );
    const { getByTestId, getByText, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('empty-sections-line')).toBeTruthy());
    expect(getByText('暂无出货记录')).toBeTruthy();
    expect(getByText('加工环节')).toBeTruthy();
    expect(getByText('质检记录')).toBeTruthy();
    expect(queryByText('出货记录')).toBeNull();
  });

  it('一段都不空 ⇒ 那一行不出现', async () => {
    mockGetFullTrace.mockResolvedValue(
      traceResponse({
        processingStages: [{ stageType: 'SLAUGHTER', stageName: '屠宰', stageOrder: 1 } as any],
        qualityInspections: [
          { inspectionId: 'QI-1', inspectionDate: '2026-09-15', inspectorName: '甲', result: 'PASSED' },
        ],
        shipments: [
          { shipmentNumber: 'SH-1', shipmentDate: '2026-09-16', customerName: '客户A', status: 'shipped' },
        ],
      }),
    );
    const { getByText, queryByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('出货记录')).toBeTruthy());
    expect(queryByTestId('empty-sections-line')).toBeNull();
  });

  it('纯函数：只列真正为空的那些，顺序固定', () => {
    expect(emptySectionsLine([])).toBeNull();
    expect(emptySectionsLine(['shipments'])).toBe('暂无出货记录');
    expect(emptySectionsLine(['processingStages', 'shipments'])).toBe('暂无加工/出货记录');
    expect(emptySectionsLine(['processingStages', 'qualityInspections', 'shipments'])).toBe('暂无加工/质检/出货记录');
  });
});

describe('⑤ 生产信息空值行不渲染', () => {
  it('负责人 / 设备 缺失 ⇒ 整行不出现，也没有占位横杠', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByText, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('生产信息')).toBeTruthy());
    expect(getByText('产品名称')).toBeTruthy(); // 「必发生」：有值的行在
    expect(queryByText('负责人')).toBeNull();
    expect(queryByText('设备')).toBeNull();
    expect(queryByText('-')).toBeNull();
  });

  it('阳性对照：负责人 / 设备 有值 ⇒ 两行都在', async () => {
    mockGetFullTrace.mockResolvedValue(
      traceResponse({
        production: { ...traceResponse().production, supervisorName: '张三', equipmentName: '分割线 A' },
      }),
    );
    const { getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('负责人')).toBeTruthy());
    expect(getByText('张三')).toBeTruthy();
    expect(getByText('设备')).toBeTruthy();
    expect(getByText('分割线 A')).toBeTruthy();
  });
});

describe('⑥ 一跳列表里的「未知供应商」', () => {
  it('后端兜的字面串 ⇒ 供应商那一行不出现（其它行照常）', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse({ lineage: null }));
    const { getByText, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('原材料溯源')).toBeTruthy());
    expect(getByText('入库日期: 2026-09-10')).toBeTruthy(); // 「必发生」
    expect(queryByText(/供应商/)).toBeNull();
    expect(queryByText(/自有牧场/)).toBeNull(); // ⛔ 不许前端再造一个假值顶上
  });

  it('阳性对照：真实供应商 ⇒ 那一行在', async () => {
    mockGetFullTrace.mockResolvedValue(
      traceResponse({ lineage: null, materials: [material({ supplierName: '鲁西黄牛合作社' })] }),
    );
    const { getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('原材料溯源')).toBeTruthy());
    expect(getByText('供应商: 鲁西黄牛合作社')).toBeTruthy();
  });
});

describe('⑦ 溯源码卡', () => {
  it('路由没带标签码 ⇒ 只有「溯源码」+ 系统码，下面一行批次号', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByText, getByTestId, queryByText, queryByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-code-card')).toBeTruthy());
    expect(getByText('溯源码')).toBeTruthy();
    expect(getByTestId('trace-code-system')).toHaveTextContent(SYSTEM_CODE);
    expect(getByTestId('trace-code-batch')).toHaveTextContent(`批次号 ${BATCH}`);
    expect(queryByText('扫入码')).toBeNull();
    expect(queryByTestId('trace-code-missing')).toBeNull();
    expect(queryByTestId('trace-code-label')).toBeNull();
  });

  it('路由带了与服务端不同的码 ⇒ 两个都显示并标「扫入码 / 标签码」', async () => {
    mockRoute.params = { batchNumber: BATCH, traceCode: LABEL_CODE };
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByText, getByTestId, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-code-label')).toBeTruthy());
    expect(getByText('扫入码')).toBeTruthy();
    expect(getByText('标签码')).toBeTruthy();
    expect(getByTestId('trace-code-label')).toHaveTextContent(LABEL_CODE);
    expect(getByTestId('trace-code-system')).toHaveTextContent(SYSTEM_CODE);
    expect(queryByText('溯源码')).toBeNull();
  });

  it('路由带的码与服务端相同 ⇒ 不重复显示', async () => {
    mockRoute.params = { batchNumber: BATCH, traceCode: SYSTEM_CODE };
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByText, getByTestId, queryByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-code-card')).toBeTruthy());
    expect(getByText('溯源码')).toBeTruthy();
    expect(queryByTestId('trace-code-label')).toBeNull();
  });

  it('服务端没有持久化标签码(null) ⇒ 显示「未打印标签」, ⛔ 不显示任何码', async () => {
    mockRoute.params = { batchNumber: BATCH };
    mockGetFullTrace.mockResolvedValue({ ...traceResponse(), traceCode: null });
    const { getByTestId, queryByTestId, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-code-missing')).toBeTruthy());
    expect(getByTestId('trace-code-missing')).toHaveTextContent('未打印标签');
    expect(queryByTestId('trace-code-system')).toBeNull();
    expect(queryByTestId('trace-code-label')).toBeNull();
    expect(queryByText('溯源码')).toBeNull();
    expect(getByTestId('trace-code-batch')).toHaveTextContent(`批次号 ${BATCH}`);
  });
});
