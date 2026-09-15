/**
 * 「本批来自 N 头牛」横幅 + 节点上的个体信息 —— 跑在**真实屏幕**上。
 *
 * ⚠️ 为什么对着 `TraceabilityDetailScreen` 而不是 `PublicTraceScreen` 写：
 * 后者在 App 里**到不了** —— 全仓零 `navigate('PublicTrace')`、无 linking 配置，
 * 它只在 `ProcessingStackNavigator` 里注册了一个路由名。演示实际走的是
 * ProcessingDashboard → 溯源查询 → `TraceabilityScreen` 输入批次号 → 这一屏。
 * 把断言写在一个用户到不了的屏上，绿了也证明不了功能可用（形态 B：机制在、没接上）。
 *
 * ⚠️ 每条阴性断言都配了一条「必发生」的读数（硬约束 9）：
 * 断言横幅**不出现**时，同时断言来源链本身渲染出来了 —— 否则「找不到横幅」
 * 在「整屏没渲染 / 数据没加载」时同样成立，是个恒真式。
 */
import React from 'react';
import { render, waitFor } from '@testing-library/react-native';

import TraceabilityDetailScreen from '../../screens/traceability/TraceabilityDetailScreen';
import { SOURCE_INDIVIDUAL_BANNER_TEST_ID } from '../../screens/traceability/components/SourceIndividualBanner';
import { LINEAGE_TIMELINE_TEST_ID } from '../../screens/traceability/components/LineageChainTimeline';
import type {
  FullTraceResponse,
  LineageSection,
} from '../../services/api/traceabilityApiClient';

const BATCH = 'CLK-W-20260815-32540';

const mockGetFullTrace = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: { batchNumber: 'CLK-W-20260815-32540' } }),
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
}));

jest.mock('../../services/api/traceabilityApiClient', () => ({
  traceabilityApiClient: {
    getFullTrace: (...args: unknown[]) => mockGetFullTrace(...args),
  },
}));

function lineageWithIndividuals(): LineageSection {
  return {
    rootType: 'PRODUCTION_BATCH',
    rootId: '8967',
    maxDepth: 5,
    nodeCount: 2,
    sourceIndividualCount: 2,
    ancestors: [
      {
        depth: 1,
        nodes: [
          {
            nodeType: 'PRODUCTION_BATCH',
            nodeId: '8100',
            batchNumber: 'PB-8100',
            name: '分割半成品',
            resolved: true,
          },
        ],
      },
      {
        // 最远那一层：全是带个体的原料批次 ⇒ 判据成立
        depth: 2,
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
              tagNumber: '*********7788',
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

function traceResponse(lineage: LineageSection | null): FullTraceResponse {
  return {
    production: {
      batchNumber: BATCH,
      productName: '冻猪蹄',
      productType: 'PT-1',
      productionDate: '2026-08-15',
      qualityStatus: 'PASSED',
      factoryName: '六膳门食品科技',
      factoryId: 'F006',
    },
    materials: [],
    processingStages: [],
    qualityInspections: [],
    shipments: [],
    traceCode: 'TRACE-X',
    queryTime: '2026-09-15T00:00:00',
    lineage,
  } as FullTraceResponse;
}

beforeEach(() => {
  mockGetFullTrace.mockReset();
});

describe('TraceabilityDetailScreen 源头个体', () => {
  it('最远祖先层全是带个体的原料 ⇒ 顶部出现「本批来自 2 头牛」', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse(lineageWithIndividuals()));

    const { getByTestId, getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId(SOURCE_INDIVIDUAL_BANNER_TEST_ID)).toBeTruthy());
    // 🔴 N=2 而两头牛的脱敏耳标一模一样（*********7788）——
    //    这个数只能来自后端按真实 id 去重，前端算会得到 1。
    expect(getByText('本批来自 2 头牛')).toBeTruthy();
  });

  it('节点卡显示「品种 · 来源场」+ DNA 状态 chip 与脱敏耳标', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse(lineageWithIndividuals()));

    const { getByText, getAllByText, getByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId(LINEAGE_TIMELINE_TEST_ID)).toBeTruthy());
    expect(getByText('西门塔尔 · 胜龙一号牧场')).toBeTruthy();
    expect(getByText('安格斯 · 胜龙二号牧场')).toBeTruthy();
    // DNA 状态是每个节点上一颗 chip (统一色板), 不再拼进那行文字
    expect(getByTestId('lineage-individual-dna-mb-a')).toHaveTextContent('DNA 已比中');
    expect(getByTestId('lineage-individual-dna-mb-b')).toHaveTextContent('已采样');
    // 两头牛脱敏后长得一模一样 —— 钉住条数(2)而不是「至少一条」,
    // 这正是「N 不能在前端按耳标去重」的可见证据。
    expect(getAllByText('耳标：*********7788')).toHaveLength(2);
  });

  it('🔴 最远祖先层混着不带个体的原料 ⇒ 不出横幅（但来源链照常渲染）', async () => {
    const mixed = lineageWithIndividuals();
    // 把最远那层的第二个节点的个体摘掉 —— 链上混进了调料/包材那类没有个体的原料
    mixed.ancestors[1]!.nodes[1]!.sourceIndividual = null;
    mockGetFullTrace.mockResolvedValue(traceResponse(mixed));

    const { queryByTestId, getByTestId } = render(<TraceabilityDetailScreen />);

    // 「必发生」的那一半：来源链本身渲染出来了 ——
    // 没有它，「找不到横幅」在「整屏没加载」时同样成立。
    await waitFor(() => expect(getByTestId(LINEAGE_TIMELINE_TEST_ID)).toBeTruthy());
    expect(queryByTestId(SOURCE_INDIVIDUAL_BANNER_TEST_ID)).toBeNull();
  });

  it('🔴 没有任何个体 ⇒ 不出横幅, 节点上也不编「未知个体」', async () => {
    const plain = lineageWithIndividuals();
    plain.sourceIndividualCount = 0;
    plain.ancestors[1]!.nodes.forEach((n) => {
      n.sourceIndividual = null;
    });
    mockGetFullTrace.mockResolvedValue(traceResponse(plain));

    const { queryByTestId, getByTestId, queryByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId(LINEAGE_TIMELINE_TEST_ID)).toBeTruthy());
    expect(queryByTestId(SOURCE_INDIVIDUAL_BANNER_TEST_ID)).toBeNull();
    expect(queryByText('未知个体')).toBeNull();
    expect(queryByText(/耳标/)).toBeNull();
  });
});
