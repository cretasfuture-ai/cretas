import React from "react";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react-native";
import { TouchableOpacity } from "react-native";
import { Button } from "react-native-paper";
import type { FactoryRole, FactoryUser } from "../../../types/auth";
import type { ShipmentRecord } from "../../../services/api/shipmentApiClient";

let routeParams: { shipmentId?: string; orderId?: string } = { shipmentId: "SH-1" };
const goBack = jest.fn();

jest.mock("@react-navigation/native", () => ({
  ...jest.requireActual("@react-navigation/native"),
  useNavigation: () => ({ goBack, navigate: jest.fn(), dispatch: jest.fn() }),
  useRoute: () => ({ params: routeParams }),
}));

jest.mock("../../../services/api/shipmentApiClient", () => ({
  shipmentApiClient: { getShipmentById: jest.fn() },
}));

import { WHOutboundDetailScreen } from "../../../screens/warehouse/outbound/WHOutboundDetailScreen";
import { WHOrderDetailScreen } from "../../../screens/warehouse/outbound/WHOrderDetailScreen";
import { shipmentApiClient } from "../../../services/api/shipmentApiClient";
import { useAuthStore } from "../../../store/authStore";

const getShipmentById = jest.mocked(shipmentApiClient.getShipmentById);

function actor(role: FactoryRole, id = 7, factoryId = "F006"): FactoryUser {
  return {
    id,
    username: `${role}-${id}`,
    email: `${role}-${id}@example.test`,
    fullName: `${role}-${id}`,
    createdAt: "2026-10-04",
    updatedAt: "2026-10-04",
    isActive: true,
    userType: "factory",
    factoryId,
    factoryUser: {
      factoryId,
      role,
      permissions: [],
      modulePermissions: {},
    },
  };
}

function shipment(overrides: Partial<ShipmentRecord> = {}): ShipmentRecord {
  return {
    id: "SH-1",
    factoryId: "F006",
    shipmentNumber: "SH-F006-1",
    customerId: "customer-1",
    orderNumber: "SO-1",
    productName: "测试猪蹄",
    quantity: 5,
    unit: "box",
    unitPrice: 12,
    totalAmount: 60,
    shipmentDate: "2026-10-04T09:00:00",
    deliveryAddress: "测试地址",
    status: "pending",
    notes: "备注",
    createdAt: "2026-10-04T08:00:00",
    updatedAt: "2026-10-04T08:30:00",
    ...overrides,
  };
}

function renderScreen(screen: "outbound" | "order") {
  return render(screen === "outbound" ? <WHOutboundDetailScreen /> : <WHOrderDetailScreen />);
}

beforeEach(() => {
  getShipmentById.mockReset();
  goBack.mockReset();
  routeParams = { shipmentId: "SH-1" };
  act(() => { useAuthStore.setState({ user: actor("warehouse_worker"), isAuthenticated: true }); });
});

afterEach(() => {
  cleanup();
  act(() => { useAuthStore.setState({ user: null, isAuthenticated: false }); });
});

