/**
 * 「来源链」时间线的三态契约。
 *
 * 这个组件守的性质只有一条，但它很容易写错：
 *
 *   ⚠️「本次没拿到」和「确实没有上游」必须是两句不同的话，而且**两种都不许隐藏**。
 *
 * 隐藏整张卡会让「没数据」和「功能坏了」都长成「这块不存在」——
 * 那正是 measurement-and-wiring 里「验证『没发生』最容易被伪造」的 UI 版本。
 *
 * ⚠️ 每条阴性断言都配了一条「一定会发生」的读数（标题「来源链」必须在），
 *    否则「找不到那段文案」在「组件根本没渲染」时同样成立 —— 恒真式。
 */
import React from 'react';
import { Text as RNText } from 'react-native';
import { fireEvent, render, within } from '@testing-library/react-native';

import LineageChainTimeline, {
  CHAIN_CONNECTOR_TEST_ID,
  ChainGroup,
  LINEAGE_EMPTY_TEST_ID,
  LINEAGE_EMPTY_TEXT,
  LINEAGE_TIMELINE_TEST_ID,
  LINEAGE_UNAVAILABLE_TEST_ID,
  LINEAGE_UNAVAILABLE_TEXT,
  UNNAMED_NODE_TEXT,
} from '../../screens/traceability/components/LineageChainTimeline';
import type { LineageSection } from '../../services/api/traceabilityApiClient';

// 时间线自己会 useNavigation()（内部侧挂了个体的原料行可点进个体详情）。
// 这里不起 NavigationContainer, 直接桩掉 navigate 并留一个可断言的 mock。
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn(), push: jest.fn() }),
}));

// 段标题 / 计数 / DNA chip 文案走 i18n（processing 命名空间）。真的 i18n 实例在单测里没初始化
// ⇒ 直接查 zh-CN 的 bundle，断言里的中文和用户屏上看到的是**同一个来源**。
jest.mock('react-i18next', () => {
  const bundle = require('../../i18n/locales/zh-CN/processing.json');
  const lookup = (key: string, opts?: Record<string, unknown>): string => {
    const raw = key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle);
    if (typeof raw !== 'string') return key;
    return raw.replace(/\{\{(\w+)\}\}/g, (_m, name) => String(opts?.[name] ?? ''));
  };
  return { useTranslation: () => ({ t: lookup }) };
});

const SECTION_TITLE = '来源链'; // 「一定会发生」的那个读数

function threeLayerLineage(): LineageSection {
  return {
    rootType: 'PRODUCTION_BATCH',
    rootId: '8967',
    maxDepth: 5,
    nodeCount: 3,
    ancestors: [
      {
        depth: 1,
        nodes: [
          {
            nodeType: 'MATERIAL_BATCH',
            nodeId: 'mb-near',
            batchNumber: 'MB-NEAR-001',
            name: '牛腩',
            supplierName: '胜龙牛业',
            quantity: 12.5,
            unit: 'kg',
            resolved: true,
          },
        ],
      },
      {
        depth: 2,
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
        depth: 3,
        nodes: [
          {
            nodeType: 'MATERIAL_BATCH',
            nodeId: 'mb-far',
            batchNumber: 'MB-FAR-001',
            name: '整牛',
            supplierName: '胜龙牛业',
            resolved: true,
          },
        ],
      },
    ],
  };
}

