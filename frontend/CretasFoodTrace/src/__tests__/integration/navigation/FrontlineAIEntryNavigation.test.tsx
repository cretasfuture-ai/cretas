/**
 * 一线 AI 助手入口（Line B, spec docs/specs/rn-frontline-ai-entry-2026-10-05.md）—— 导航集成测试。
 *
 * 跑在产品真实入口上（形态 B）：真的三个角色导航器 + 真首页屏 + 真 store，
 * 只桩掉网络与 AIChatScreen 本体（它有自己的测试；这里要证明的是「点了入口，到得了它」）。
 *
 * ⚠️ 真 @react-navigation 在这个 jest 环境里加载不起来（UIManager.getViewManagerConfig 缺失，
 *    2026-10-05 实测），所以与仓里其它导航测试一样桩掉 createNativeStackNavigator / createBottomTabNavigator。
 *    这里的桩**保留了要守的那个语义**：navigate(name) 先在自己的路由里找，找不到交给父导航器 ——
 *    与 React Navigation 的冒泡规则一致。所以「首页里点入口 → 冒泡到根 Stack → 渲染 AIChat」是走通了真实接线的，
 *    不是直接断言「调用了 navigate」。
 *
 * 守的行为：
 *   ① 默认（两层开关都开）三个一线角色首页都有入口，点它进 AIChat                     —— owner 裁定「默认开」
 *   ② 熔断：全局常量关 ⇒ 入口消失 **且** AIChat 路由不注册                             —— 让 ① 能红的对照
 *   ③ 熔断：本工厂 feature-config 禁用 FrontlineAIChat / 关掉 smartbi ⇒ 同上
 *   ④ yield_operator 与 operator 共用 OperatorNavigator，同样有入口（owner 2026-10-05 追加）
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

let mockGlobalEnabled = true;
jest.mock('../../../config/frontlineAIEntry', () => {
  const actual = jest.requireActual('../../../config/frontlineAIEntry');
  return {
    ...actual,
    get FRONTLINE_AI_ENTRY_GLOBAL_ENABLED() {
      return mockGlobalEnabled;
    },
  };
});

jest.mock('../../../screens/factory-admin/ai-analysis/AIChatScreen', () => ({
  __esModule: true,
  default: () => {
    const { Text } = require('react-native');
    return <Text testID="ai-chat-stub">AI_CHAT_STUB</Text>;
  },
}));

/** 根导航器（没有父导航器的那个）当前注册的路由名。 */
let mockRootRouteNames: string[] = [];

jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return {
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
    SafeAreaView: View,
    useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
  };
});
jest.mock('@react-navigation/native', () => {
  const actual = jest.requireActual('@react-navigation/native');
  const ReactLib = require('react');
  const { NavigationContext } = require('@react-navigation/core');
  return {
    ...actual,
    useNavigation: () => ReactLib.useContext(NavigationContext),
    useRoute: () => ({ params: undefined }),
    useFocusEffect: (effect: () => void | (() => void)) => ReactLib.useEffect(() => effect(), [effect]),
  };
});

/** 最小导航器：渲染当前路由；navigate 先找自己的路由，找不到冒泡给父导航器（同 React Navigation）。 */
function mockMakeNavigator() {
  const ReactLib = require('react');
  const { View } = require('react-native');
  const { NavigationContext } = require('@react-navigation/core');
  return () => ({
    Screen: () => null,
    Group: () => null,
    Navigator: ({ children, initialRouteName }: { children: React.ReactNode; initialRouteName?: string }) => {
      const parent = ReactLib.useContext(NavigationContext);
      const screens = ReactLib.Children.toArray(children)
        .filter(ReactLib.isValidElement) as Array<React.ReactElement<{ name: string; component: React.ComponentType }>>;
      const routeNames = screens.map((s) => s.props.name);
      if (!parent) mockRootRouteNames = routeNames;
      const [current, setCurrent] = ReactLib.useState(initialRouteName ?? routeNames[0]);
      const navigation = {
        navigate: (name: string, params?: unknown) => {
          if (routeNames.includes(name)) setCurrent(name);
          else if (parent) parent.navigate(name, params);
          else throw new Error(`没有导航器注册了路由 ${name}`);
        },
        getParent: () => parent,
        getState: () => ({ routeNames }),
        goBack: () => undefined,
        dispatch: () => undefined,
        addListener: () => () => undefined,
        setOptions: () => undefined,
        isFocused: () => true,
      };
      const active = screens.find((s) => s.props.name === current);
      return (
        <NavigationContext.Provider value={navigation}>
          <View>{active ? ReactLib.createElement(active.props.component) : null}</View>
        </NavigationContext.Provider>
      );
    },
  });
}
jest.mock('@react-navigation/native-stack', () => ({ createNativeStackNavigator: mockMakeNavigator() }));
jest.mock('@react-navigation/bottom-tabs', () => ({ createBottomTabNavigator: mockMakeNavigator() }));

