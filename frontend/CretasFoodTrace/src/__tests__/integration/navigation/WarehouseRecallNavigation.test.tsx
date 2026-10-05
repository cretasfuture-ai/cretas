import React from "react";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react-native";
import type { FactoryUser } from "../../../types/auth";

let mockUser: FactoryUser | null = null;
const mockGetBatchById = jest.fn();
const mockGetUsage = jest.fn();
const mockGetImpact = jest.fn();
let mockNestedScreen: string | null = null;
let mockNestedScreenOverride: string | null = null;

jest.mock("react-native-safe-area-context", () => {
  const ReactNative = require("react-native");
  return {
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
    SafeAreaView: ReactNative.View,
    useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
  };
});
jest.mock("@react-navigation/native", () => {
  const actual = jest.requireActual("@react-navigation/native");
  const ReactNative = require("react");
  const { NavigationContext } = require("@react-navigation/core");
  return { ...actual, useNavigation: () => ReactNative.useContext(NavigationContext), useRoute: () => ({ params: { batchId: "BATCH-1" } }) };
});
jest.mock("@react-navigation/bottom-tabs", () => {
  const ReactNative = require("react");
  const { View } = require("react-native");
  const { NavigationContext } = require("@react-navigation/core");
  return {
    createBottomTabNavigator: () => ({
      Screen: () => null,
      Navigator: ({ children, initialRouteName }: { children: React.ReactNode; initialRouteName?: string }) => {
        const screens = ReactNative.Children.toArray(children).filter(ReactNative.isValidElement) as React.ReactElement<{ name: string; component: React.ComponentType }> [];
        const routeNames = screens.map((screen) => screen.props.name);
        const [selected, setSelected] = ReactNative.useState(initialRouteName ?? routeNames[0]);
        const active = screens.find((screen) => screen.props.name === selected) ?? screens[0];
        const parentNavigation = {
          getState: () => ({ routeNames }),
          navigate: (name: string, params?: { screen?: string }) => {
            mockNestedScreen = mockNestedScreenOverride ?? params?.screen ?? null;
            setSelected(name);
          },
        };
        const childNavigation = { getParent: () => parentNavigation, goBack: () => undefined };
        return <NavigationContext.Provider value={childNavigation}><View>{active ? ReactNative.createElement(active.props.component) : null}</View></NavigationContext.Provider>;
      },
    }),
  };
});
jest.mock("@react-navigation/native-stack", () => {
  const ReactNative = require("react");
  const { Text, View } = require("react-native");
  return {
    createNativeStackNavigator: () => ({
      Screen: () => null,
      Navigator: ({ children, initialRouteName }: { children: React.ReactNode; initialRouteName?: string }) => {
        const screens = ReactNative.Children.toArray(children).filter(ReactNative.isValidElement) as React.ReactElement<{ name: string; component: React.ComponentType }> [];
        const selected = mockNestedScreen ?? initialRouteName ?? screens[0]?.props.name;
        const active = screens.find((screen) => screen.props.name === selected);
        return <View>{active ? ReactNative.createElement(active.props.component) : <Text testID="nested-screen-not-registered">Nested target is not registered</Text>}</View>;
      },
    }),
  };
});
jest.mock("react-i18next", () => {
  const t = (key: string) => key;
  return { useTranslation: () => ({ t }) };
});
jest.mock("../../../store/authStore", () => ({
  useAuthStore: Object.assign(
    (selector?: (state: { user: FactoryUser | null }) => unknown) => selector ? selector({ user: mockUser }) : { user: mockUser },
    { getState: () => ({ user: mockUser }) },
  ),
}));
jest.mock("../../../store/factoryFeatureStore", () => ({
  useFactoryFeatureStore: () => ({ isScreenEnabled: (name: string) => name !== "InboundManagement" && name !== "OutboundManagement" && name !== "SmartBI" }),
}));
jest.mock("../../../services/api/materialBatchApiClient", () => ({
  materialBatchApiClient: { getBatchById: mockGetBatchById, getBatchUsageHistory: mockGetUsage },
}));
jest.mock("../../../services/api/traceabilityApiClient", () => ({
  traceabilityApiClient: { getImpact: mockGetImpact },
}));
jest.mock("../../../navigation/warehouse/WHInboundStackNavigator", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../navigation/warehouse/WHOutboundStackNavigator", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../navigation/warehouse/WHInventoryStackNavigator", () => {
  const ReactNative = require("react");
  const { WHBatchTraceScreen } = require("../../../screens/warehouse/shared/WHBatchTraceScreen");
  return { __esModule: true, default: () => ReactNative.createElement(WHBatchTraceScreen) };
});
jest.mock("../../../navigation/warehouse/WHHomeStackNavigator", () => {
  const ReactNative = require("react");
  const { WHBatchTraceScreen } = require("../../../screens/warehouse/shared/WHBatchTraceScreen");
  return { __esModule: true, default: () => ReactNative.createElement(WHBatchTraceScreen) };
});
jest.mock("../../../screens/warehouse/profile/WHProfileScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/profile/WHProfileEditScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/profile/WHSettingsScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/profile/WHOperationLogScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/WHIOStatisticsScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/WHInventoryCheckScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/WHExpireHandleScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/WastageReportScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/shared/WHAlertListScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/shared/WHAlertHandleScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/shared/WHRecallManageScreen", () => {
  const React = require("react");
  const ReactNative = require("react-native");
  return { __esModule: true, default: () => React.createElement(ReactNative.Text, { testID: "manager-recall-target" }, "WHRecallManage") };
});
jest.mock("../../../screens/warehouse/shared/WHConversionAnalysisScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/WHStocktakeListScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/WHStocktakeDetailScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/warehouse/inventory/StocktakeEntryScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../screens/profile/FeedbackScreen", () => ({ __esModule: true, default: () => null }));
jest.mock("../../../navigation/OperatingReportStackNavigator", () => ({ __esModule: true, default: () => null }));
// 2026-10-05 起仓库员根导航器注册 AIChat (一线 AI 入口); 真屏会拉 expo-speech 原生模块, 本测试不涉及它
jest.mock("../../../screens/factory-admin/ai-analysis/AIChatScreen", () => ({ __esModule: true, default: () => null }));

