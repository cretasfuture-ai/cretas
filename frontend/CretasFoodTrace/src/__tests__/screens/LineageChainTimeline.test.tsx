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
import { render } from '@testing-library/react-native';

import LineageChainTimeline, {
  LINEAGE_EMPTY_TEST_ID,
  LINEAGE_EMPTY_TEXT,
  LINEAGE_TIMELINE_TEST_ID,
  LINEAGE_UNAVAILABLE_TEST_ID,
  LINEAGE_UNAVAILABLE_TEXT,
} from '../../screens/traceability/components/LineageChainTimeline';
import type { LineageSection } from '../../services/api/traceabilityApiClient';

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

    expect(getByText('mb-gone')).toBeTruthy(); // id 是我们真的知道的东西
    expect(getByText('该批次记录已不可查（血缘保留，批次已归档）')).toBeTruthy();
    expect(queryByText('未知批次')).toBeNull();
  });
});