// 首页以外、本测试不会渲染到的屏/子导航器：桩成空组件，免得为它们的原生依赖搭环境。
// ⚠️ 被测的首页（工序 / 入库 / 质检首页）与三个角色导航器本身都是真的。
jest.mock('../../../navigation/ProfileStackNavigator', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/common/MobileAccountScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/processing/YieldStepReportScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/processing/ShortageAlertDetailScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/quality-inspector/QILabelQcCreateScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/quality-inspector/QILabelQcQueueScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/quality-inspector/QILabelQcSubmittedScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/quality-inspector/QIProfileScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/quality-inspector/QISettingsScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../navigation/warehouse/WHOutboundStackNavigator', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../navigation/warehouse/WHInventoryStackNavigator', () => ({ __esModule: true, default: () => null }));
// 入库 Stack 本身是真的（入口在它的首屏 WHInboundList 上），只桩掉它的非首屏。
// ⚠️ jest.mock 必须是顶层字面量调用才会被提升到 import 之前 —— 不能写成循环。
jest.mock('../../../screens/warehouse/inbound/WHInboundDetailScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/inbound/WHInspectScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/inbound/WHPutawayScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/inbound/WHReceiptCreateScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/inbound/WHPurchaseReceiveListScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/inbound/WHUnorderedInboundReceiveScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/shared/WHScanOperationScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/restaurant/supplier-delivery/SupplierDeliveryListScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/restaurant/supplier-delivery/SupplierDeliveryCreateScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/restaurant/supplier-delivery/SupplierDeliveryDetailScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/restaurant/supplier-delivery/PriceAnomalyApprovalScreen', () => ({ __esModule: true, default: () => null }));
jest.mock('../../../screens/warehouse/inventory/WHTransitLedgerScreen', () => ({ __esModule: true, default: () => null }));

// 网络层：首页都落到「已加载、空列表」
jest.mock('../../../services/api/yieldReportApi', () => ({
  yieldReportApi: {
    listAssignedWorkProcessTasks: jest.fn(async () => ({ success: true, data: { content: [] } })),
    listWorkProcessTasks: jest.fn(async () => ({ success: true, data: [] })),
    getYield: jest.fn(async () => null),
  },
}));
jest.mock('../../../services/api/shortageAlertApiClient', () => ({
  shortageAlertApiClient: { fetchBomShortage: jest.fn(async () => ({ result: null })) },
}));
jest.mock('../../../services/api/purchaseApiClient', () => ({
  purchaseApiClient: {
    getOrdersByStatus: jest.fn(async () => ({
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, page: 1, size: 20 },
    })),
  },
}));
jest.mock('../../../services/api/warehouseReceivingApiClient', () => ({
  warehouseReceivingApiClient: { listCustomerMaterialArrivalTasks: jest.fn(async () => ({ data: [] })) },
}));

import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import OperatorNavigator from '../../../navigation/OperatorNavigator';
import QualityInspectorNavigator from '../../../navigation/QualityInspectorNavigator';
import WarehouseWorkerNavigator from '../../../navigation/WarehouseWorkerNavigator';
import { FRONTLINE_AI_SCREEN_ID } from '../../../config/frontlineAIEntry';

const ENTRY = 'frontline-ai-entry';

/** 每个首页「已经渲染出来」的固定标记（不依赖入口本身）—— 先等它出现，再断言「入口不在」（硬约束 9）。 */
const HOME_MARKER: Record<string, string> = {
  operator: 'operator-assigned-process',
  yield_operator: 'operator-assigned-process',
  warehouse_worker: 'wh-inbound-home',
  quality_inspector: 'qi-home-screen',
};

function setUser(role: string) {
  useAuthStore.setState({
    user: {
      id: 501,
      username: `u_${role}`,
      userType: 'factory',
      factoryId: 'F006',
      factoryUser: { factoryId: 'F006', role, factoryType: 'FACTORY', permissions: [] },
    } as never,
    isAuthenticated: true,
  } as never);
}

type SmartbiOverride = { enabled: boolean; disabledScreens?: string[] };
function setFeatures(smartbi?: SmartbiOverride) {
  const modules: Record<string, unknown> = {
    production: { enabled: true, moduleName: '生产', config: {} },
    warehouse: { enabled: true, moduleName: '仓储', config: {} },
  };
  if (smartbi) {
    modules.smartbi = {
      enabled: smartbi.enabled,
      moduleName: '智能BI',
      config: { disabledScreens: smartbi.disabledScreens ?? [] },
    };
  }
  useFactoryFeatureStore.setState({ modules: modules as never, loaded: true, loading: false });
}

