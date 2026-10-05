/**
 * QuickActionCardGrid：卡组选择 + 每张卡的落点。
 *
 * 2026-09-16 改成真渲染（@testing-library/react-native）：组件里加了 useTheme / useTranslation，
 * 原来「把组件当普通函数调」的写法在 hook 上直接炸（Invalid hook call）。
 *
 * t() 从**真的** zh-CN/home.json 取值：断言用中文标签，同时证明键在 JSON 里存在
 * （取不到会退回 key 字符串，`getByText('这批来自哪几头牛')` 就找不到 → 红）。
 */
import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';
import QuickActionCardGrid, {
  CATTLE_OFFSPRING_ROUTE,
  isCattleFactory,
  resolveCardFactory,
} from '../../../components/ai/QuickActionCardGrid';

function lookup(obj: unknown, dotted: string): string | undefined {
  const value = dotted.split('.').reduce<unknown>(
    (acc, part) => (acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[part] : undefined),
    obj,
  );
  return typeof value === 'string' ? value : undefined;
}

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => lookup(zhHome, key) ?? key,
    i18n: { language: 'zh-CN' },
  }),
}));

function renderGrid(userRole: string, opts: { isRestaurantMode?: boolean; factoryId?: string } = {}) {
  const onSendIntent = jest.fn();
  const onNavigate = jest.fn();
  const screen = render(
    <QuickActionCardGrid
      userRole={userRole}
      isRestaurantMode={opts.isRestaurantMode}
      factoryId={opts.factoryId}
      onSendIntent={onSendIntent}
      onNavigate={onNavigate}
    />,
  );
  return { screen, onSendIntent, onNavigate };
}

function labels(screen: ReturnType<typeof render>): string[] {
  const out: string[] = [];
  for (let i = 0; i < 12; i += 1) {
    const node = screen.queryByTestId(`quick-action-label-${i}`);
    if (!node) break;
    out.push(String(node.props.children));
  }
  return out;
}

describe('卡组选择（餐饮 > 牛业 > 角色）', () => {
  it('FA / WS / WM 各自的卡数与标签不变', () => {
    expect(labels(renderGrid('factory_super_admin').screen))
      .toEqual(['生产报工', '原料入库', '查库存', 'BOM达成率', '成本分析', '毛利率']);
    expect(labels(renderGrid('workshop_supervisor').screen))
      .toEqual(['生产报工', '我的任务', '人员状态', '设备状态']);
    expect(labels(renderGrid('warehouse_manager').screen))
      .toEqual(['原料入库', '出库发货', '查库存', '低库存']);
  });

  it('未知角色不渲染网格', () => {
    const { screen } = renderGrid('unknown_role');
    expect(screen.queryByTestId('quick-action-card-grid')).toBeNull();
    expect(resolveCardFactory({ userRole: 'unknown_role' })).toBeUndefined();
  });

  it('餐饮模式覆盖角色：六张经营卡', () => {
    expect(labels(renderGrid('factory_super_admin', { isRestaurantMode: true }).screen))
      .toEqual(['今日经营', '门店对比', '菜品表现', '损耗分析', '库存风险', '经营建议']);
  });

  it('CATTLE_ 租户渲染四张牛业卡；F006 同角色渲染原 FA 六张（对照）', () => {
    expect(labels(renderGrid('factory_super_admin', { factoryId: 'CATTLE_3701_001' }).screen))
      .toEqual(['这批来自哪几头牛', '这头牛的后代', '谁 DNA 不匹配', '拍耳标认牛']);
    // 对照：同一个角色、非牛业租户 ⇒ 原卡组一张不少
    expect(labels(renderGrid('factory_super_admin', { factoryId: 'F006' }).screen))
      .toEqual(['生产报工', '原料入库', '查库存', 'BOM达成率', '成本分析', '毛利率']);
    // 没传 factoryId 也走原卡组（旧调用方不受影响）
    expect(labels(renderGrid('factory_super_admin').screen)).toHaveLength(6);
  });

  it('牛业判定只认前缀：CATTLE_ 前缀为真，含 CATTLE 但不在开头 / 空 / undefined 为假', () => {
    expect(isCattleFactory('CATTLE_3701_001')).toBe(true);
    expect(isCattleFactory('F_CATTLE_1')).toBe(false);
    expect(isCattleFactory('')).toBe(false);
    expect(isCattleFactory(undefined)).toBe(false);
  });

  it('餐饮模式优先于牛业前缀（同时为真时给餐饮卡）', () => {
    expect(labels(renderGrid('factory_super_admin', { isRestaurantMode: true, factoryId: 'CATTLE_X' }).screen)[0])
      .toBe('今日经营');
  });
});