describe("warehouse shipment detail price visibility", () => {
  it.each(["outbound", "order"] as const)("denies malicious payload prices on %s detail", async (screen) => {
    routeParams = screen === "outbound" ? { shipmentId: "SH-1" } : { orderId: "SH-1" };
    getShipmentById.mockResolvedValue(shipment({ unitPrice: 99999, totalAmount: 88888 }));
    const view = renderScreen(screen);

    await waitFor(() => expect(view.getAllByText("测试猪蹄").length).toBeGreaterThan(0));
    expect(getShipmentById).toHaveBeenCalledWith("SH-1", "F006");
    expect(view.queryByText("¥999,999")).toBeNull();
    expect(view.queryByText("¥99,999")).toBeNull();
    expect(view.queryByText("¥88,888")).toBeNull();
    expect(view.getAllByText("无权限查看价格").length).toBeGreaterThan(0);
    expect(view.queryByText("金额未提供")).toBeNull();
    expect(view.queryByText("5 box × ¥99999")).toBeNull();
    expect(view.queryByText("5 box × ¥99,999")).toBeNull();
    expect(JSON.stringify(view.toJSON())).not.toContain("¥");
    expect(view.getAllByText("5 box").length).toBeGreaterThan(0);
  });

  it.each(["outbound", "order"] as const)("preserves authorized nonzero, zero, and null price truth on %s detail", async (screen) => {
    act(() => { useAuthStore.setState({ user: actor("factory_super_admin"), isAuthenticated: true }); });
    routeParams = screen === "outbound" ? { shipmentId: "SH-1" } : { orderId: "SH-1" };
    getShipmentById.mockResolvedValue(shipment({ unitPrice: 12, totalAmount: 60 }));
    const view = renderScreen(screen);
    await waitFor(() => expect(view.getAllByText("¥60").length).toBeGreaterThan(0));
    if (screen === "order") {
      expect(view.getByText("5 box × ¥12")).toBeTruthy();
    }

    getShipmentById.mockResolvedValue(shipment({ id: "SH-2", unitPrice: 0, totalAmount: 0 }));
    routeParams = screen === "outbound" ? { shipmentId: "SH-2" } : { orderId: "SH-2" };
    await act(async () => { view.rerender(screen === "outbound" ? <WHOutboundDetailScreen /> : <WHOrderDetailScreen />); });
    await waitFor(() => expect(view.getAllByText("¥0").length).toBeGreaterThan(0));
    if (screen === "order") {
      expect(view.getByText("5 box × ¥0")).toBeTruthy();
    }

    getShipmentById.mockResolvedValue(shipment({ id: "SH-3", unitPrice: null, totalAmount: null, unit: undefined }));
    routeParams = screen === "outbound" ? { shipmentId: "SH-3" } : { orderId: "SH-3" };
    await act(async () => { view.rerender(screen === "outbound" ? <WHOutboundDetailScreen /> : <WHOrderDetailScreen />); });
    await waitFor(() => expect(view.getAllByText("金额未提供").length).toBeGreaterThan(0));
    expect(view.queryByText("¥0")).toBeNull();
    if (screen === "order") {
      expect(view.queryByText(/× ¥/)).toBeNull();
    }
    expect(view.queryByText("5 kg")).toBeNull();
  });

  it("removes an already rendered price when the live actor loses price access", async () => {
    act(() => { useAuthStore.setState({ user: actor("factory_super_admin"), isAuthenticated: true }); });
    routeParams = { shipmentId: "SH-1" };
    getShipmentById.mockResolvedValue(shipment({ totalAmount: 60 }));
    const view = renderScreen("outbound");
    await waitFor(() => expect(view.getByText("¥60")).toBeTruthy());

    act(() => { useAuthStore.setState({ user: actor("warehouse_worker"), isAuthenticated: true }); });
    await waitFor(() => expect(view.queryByText("¥60")).toBeNull());
    expect(view.getAllByText("无权限查看价格").length).toBeGreaterThan(0);
    expect(view.queryByText("金额未提供")).toBeNull();
  });

  it("executes retry for the current owner but ignores a retained retry after account or factory change", async () => {
    getShipmentById.mockRejectedValueOnce(new Error("首次读取失败"));
    const view = renderScreen("outbound");
    await waitFor(() => expect(view.getByText("首次读取失败")).toBeTruthy());
    const retainedRetry = view.getByText("重试");
    const retainedRetryCallback: unknown = view.UNSAFE_getByType(Button).props.onPress;
    expect(typeof retainedRetryCallback).toBe("function");
    if (typeof retainedRetryCallback !== "function") throw new Error("Rendered Retry callback is missing");

    getShipmentById.mockResolvedValueOnce(shipment({ productName: "重试成功的当前详情" }));
    fireEvent.press(retainedRetry);
    await waitFor(() => expect(view.getByText("重试成功的当前详情")).toBeTruthy());
    expect(getShipmentById).toHaveBeenCalledTimes(2);
    expect(getShipmentById).toHaveBeenLastCalledWith("SH-1", "F006");

    getShipmentById.mockResolvedValue(shipment({ productName: "账号切换后的详情" }));
    act(() => { useAuthStore.setState({ user: actor("warehouse_worker", 8, "F006"), isAuthenticated: true }); });
    await act(async () => { view.rerender(<WHOutboundDetailScreen />); });
    await waitFor(() => expect(view.getByText("账号切换后的详情")).toBeTruthy());
    const afterAccountChange = getShipmentById.mock.calls.length;
    act(() => { retainedRetryCallback(); });
    expect(getShipmentById.mock.calls.length).toBe(afterAccountChange);

    getShipmentById.mockResolvedValue(shipment({ factoryId: "F007", productName: "工厂切换后的详情" }));
    act(() => { useAuthStore.setState({ user: actor("warehouse_worker", 8, "F007"), isAuthenticated: true }); });
    await act(async () => { view.rerender(<WHOutboundDetailScreen />); });
    await waitFor(() => expect(view.getByText("工厂切换后的详情")).toBeTruthy());
    const afterFactoryChange = getShipmentById.mock.calls.length;
    act(() => { retainedRetryCallback(); });
    expect(getShipmentById.mock.calls.length).toBe(afterFactoryChange);
  });

  it("rejects a response from the wrong factory or entity", async () => {
    getShipmentById.mockResolvedValueOnce(shipment({ factoryId: "F007" }));
    const wrongFactory = renderScreen("outbound");
    await waitFor(() => expect(wrongFactory.getByText("发货详情身份校验失败，请重新打开当前单据")).toBeTruthy());
    expect(wrongFactory.queryByText("测试猪蹄")).toBeNull();

    wrongFactory.unmount();
    getShipmentById.mockReset();
    getShipmentById.mockResolvedValueOnce(shipment({ id: "SH-OTHER" }));
    routeParams = { orderId: "SH-1" };
    const wrongId = renderScreen("order");
    await waitFor(() => expect(wrongId.getByText("订单详情身份校验失败，请重新打开当前单据")).toBeTruthy());
    expect(wrongId.queryByText("测试猪蹄")).toBeNull();
  });

  it("does not let a same-role different-account response overwrite the current detail", async () => {
    let resolveOld: (value: ShipmentRecord) => void = () => undefined;
    getShipmentById.mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }));
    const view = renderScreen("outbound");

    getShipmentById.mockResolvedValueOnce(shipment({ productName: "新账号单据" }));
    act(() => { useAuthStore.setState({ user: actor("warehouse_worker", 8), isAuthenticated: true }); });
    await act(async () => { view.rerender(<WHOutboundDetailScreen />); });
    await waitFor(() => expect(view.getByText("新账号单据")).toBeTruthy());
    await act(async () => { resolveOld(shipment({ productName: "旧账号单据", totalAmount: 999999 })); });
    expect(view.queryByText("旧账号单据")).toBeNull();
    expect(view.queryByText("¥999,999")).toBeNull();
  });

  it("ignores delayed success and keeps loading until the current generation completes", async () => {
    let resolveOld: (value: ShipmentRecord) => void = () => undefined;
    let resolveNew: (value: ShipmentRecord) => void = () => undefined;
    getShipmentById
      .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve; }));
    const view = renderScreen("outbound");
    routeParams = { shipmentId: "SH-2" };
    await act(async () => { view.rerender(<WHOutboundDetailScreen />); });
    await waitFor(() => expect(getShipmentById).toHaveBeenCalledTimes(2));
    await act(async () => { resolveOld(shipment({ productName: "旧迟到结果" })); });
    expect(view.getByText("加载中...")).toBeTruthy();
    await act(async () => { resolveNew(shipment({ id: "SH-2", productName: "当前结果" })); });
    await waitFor(() => expect(view.getByText("当前结果")).toBeTruthy());
    expect(view.queryByText("旧迟到结果")).toBeNull();
  });

  it("ignores delayed error and finally from a previous generation", async () => {
    let rejectOld: (error: Error) => void = () => undefined;
    let resolveNew: (value: ShipmentRecord) => void = () => undefined;
    getShipmentById
      .mockReturnValueOnce(new Promise((_resolve, reject) => { rejectOld = reject; }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve; }));
    routeParams = { orderId: "SH-1" };
    const view = renderScreen("order");
    routeParams = { orderId: "SH-2" };
    await act(async () => { view.rerender(<WHOrderDetailScreen />); });
    await waitFor(() => expect(getShipmentById).toHaveBeenCalledTimes(2));
    await act(async () => { rejectOld(new Error("旧请求失败")); });
    expect(view.queryByText("旧请求失败")).toBeNull();
    expect(view.getByText("加载中...")).toBeTruthy();
    await act(async () => { resolveNew(shipment({ id: "SH-2", productName: "当前订单" })); });
    await waitFor(() => expect(view.getAllByText("当前订单").length).toBeGreaterThan(0));
    expect(view.queryByText("旧请求失败")).toBeNull();
  });

  it("guards retained back navigation after the actor changes", async () => {
    getShipmentById.mockResolvedValue(shipment());
    const view = renderScreen("outbound");
    await waitFor(() => expect(view.getByText("测试猪蹄")).toBeTruthy());
    const retainedBack = view.UNSAFE_getAllByType(TouchableOpacity)[0]?.props.onPress as (() => void) | undefined;
    act(() => { useAuthStore.setState({ user: actor("warehouse_worker", 8), isAuthenticated: true }); });
    await act(async () => { retainedBack?.(); });
    expect(goBack).not.toHaveBeenCalled();
  });
});