const CASES: Array<{ role: string; Navigator: React.ComponentType }> = [
  { role: 'operator', Navigator: OperatorNavigator },
  { role: 'warehouse_worker', Navigator: WarehouseWorkerNavigator },
  { role: 'quality_inspector', Navigator: QualityInspectorNavigator },
  { role: 'yield_operator', Navigator: OperatorNavigator },
];

async function renderHome(role: string, Navigator: React.ComponentType) {
  setUser(role);
  const view = render(<Navigator />);
  await waitFor(() => expect(view.getByTestId(HOME_MARKER[role]!)).toBeTruthy());
  return view;
}

beforeEach(() => {
  mockGlobalEnabled = true;
  mockRootRouteNames = [];
  setFeatures({ enabled: true });
});

describe('一线 AI 助手入口 · 默认开', () => {
  it('参数化的那一维真的不同：四个角色、三个导航器、三个首页（yield_operator 与 operator 共用）', () => {
    expect(new Set(CASES.map((c) => c.role)).size).toBe(4);
    expect(new Set(CASES.map((c) => c.Navigator)).size).toBe(3);
    expect(new Set(CASES.map((c) => HOME_MARKER[c.role])).size).toBe(3);
  });

  it.each(CASES)('$role: 首页有入口，点它冒泡到根 Stack 打开 AI 对话', async ({ role, Navigator }) => {
    const view = await renderHome(role, Navigator);

    expect(mockRootRouteNames).toContain('AIChat');
    const entry = view.getByTestId(ENTRY);
    expect(view.queryByTestId('ai-chat-stub')).toBeNull();

    await act(async () => { fireEvent.press(entry); });
    await waitFor(() => expect(view.getByTestId('ai-chat-stub')).toBeTruthy());
    // 首页被 AI 对话盖住（根 Stack 切走了），不是在 tab 里嵌了一个
    expect(view.queryByTestId(HOME_MARKER[role]!)).toBeNull();
  });
});

describe('一线 AI 助手入口 · 熔断', () => {
  it.each(CASES)('$role: 全局常量关 ⇒ 没有入口、没有 AIChat 路由', async ({ role, Navigator }) => {
    mockGlobalEnabled = false;
    const view = await renderHome(role, Navigator);
    expect(view.queryByTestId(ENTRY)).toBeNull();
    expect(mockRootRouteNames.length).toBeGreaterThan(0);
    expect(mockRootRouteNames).not.toContain('AIChat');
  });

  it.each(CASES)('$role: 本工厂禁用 FrontlineAIChat ⇒ 没有入口、没有路由', async ({ role, Navigator }) => {
    setFeatures({ enabled: true, disabledScreens: [FRONTLINE_AI_SCREEN_ID] });
    const view = await renderHome(role, Navigator);
    expect(view.queryByTestId(ENTRY)).toBeNull();
    expect(mockRootRouteNames).not.toContain('AIChat');
  });

  it.each(CASES)('$role: 本工厂关掉 smartbi 模块 ⇒ 没有入口、没有路由', async ({ role, Navigator }) => {
    setFeatures({ enabled: false });
    const view = await renderHome(role, Navigator);
    expect(view.queryByTestId(ENTRY)).toBeNull();
    expect(mockRootRouteNames).not.toContain('AIChat');
  });

  it('功能配置里没有 smartbi 这一行（新工厂）⇒ 仍是默认开', async () => {
    setFeatures(undefined);
    const view = await renderHome('operator', OperatorNavigator);
    expect(view.getByTestId(ENTRY)).toBeTruthy();
  });

  it('开关在运行中被关掉（功能配置晚到）⇒ 入口当场消失', async () => {
    const view = await renderHome('quality_inspector', QualityInspectorNavigator);
    expect(view.getByTestId(ENTRY)).toBeTruthy();
    await act(async () => { setFeatures({ enabled: true, disabledScreens: [FRONTLINE_AI_SCREEN_ID] }); });
    await waitFor(() => expect(view.queryByTestId(ENTRY)).toBeNull());
    expect(mockRootRouteNames).not.toContain('AIChat');
  });
});

describe('一线 AI 助手入口 · 角色边界', () => {
  // 守「名单在起作用」的阴性对照: 共用首页的导航器里, 不在名单上的角色看不到入口。
  // OperatorNavigator 只服务 operator / yield_operator, 两个都在名单上了 ⇒ 用仓库员首页上的
  // warehouse_manager（主管与仓库员共用收货/发货/库存三屏）当对照。
  it('warehouse_manager 与仓库员共用首页，但不在一线名单里 ⇒ 没有入口', async () => {
    setUser('warehouse_manager');
    const view = render(<WarehouseWorkerNavigator />);
    await waitFor(() => expect(view.getByTestId(HOME_MARKER.warehouse_worker!)).toBeTruthy());
    expect(view.queryByTestId(ENTRY)).toBeNull();
    expect(mockRootRouteNames).not.toContain('AIChat');
  });
});
