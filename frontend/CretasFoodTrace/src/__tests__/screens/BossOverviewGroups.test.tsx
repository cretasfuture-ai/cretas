/**
 * 经营总览首页分组（FEAT-RN-TRACE-DENOISE-AND-HOME-GROUPS-20260915 / P2）。
 *
 * 守的是**顺序**，不是「有没有那几个字」：
 * 「现场作业」组（溯源查询 / 拍照核验 / 源头个体）必须排在「经营」组
 * （生产进度 / 销售订单 / 采购订单 / 成品库存）**前面**。
 * 三个入口的 testID 与 navigate 目标由 `gates/factoryBossReachesFieldEntries` 钉着，这里不重复。
 */
import React from 'react';
import { render } from '@testing-library/react-native';

import BossOverviewScreen from '../../screens/factory-admin/home/BossOverviewScreen';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), dispatch: jest.fn(), goBack: jest.fn() }),
}));

/** 按渲染顺序收集所有字符串叶子 —— 顺序断言的仪器。 */
function textLeavesInOrder(node: unknown, out: string[] = []): string[] {
  if (node == null || typeof node === 'boolean') return out;
  if (typeof node === 'string' || typeof node === 'number') {
    out.push(String(node));
    return out;
  }
  if (Array.isArray(node)) {
    node.forEach((n) => textLeavesInOrder(n, out));
    return out;
  }
  const children = (node as { children?: unknown }).children;
  if (children != null) textLeavesInOrder(children, out);
  return out;
}

/** RN 的 style 可能是数组 / 嵌套数组 —— 摊平成一个对象再读键。 */
function flatten(style: unknown): Record<string, unknown> {
  if (style == null || typeof style === 'boolean') return {};
  if (Array.isArray(style)) return Object.assign({}, ...style.map(flatten));
  return style as Record<string, unknown>;
}

/** 从这个节点起沿祖先向上，找最近一个 style 里带 `key` 的值。找不到返回 undefined。 */
function nearestStyle(node: unknown, key: string): unknown {
  let cur: any = node;
  while (cur) {
    const v = flatten(cur.props?.style)[key];
    if (v != null) return v;
    cur = cur.parent;
  }
  return undefined;
}

function indexOfLeaf(leaves: string[], label: string): number {
  const i = leaves.findIndex((s) => s === label);
  if (i < 0) throw new Error(`没渲染出「${label}」—— 顺序断言无从谈起: ${JSON.stringify(leaves)}`);
  return i;
}

describe('BossOverviewScreen 分组', () => {
  it('阳性对照：仪器真的按顺序读到了叶子（且不是恒空）', () => {
    const { toJSON } = render(<BossOverviewScreen />);
    const leaves = textLeavesInOrder(toJSON());
    expect(leaves.length).toBeGreaterThan(8);
    // 标题在按钮前 —— 证明「顺序」这个读数本身是活的
    expect(indexOfLeaf(leaves, '经营总览')).toBeLessThan(indexOfLeaf(leaves, '生产进度'));
  });

  it('「现场作业」组标题在前，「经营」组标题在后', () => {
    const { toJSON } = render(<BossOverviewScreen />);
    const leaves = textLeavesInOrder(toJSON());
    expect(indexOfLeaf(leaves, '现场作业')).toBeLessThan(indexOfLeaf(leaves, '经营'));
  });

  it('三个现场入口都落在「现场作业」与「经营」之间；四个经营入口都在「经营」之后', () => {
    const { toJSON } = render(<BossOverviewScreen />);
    const leaves = textLeavesInOrder(toJSON());
    const field = indexOfLeaf(leaves, '现场作业');
    const biz = indexOfLeaf(leaves, '经营');
    for (const label of ['溯源查询', '拍照核验', '源头个体']) {
      const i = indexOfLeaf(leaves, label);
      expect(i).toBeGreaterThan(field);
      expect(i).toBeLessThan(biz);
    }
    for (const label of ['生产进度', '销售订单', '采购订单', '成品库存']) {
      expect(indexOfLeaf(leaves, label)).toBeGreaterThan(biz);
    }
  });

  it('标题与「现场作业」之间没有说明卡（政策说明不是功能，2026-09-15 polish 删掉）', () => {
    const { toJSON } = render(<BossOverviewScreen />);
    const leaves = textLeavesInOrder(toJSON());
    // 「现场作业」之前只允许有页面标题 —— 任何一句说明文案回来这里都会红。
    const field = indexOfLeaf(leaves, '现场作业');
    const before = leaves.slice(0, field).filter((s) => s !== '经营总览');
    expect(before).toEqual([]);
    expect(leaves.some((s) => /查看与决策/.test(s))).toBe(false);
  });

  it('现场作业是瓷砖（primaryContainer 底、≥96dp），经营是列表项（≥48dp）—— 两组视觉权重不同', () => {
    const { getByTestId, getByText } = render(<BossOverviewScreen />);
    const { theme } = require('../../theme');
    const tile = getByTestId('boss-entry-traceability');
    // 瓷砖: 触控目标本身 ≥96dp
    expect(flatten(tile.props.style).minHeight).toBeGreaterThanOrEqual(96);
    // 瓷砖的底是 primaryContainer (在它外层的 Surface 上 —— 沿祖先找最近一个有底色的)
    expect(nearestStyle(tile, 'backgroundColor')).toBe(theme.colors.primaryContainer);
    // 列表项: 最近的触控容器 ≥48dp, 且**不是**瓷砖那个底色 (两组权重不同, 不能长一样)
    const item = getByText('生产进度');
    expect(nearestStyle(item, 'minHeight')).toBeGreaterThanOrEqual(48);
    expect(nearestStyle(item, 'minHeight')).toBeLessThan(96);
    expect(nearestStyle(item, 'backgroundColor')).not.toBe(theme.colors.primaryContainer);
  });
});