describe('LineageChainTimeline 三态', () => {
  it('有 3 层来源链 ⇒ 三层都渲染出来，顺序由远到近', () => {
    const { getByTestId, getByText, queryByTestId } = render(
      <LineageChainTimeline lineage={threeLayerLineage()} showSupplier />
    );

    expect(getByTestId(LINEAGE_TIMELINE_TEST_ID)).toBeTruthy();
    expect(getByText(SECTION_TITLE)).toBeTruthy();
    // 三层各自存在（只数「有 3 个分组」不够 —— 这里逐层按 depth 找）
    expect(getByTestId('lineage-depth-1')).toBeTruthy();
    expect(getByTestId('lineage-depth-2')).toBeTruthy();
    expect(getByTestId('lineage-depth-3')).toBeTruthy();
    expect(getByTestId('lineage-node-mb-far')).toBeTruthy();
    expect(getByTestId('lineage-node-8100')).toBeTruthy();
    expect(getByTestId('lineage-node-mb-near')).toBeTruthy();
    // 有链的时候不许同时出现空态/故障态
    expect(queryByTestId(LINEAGE_EMPTY_TEST_ID)).toBeNull();
    expect(queryByTestId(LINEAGE_UNAVAILABLE_TEST_ID)).toBeNull();
  });

  it('showSupplier=true ⇒ 显示供应商；false ⇒ 不显示（公开页的第二道闸）', () => {
    const internal = render(<LineageChainTimeline lineage={threeLayerLineage()} showSupplier />);
    // 两个原料节点都挂同一家供应商 ⇒ 用 getAllByText, 并钉住条数(2 条, 不是「至少一条」)
    expect(internal.getAllByText('供应商：胜龙牛业')).toHaveLength(2);

    const publicView = render(<LineageChainTimeline lineage={threeLayerLineage()} />);
    // 阴性断言 + 必发生读数：标题还在，说明组件确实渲染了，只是供应商没出来。
    expect(publicView.getByText(SECTION_TITLE)).toBeTruthy();
    expect(publicView.queryAllByText('供应商：胜龙牛业')).toHaveLength(0);
  });

  it('ancestors 为空 ⇒ 显示「无上游血缘记录」，⛔ 不隐藏整张卡', () => {
    const { getByTestId, getByText } = render(
      <LineageChainTimeline
        lineage={{ rootType: 'PRODUCTION_BATCH', rootId: '8967', maxDepth: 5, nodeCount: 0, ancestors: [] }}
      />
    );

    expect(getByText(SECTION_TITLE)).toBeTruthy(); // 卡还在
    expect(getByTestId(LINEAGE_EMPTY_TEST_ID)).toBeTruthy();
    expect(getByText(LINEAGE_EMPTY_TEXT)).toBeTruthy();
  });

  it('lineage=null（本次没拿到）⇒ 文案与空态不同，且不谎称「没有上游」', () => {
    const { getByTestId, getByText, queryByText } = render(<LineageChainTimeline lineage={null} />);

    expect(getByText(SECTION_TITLE)).toBeTruthy();
    expect(getByTestId(LINEAGE_UNAVAILABLE_TEST_ID)).toBeTruthy();
    expect(getByText(LINEAGE_UNAVAILABLE_TEXT)).toBeTruthy();
    // 🔴 这一条是本文件的核心：故障态绝不能显示成「无上游血缘记录」。
    expect(queryByText(LINEAGE_EMPTY_TEXT)).toBeNull();
    expect(LINEAGE_UNAVAILABLE_TEXT).not.toEqual(LINEAGE_EMPTY_TEXT);
  });

  it('节点实体查不到（resolved=false）⇒ 说实话，⛔ 不编批次号也不编名字', () => {
    const { getByText, queryByText } = render(
      <LineageChainTimeline
        lineage={{
          rootType: 'PRODUCTION_BATCH',
          rootId: '8967',
          maxDepth: 5,
          nodeCount: 1,
          ancestors: [
            {
              depth: 1,
              nodes: [
                {
                  nodeType: 'MATERIAL_BATCH',
                  nodeId: 'mb-gone',
                  batchNumber: null,
                  name: null,
                  resolved: false,
                },
              ],
            },
          ],
        }}
        showSupplier
      />
    );

    expect(getByText('该批次记录已不可查（血缘保留，批次已归档）')).toBeTruthy();
    expect(queryByText('未知批次')).toBeNull();
  });

  it('🔴 公开侧 nodeId=null ⇒ 用 batchNumber 展示；缺 batchNumber 也⛔不回显内部主键', () => {
    // 后端在 desensitize 路径上把 nodeId 置成了 null(它是内部数据库主键,
    // PRODUCTION_BATCH 那一类还是自增整数)。前端原来拿它当兜底标签, 等于又渲染回屏幕上。
    const { getByText, queryByText } = render(
      <LineageChainTimeline
        lineage={{
          rootType: 'PRODUCTION_BATCH',
          rootId: '8967',
          maxDepth: 5,
          nodeCount: 2,
          ancestors: [
            {
              depth: 1,
              nodes: [
                { nodeType: 'MATERIAL_BATCH', nodeId: null, batchNumber: 'MB-PUB-001',
                  name: '牛腩', resolved: true },
                { nodeType: 'PRODUCTION_BATCH', nodeId: null, batchNumber: null,
                  name: null, resolved: true },
              ],
            },
          ],
        }}
      />
    );

    // 「必发生」的那一半：有 batchNumber 的节点照常显示 —— 没有它,
    // 「屏幕上找不到内部 id」在「整个组件没渲染」时也成立。
    expect(getByText('MB-PUB-001')).toBeTruthy();
    // 没有批次号时如实说，不回显任何 id
    expect(getByText(UNNAMED_NODE_TEXT)).toBeTruthy();
  });

  it('🔴 内部页上 nodeId 存在, 但它也不该被当成标题兜底（标题只认 batchNumber）', () => {
    const { getByText, queryByText } = render(
      <LineageChainTimeline
        lineage={{
          rootType: 'PRODUCTION_BATCH',
          rootId: '8967',
          maxDepth: 5,
          nodeCount: 1,
          ancestors: [
            {
              depth: 1,
              nodes: [
                { nodeType: 'PRODUCTION_BATCH', nodeId: '8100', batchNumber: null,
                  name: null, resolved: true },
              ],
            },
          ],
        }}
        showSupplier
      />
    );

    expect(getByText(UNNAMED_NODE_TEXT)).toBeTruthy();
    // 这条是会红的那一条：把 `node.batchNumber || node.nodeId` 写回去, 屏幕上就会出现 8100。
    expect(queryByText('8100')).toBeNull();
  });
});