import WarehouseWorkerNavigator from "../../../navigation/WarehouseWorkerNavigator";
import WarehouseManagerTabNavigator from "../../../navigation/WarehouseManagerTabNavigator";

function actor(role: FactoryUser["factoryUser"]["role"]): FactoryUser {
  return {
    id: role === "warehouse_manager" ? 101 : 102,
    username: role,
    email: `${role}@test.invalid`,
    fullName: role,
    userType: "factory",
    factoryId: "F006",
    createdAt: "2026-10-05",
    updatedAt: "2026-10-05",
    isActive: true,
    factoryUser: { factoryId: "F006", role, permissions: [], modulePermissions: { warehouse: "r" } },
  };
}

function hasDisabledAncestor(node: { parent: { props?: Record<string, unknown>; parent?: unknown } | null }): boolean {
  let current = node.parent;
  for (let depth = 0; current && depth < 6; depth += 1) {
    const props = current.props ?? {};
    if (props.disabled === true || (typeof props.accessibilityState === "object" && props.accessibilityState !== null && (props.accessibilityState as { disabled?: unknown }).disabled === true)) return true;
    current = current.parent as typeof current;
  }
  return false;
}

beforeEach(() => {
  mockNestedScreen = null;
  mockNestedScreenOverride = null;
  mockUser = actor("warehouse_worker");
  mockGetBatchById.mockReset().mockResolvedValue({ success: true, data: {
    id: "BATCH-1", batchNumber: "MB-F006-1", factoryId: "F006", materialTypeId: "MAT-1",
    inboundQuantity: 100, remainingQuantity: 80, quantityUnit: "box", inboundDate: "2026-10-01", createdAt: "2026-10-01",
    materialName: "鸡胸肉",
  } });
  mockGetUsage.mockReset().mockResolvedValue({ success: true, data: [] });
  mockGetImpact.mockReset().mockResolvedValue({ rootType: "MATERIAL_BATCH", rootId: "BATCH-1", productionBatches: [], finishedBatches: [], shipments: [] });
});

afterEach(() => {
  cleanup();
  mockUser = null;
});

describe("warehouse recall reachability", () => {
  it("keeps recall unavailable and disabled for a real worker parent route", async () => {
    const view = render(<WarehouseWorkerNavigator />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.recallUnavailable")).toBeTruthy());
    const recallText = view.getByText("shared.batchTrace.recallUnavailable");
    expect(hasDisabledAncestor(recallText)).toBe(true);
    expect(view.getByText("shared.batchTrace.recallUnavailableHint")).toBeTruthy();
    expect(view.queryByText("shared.batchTrace.recall")).toBeNull();
  });

  it("routes a real manager parent to the profile recall screen", async () => {
    mockUser = actor("warehouse_manager");
    const view = render(<WarehouseManagerTabNavigator />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.recall")).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByText("shared.batchTrace.recall")); });
    await waitFor(() => expect(view.getByTestId("manager-recall-target")).toHaveTextContent("WHRecallManage"));
  });

  it("does not claim a recall target when the nested route is not registered", async () => {
    mockUser = actor("warehouse_manager");
    mockNestedScreenOverride = "WHMissingRecallTarget";
    const view = render(<WarehouseManagerTabNavigator />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.recall")).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByText("shared.batchTrace.recall")); });
    await waitFor(() => expect(view.getByTestId("nested-screen-not-registered")).toBeTruthy());
    expect(view.queryByTestId("manager-recall-target")).toBeNull();
  });
});