describe('牛业卡的落点', () => {
  it('「这批来自哪几头牛」→ 溯源查询入口，⛔ 不发写死批次号的问句', () => {
    const { screen, onNavigate, onSendIntent } = renderGrid('factory_super_admin', { factoryId: 'CATTLE_3701_001' });
    fireEvent.press(screen.getByTestId('quick-action-card-0'));
    expect(onNavigate).toHaveBeenCalledWith('Traceability');
    expect(onSendIntent).not.toHaveBeenCalled();
  });

  it('「这头牛的后代」→ SourceIndividualList（只读源头个体列表，#822 起在 Boss 树里注册）', () => {
    const { screen, onNavigate } = renderGrid('factory_super_admin', { factoryId: 'CATTLE_3701_001' });
    fireEvent.press(screen.getByTestId('quick-action-card-1'));
    expect(onNavigate).toHaveBeenCalledWith('SourceIndividualList');
    expect(CATTLE_OFFSPRING_ROUTE).toBe('SourceIndividualList');
  });

  it('「谁 DNA 不匹配」→ 问答意图（正文格式由后端 Tool 决定）', () => {
    const { screen, onSendIntent, onNavigate } = renderGrid('factory_super_admin', { factoryId: 'CATTLE_3701_001' });
    fireEvent.press(screen.getByTestId('quick-action-card-2'));
    expect(onSendIntent).toHaveBeenCalledWith('哪一头牛的 DNA 比对不匹配');
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('「拍耳标认牛」→ VisionVerify', () => {
    const { screen, onNavigate } = renderGrid('factory_super_admin', { factoryId: 'CATTLE_3701_001' });
    fireEvent.press(screen.getByTestId('quick-action-card-3'));
    expect(onNavigate).toHaveBeenCalledWith('VisionVerify');
  });
});

describe('存量卡的落点不变', () => {
  it.each(['factory_super_admin', 'warehouse_manager'])('%s 查库存明确查询原材料，避免无选项的泛化澄清', (role) => {
    const { screen, onSendIntent, onNavigate } = renderGrid(role, { factoryId: 'F006' });
    expect(screen.getByText('查看原材料库存')).toBeTruthy();
    fireEvent.press(screen.getByTestId('quick-action-card-2'));
    expect(onSendIntent).toHaveBeenCalledTimes(1);
    expect(onSendIntent).toHaveBeenCalledWith('查询原材料库存');
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('FA card-0 → onSendIntent("我要报工")；card-3 → onNavigate("BomAchievement")', () => {
    const { screen, onSendIntent, onNavigate } = renderGrid('factory_super_admin');
    fireEvent.press(screen.getByTestId('quick-action-card-0'));
    expect(onSendIntent).toHaveBeenCalledWith('我要报工');
    fireEvent.press(screen.getByTestId('quick-action-card-3'));
    expect(onNavigate).toHaveBeenCalledWith('BomAchievement');
  });

  it('WS card-1 → ProcessTaskList；WM card-0 → WHPurchaseReceiveList', () => {
    const ws = renderGrid('workshop_supervisor');
    fireEvent.press(ws.screen.getByTestId('quick-action-card-1'));
    expect(ws.onNavigate).toHaveBeenCalledWith('ProcessTaskList');
    const wm = renderGrid('warehouse_manager');
    fireEvent.press(wm.screen.getByTestId('quick-action-card-0'));
    expect(wm.onNavigate).toHaveBeenCalledWith('WHPurchaseReceiveList');
  });

  it('餐饮卡只发问句，不导航到工厂屏', () => {
    const { screen, onSendIntent, onNavigate } = renderGrid('factory_super_admin', { isRestaurantMode: true });
    fireEvent.press(screen.getByTestId('quick-action-card-1'));
    expect(onSendIntent).toHaveBeenCalledWith(expect.stringContaining('最近7天各门店'));
    expect(onNavigate).not.toHaveBeenCalled();
  });
});

// 一线卡组 (2026-10-05)。每句话都在 prod 识别接口上按角色量过 (16/16 直接落到目标意图、不反问),
// 这里钉的就是**量过的那句话** —— 改措辞会红, 红了先去重新量, 再改这里。
const FRONTLINE_CARDS: Record<string, Array<[string, string]>> = {
  operator: [['报工', '我要报工'], ['我的任务', '今天我的工序任务'], ['上班打卡', '上班打卡'], ['下班打卡', '下班打卡']],
  yield_operator: [['报工', '我要报工'], ['我的任务', '今天我的工序任务'], ['上班打卡', '上班打卡'], ['下班打卡', '下班打卡']],
  warehouse_worker: [['查库存', '查询原材料库存'], ['库存不足', '查一下库存不足的原料'], ['快过期', '快过期的原料有哪些'], ['出货记录', '查询出货记录']],
  quality_inspector: [['待检批次', '今天待质检的批次'], ['登记不良', '登记质检不良'], ['质检统计', '质检统计'], ['上班打卡', '上班打卡']],
};

describe('一线角色卡组', () => {
  it('参数化的那一维真的不同: 四个角色, 三组不同的卡 (yield_operator 与 operator 同组)', () => {
    expect(Object.keys(FRONTLINE_CARDS)).toHaveLength(4);
    expect(new Set(Object.values(FRONTLINE_CARDS).map((c) => JSON.stringify(c))).size).toBe(3);
  });

  it.each(Object.keys(FRONTLINE_CARDS))('%s: 标签, 以及每张卡发出的正是量过的那句话; 不跳屏', (role) => {
    const expected = FRONTLINE_CARDS[role]!;
    const { screen, onSendIntent, onNavigate } = renderGrid(role);
    expect(labels(screen)).toEqual(expected.map(([label]) => label));
    expected.forEach(([, phrase], i) => {
      fireEvent.press(screen.getByTestId(`quick-action-card-${i}`));
      expect(onSendIntent).toHaveBeenLastCalledWith(phrase);
    });
    expect(onSendIntent).toHaveBeenCalledTimes(expected.length);
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('换掉的说法不许回来 (它们在 prod 上要反问, 或落到会建单的意图)', () => {
    const sent: string[] = [];
    for (const role of Object.keys(FRONTLINE_CARDS)) {
      const { screen, onSendIntent } = renderGrid(role);
      for (let i = 0; screen.queryByTestId(`quick-action-card-${i}`); i += 1) {
        fireEvent.press(screen.getByTestId(`quick-action-card-${i}`));
      }
      sent.push(...onSendIntent.mock.calls.map((c: unknown[]) => String(c[0])));
    }
    expect(sent).toHaveLength(16);
    for (const bad of ['低库存预警', '今天的出货记录', '今天要发哪些货']) {
      expect(sent).not.toContain(bad);
    }
  });
});

describe('视觉与无障碍', () => {
  it('每张卡都有 accessibilityLabel = 标签，role=button', () => {
    const { screen } = renderGrid('factory_super_admin', { factoryId: 'CATTLE_3701_001' });
    const expected = ['这批来自哪几头牛', '这头牛的后代', '谁 DNA 不匹配', '拍耳标认牛'];
    expected.forEach((label, i) => {
      const card = screen.getByTestId(`quick-action-card-${i}`);
      expect(card.props.accessibilityLabel).toBe(label);
      expect(card.props.accessibilityRole).toBe('button');
    });
  });

  it('图标底色全部是同一个 token（primaryContainer），不再每卡一色', () => {
    const { theme } = require('../../../theme');
    const { screen } = renderGrid('factory_super_admin');
    const bgs = new Set<string | undefined>();
    for (let i = 0; i < 6; i += 1) {
      const flat = Object.assign(
        {},
        ...([] as unknown[]).concat(screen.getByTestId(`quick-action-icon-${i}`).props.style ?? []).flat(Infinity),
      ) as { backgroundColor?: string };
      bgs.add(flat.backgroundColor);
    }
    expect(bgs).toEqual(new Set([theme.colors.primaryContainer]));
    // 阳性对照：token 本身是个真色值，不是 undefined（否则上面那个 Set 是 {undefined} 也能过）
    expect(theme.colors.primaryContainer).toMatch(/^#[0-9A-Fa-f]{6}$/);
  });
});