describe('挂了个体的原料行 → 个体详情', () => {
  beforeEach(() => mockNavigate.mockClear());

  function lineageWithIndividual(id: string | null): LineageSection {
    return {
      rootType: 'PRODUCTION_BATCH',
      rootId: '8967',
      maxDepth: 5,
      nodeCount: 1,
      sourceIndividualCount: 1,
      ancestors: [
        {
          depth: 1,
          nodes: [
            {
              nodeType: 'MATERIAL_BATCH',
              nodeId: id ? 'mb-near' : null,
              batchNumber: 'MB-NEAR-001',
              name: '整牛',
              resolved: true,
              sourceIndividual: {
                id,
                // 公开侧: 编码 null、耳标遮住; 内部侧: 全给。两种形状都照后端真实输出。
                individualCode: id ? 'SL-CATTLE-005' : null,
                tagNumber: id ? '1100000000005' : '*********0005',
                tagNumberMasked: !id,
                species: '牛',
                breed: '西门塔尔',
                originFarm: '胜龙一号牧场',
                dnaVerificationStatus: 'SAMPLED',
              },
            },
          ],
        },
      ],
    };
  }

  it('内部侧（后端给了个体 id）⇒ 整行可点、右侧有「可进」的 chevron，按下去 navigate 到 SourceIndividualDetail 并带 id', () => {
    const { getByTestId, getByText } = render(
      <LineageChainTimeline lineage={lineageWithIndividual('si-005')} showSupplier />
    );

    // 「必发生」读数: 个体信息本身渲染出来了 (否则下面找不到 chevron 是恒真式)
    expect(getByText('西门塔尔 · 胜龙一号牧场')).toBeTruthy();
    // DNA 状态不再拼进那行文字里 —— 它是一颗走统一色板的 chip (颜色由 DnaStatusPalette 测试钉)
    expect(getByTestId('lineage-individual-dna-mb-near')).toHaveTextContent('已采样');
    // 2026-09-15 polish: 「查看个体档案 ›」那行蓝字没了, 「可进」由整行 ripple + chevron 表达。
    // 守性质不守字面: chevron 在、整行按下去导航。
    expect(getByTestId('lineage-node-chevron-mb-near')).toBeTruthy();

    fireEvent.press(getByTestId('lineage-node-mb-near'));
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('SourceIndividualDetail', { individualId: 'si-005' });
  });

  it('公开侧（id 为 null）⇒ 个体信息照常显示，但没有 chevron、按了也不导航', () => {
    const { getByText, queryByTestId, getByTestId } = render(
      <LineageChainTimeline lineage={lineageWithIndividual(null)} />
    );

    // 必发生: 品种/来源场/DNA 仍然显示 —— 这是消费者扫码要看的
    expect(getByText('西门塔尔 · 胜龙一号牧场')).toBeTruthy();
    expect(getByTestId('lineage-individual-dna-MB-NEAR-001')).toHaveTextContent('已采样');
    expect(getByText('耳标：*********0005')).toBeTruthy();
    // 阴性: 没有「可进」的示意 (公开侧没有个体主键, 没地方可进)
    expect(queryByTestId('lineage-node-chevron-MB-NEAR-001')).toBeNull();

    fireEvent.press(getByTestId('lineage-node-MB-NEAR-001'));
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});

// ── 2026-09-16 评审：措辞去图论词、圆点之间的竖线要连续 ─────────────────────────────

/** RN 的 style 可能是数组 / 嵌套数组 —— 摊平成一个对象再读键。 */
function flatten(style: unknown): Record<string, unknown> {
  if (style == null || typeof style === 'boolean') return {};
  if (Array.isArray(style)) return Object.assign({}, ...style.map(flatten));
  return style as Record<string, unknown>;
}

describe('措辞：老板听得懂的话，不是图论词', () => {
  it('段标题「直接来源」「往上第 N 层来源」、计数「来源 N 项」；⛔ 不再出现「上游节点 / 直接上游 / 关联边」', () => {
    const { getByText, queryByText } = render(<LineageChainTimeline lineage={threeLayerLineage()} showSupplier />);

    // 必发生：三段标题 + 计数都渲染出来（否则下面的阴性断言是恒真式）
    expect(getByText('直接来源')).toBeTruthy();
    expect(getByText('往上第 2 层来源')).toBeTruthy();
    expect(getByText('往上第 3 层来源')).toBeTruthy();
    expect(getByText('来源 3 项')).toBeTruthy(); // N = nodeCount(3)，后端给的，不在前端数

    // 阴性：旧措辞一个都不许回来
    expect(queryByText(/上游节点/)).toBeNull();
    expect(queryByText(/直接上游/)).toBeNull();
    expect(queryByText(/关联边/)).toBeNull();
    expect(queryByText(/跳/)).toBeNull();
  });

  it('nodeCount=0 ⇒ 不显示计数（「来源 0 项」是句废话）', () => {
    const { queryByText, getByText } = render(
      <LineageChainTimeline
        lineage={{ rootType: 'PRODUCTION_BATCH', rootId: '8967', maxDepth: 5, nodeCount: 0, ancestors: [] }}
      />
    );
    expect(getByText(SECTION_TITLE)).toBeTruthy();
    expect(queryByText(/来源 \d+ 项/)).toBeNull();
  });
});

describe('圆点之间的竖线连续（个体档案「去了哪」与来源链共用同一个 ChainGroup）', () => {
  it('3 段 ⇒ 2 根连线（= 段数 − 1）；每根都是上下锚在圆点上的绝对定位线，⛔ 不是写死高度的短线', () => {
    const { getAllByTestId, getByTestId } = render(
      <LineageChainTimeline lineage={threeLayerLineage()} showSupplier />
    );

    const connectors = getAllByTestId(CHAIN_CONNECTOR_TEST_ID);
    expect(connectors).toHaveLength(2);

    for (const c of connectors) {
      const s = flatten(c.props.style);
      // 连续的全部含义：段有多高线就多长 ⇒ 没有 height，靠 top/bottom 两端锚定
      expect(s.position).toBe('absolute');
      expect(s.height).toBeUndefined();
      expect(typeof s.top).toBe('number');
      expect(typeof s.bottom).toBe('number');
      // 下端越过本段底边（bottom 为负）—— 这样才碰得到下一段的圆点，而不是停在段与段之间
      expect(s.bottom as number).toBeLessThan(0);
      // 上端与下端对称：从本段圆点中心到下一段圆点中心
      expect(s.top).toBe(-(s.bottom as number));
      expect(typeof s.backgroundColor).toBe('string');
    }

    // 最后一段（depth 1，排最下面）里没有连线；第一段（depth 3）里有
    expect(within(getByTestId('lineage-depth-1')).queryByTestId(CHAIN_CONNECTOR_TEST_ID)).toBeNull();
    expect(within(getByTestId('lineage-depth-3')).getByTestId(CHAIN_CONNECTOR_TEST_ID)).toBeTruthy();
  });

  it('只有 1 段 ⇒ 0 根连线；单独渲染 ChainGroup：last=false 有线、last=true 没有', () => {
    const one = render(
      <LineageChainTimeline
        lineage={{
          ...threeLayerLineage(),
          nodeCount: 1,
          ancestors: [threeLayerLineage().ancestors[0]!],
        }}
        showSupplier
      />
    );
    expect(one.getByTestId('lineage-depth-1')).toBeTruthy(); // 必发生：那一段真的渲染了
    expect(one.queryAllByTestId(CHAIN_CONNECTOR_TEST_ID)).toHaveLength(0);
    one.unmount();

    const open = render(
      <ChainGroup label="原料批次" testID="g-open">
        <RNText>row</RNText>
      </ChainGroup>
    );
    expect(open.getByText('原料批次')).toBeTruthy();
    expect(open.getAllByTestId(CHAIN_CONNECTOR_TEST_ID)).toHaveLength(1);
    open.unmount();

    const closed = render(
      <ChainGroup label="出货单" testID="g-closed" last>
        <RNText>row</RNText>
      </ChainGroup>
    );
    expect(closed.getByText('出货单')).toBeTruthy();
    expect(closed.queryAllByTestId(CHAIN_CONNECTOR_TEST_ID)).toHaveLength(0);
  });
});
