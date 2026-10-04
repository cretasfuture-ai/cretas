import React from "react";
import { act, fireEvent, render, waitFor } from "@testing-library/react-native";
import type { FactoryRole, FactoryUser, User } from "../../../types/auth";
import type { MaterialBatch } from "../../../services/api/materialBatchApiClient";

let liveUser: User | null = null;
let mockRouteParams: Record<string, string | undefined> = { materialId: "MAT-1" };
const getMaterialBatches = jest.fn();
const getBatchById = jest.fn();
const getBatchUsageHistory = jest.fn();
const getFullTrace = jest.fn();
const getImpact = jest.fn();
const generateMaterialBatchLabel = jest.fn();
const getLabelsByBatchId = jest.fn();
const recordPrint = jest.fn();
const printBatchLabel = jest.fn();
const navigationNavigate = jest.fn();
const parentNavigate = jest.fn();
const parentNavigation = { navigate: parentNavigate };
const navigationGoBack = jest.fn();
const mockTranslate = (key: string) => key;

jest.mock("@react-navigation/native", () => ({
  ...jest.requireActual("@react-navigation/native"),
  useNavigation: () => ({ goBack: navigationGoBack, navigate: navigationNavigate, dispatch: jest.fn(), getParent: () => parentNavigation }),
  useRoute: () => ({ params: mockRouteParams }),
}));

jest.mock("../../../store/authStore", () => ({
  useAuthStore: Object.assign(
    (selector?: (state: { user: User | null }) => unknown) => selector ? selector({ user: liveUser }) : { user: liveUser },
    { getState: () => ({ user: liveUser }) },
  ),
}));

jest.mock("../../../services/api/materialBatchApiClient", () => ({
  materialBatchApiClient: { getMaterialBatches, getBatchById, getBatchUsageHistory },
}));
jest.mock("../../../services/api/traceabilityApiClient", () => ({ traceabilityApiClient: { getImpact, getFullTrace } }));
jest.mock("../../../services/api/labelApiClient", () => ({ labelApiClient: { generateMaterialBatchLabel, getLabelsByBatchId, recordPrint } }));
jest.mock("../../../services/BatchLabelPrintService", () => ({ printBatchLabel }));
jest.mock("react-i18next", () => ({ useTranslation: () => ({ t: mockTranslate }) }));

import WHInventoryDetailScreen from "../../../screens/warehouse/inventory/WHInventoryDetailScreen";
import WHBatchDetailScreen from "../../../screens/warehouse/inventory/WHBatchDetailScreen";
import WHBatchTraceScreen from "../../../screens/warehouse/shared/WHBatchTraceScreen";
import { useAuthStore } from "../../../store/authStore";

const actor = (role: FactoryRole, factoryId = "F006", price = false): FactoryUser => ({
  id: role === "factory_super_admin" ? 1 : 2,
  username: role,
  email: `${role}@test.invalid`,
  fullName: role,
  createdAt: "2026-10-04",
  updatedAt: "2026-10-04",
  isActive: true,
  userType: "factory",
  factoryId,
  factoryUser: { factoryId, role, permissions: [], modulePermissions: { procurement: price ? "rw" : "-", warehouse: "r" } },
});

const batch = (overrides: Partial<MaterialBatch> = {}): MaterialBatch => ({
  id: "BATCH-1",
  batchNumber: "MB-F006-1",
  factoryId: "F006",
  materialTypeId: "MAT-1",
  materialName: "鸡胸肉",
  materialCategory: "肉类",
  inboundQuantity: 100,
  remainingQuantity: 80,
  reservedQuantity: 0,
  usedQuantity: 20,
  quantityUnit: "box",
  unit: "kg",
  unitPrice: null,
  totalCost: null,
  supplierId: "SUP-1",
  inboundDate: "2026-10-01",
  productionDate: "2026-10-01",
  expiryDate: "2026-10-20",
  status: "available",
  storageType: "fresh",
  storageLocation: "WH-1",
  createdAt: "2026-10-01T08:00:00",
  ...overrides,
});

function listResponse(rows: MaterialBatch[], totalPages = 1) {
  return { success: true, code: 200, message: "", data: { content: rows, totalElements: rows.length, totalPages } };
}

interface PressNode { props: { onPress?: unknown }; parent: PressNode | null; }
function onPressFor(instance: PressNode): (() => void) | undefined {
  let current: PressNode | null = instance;
  for (let depth = 0; current && depth < 6; depth += 1) {
    if (typeof current.props?.onPress === "function") return current.props.onPress as () => void;
    current = current.parent;
  }
  return undefined;
}

function impactResponse(overrides: Record<string, unknown> = {}) {
  return {
    rootType: "MATERIAL_BATCH",
    rootId: "BATCH-1",
    productionBatches: [],
    finishedBatches: [],
    shipments: [],
    ...overrides,
  };
}

beforeEach(() => {
  liveUser = actor("warehouse_worker");
  getMaterialBatches.mockReset();
  getBatchById.mockReset();
  getBatchUsageHistory.mockReset();
  getFullTrace.mockReset();
  getImpact.mockReset();
  generateMaterialBatchLabel.mockReset();
  getLabelsByBatchId.mockReset();
  recordPrint.mockReset();
  printBatchLabel.mockReset();
  navigationNavigate.mockReset();
  parentNavigate.mockReset();
  navigationGoBack.mockReset();
  getMaterialBatches.mockResolvedValue(listResponse([batch()]));
  getBatchById.mockResolvedValue({ success: true, data: batch() });
  getBatchUsageHistory.mockResolvedValue({ success: true, data: [] });
  getImpact.mockResolvedValue(impactResponse());
  generateMaterialBatchLabel.mockResolvedValue({ success: true, data: { id: "L-1", factoryId: "F006", batchId: "BATCH-1", labelCode: "LBL-1", status: "ACTIVE" } });
  getLabelsByBatchId.mockResolvedValue({ success: true, data: [] });
  recordPrint.mockResolvedValue({ success: true, data: { id: "L-1", factoryId: "F006", batchId: "BATCH-1", status: "PRINTED" } });
  printBatchLabel.mockResolvedValue(undefined);
  mockRouteParams = { materialId: "MAT-1" };
});

describe("warehouse material detail readback", () => {
  it("uses explicit factory and material type, hides prices, and keeps real unit/expiry facts", async () => {
    getMaterialBatches.mockResolvedValue(listResponse([batch({ unitPrice: 12.34, movingAvgPrice: 8.76, totalCost: 99.99 })]));
    const view = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(view.getAllByText("鸡胸肉").length).toBeGreaterThan(0));
    expect(getMaterialBatches).toHaveBeenCalledWith({ factoryId: "F006", materialTypeId: "MAT-1", page: 1, size: 50 });
    expect(view.getAllByText("80 box").length).toBeGreaterThan(0);
    expect(JSON.stringify(view.toJSON())).not.toContain("¥");
    expect(view.queryByText("¥30")).toBeNull();
    expect(view.queryByText("200")).toBeNull();
    expect(view.queryByText("999")).toBeNull();
  });

  it("applies the authorized inventory detail nonzero, zero and missing price matrix", async () => {
    const allowed = actor("factory_super_admin", "F006", true);
    liveUser = allowed;
    mockRouteParams = { materialId: "MAT-1" };
    getMaterialBatches.mockResolvedValue(listResponse([batch({ unitPrice: 12.34, movingAvgPrice: 8.76, totalCost: 99.99 })]));
    const view = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(view.getByText("¥12.34")).toBeTruthy());
    expect(view.getByText("¥8.76")).toBeTruthy();

    getMaterialBatches.mockResolvedValue(listResponse([batch({ unitPrice: 0, movingAvgPrice: null })]));
    liveUser = { ...allowed };
    await act(async () => { view.rerender(<WHInventoryDetailScreen />); });
    await waitFor(() => expect(view.getByText("¥0")).toBeTruthy());
    expect(view.queryByText("¥8.76")).toBeNull();

    getMaterialBatches.mockResolvedValue(listResponse([batch({ unitPrice: null, movingAvgPrice: null })]));
    liveUser = { ...allowed };
    await act(async () => { view.rerender(<WHInventoryDetailScreen />); });
    await waitFor(() => expect(view.queryByText("¥0")).toBeNull());
    expect(JSON.stringify(view.toJSON())).not.toContain("¥");
  });

  it("immediately masks a visible nonzero inventory price on same-id revocation while the new request is pending", async () => {
    const allowed = actor("factory_super_admin", "F006", true);
    liveUser = allowed;
    getMaterialBatches.mockResolvedValue(listResponse([batch({ unitPrice: 12.34, movingAvgPrice: 8.76, totalCost: 99.99 })]));
    const view = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(view.getByText("¥12.34")).toBeTruthy());
    expect(view.getByText("¥8.76")).toBeTruthy();
    expect(view.getAllByText("鸡胸肉").length).toBeGreaterThan(0);
    const requestsBeforeRevocation = getMaterialBatches.mock.calls.length;
    let resolveRevoked: (value: ReturnType<typeof listResponse>) => void = () => undefined;
    getMaterialBatches.mockReturnValue(new Promise((resolve) => { resolveRevoked = resolve; }));
    liveUser = { ...allowed, factoryUser: { ...allowed.factoryUser, role: "warehouse_worker", modulePermissions: { procurement: "-", warehouse: "r" } } };
    expect(liveUser.id).toBe(allowed.id);
    expect(liveUser.factoryId).toBe(allowed.factoryId);
    await act(async () => { view.rerender(<WHInventoryDetailScreen />); });
    expect(getMaterialBatches).toHaveBeenCalledTimes(requestsBeforeRevocation + 1);
    expect(view.queryByText("¥12.34")).toBeNull();
    expect(view.queryByText("¥8.76")).toBeNull();
    expect(JSON.stringify(view.toJSON())).not.toContain("¥");
    expect(view.queryByText("鸡胸肉")).toBeNull();
    await act(async () => { resolveRevoked(listResponse([batch({ unitPrice: 12.34, movingAvgPrice: 8.76, totalCost: 99.99 })])); });
    await waitFor(() => expect(view.getAllByText("鸡胸肉").length).toBeGreaterThan(0));
    expect(view.getAllByText("80 box").length).toBeGreaterThan(0);
    expect(JSON.stringify(view.toJSON())).not.toContain("¥");
  });

  it("hides malicious nonzero prices for a denied actor across the rendered detail tree", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchById.mockResolvedValue({ success: true, data: batch({ unitPrice: 12.34, totalCost: 99.99, movingAvgPrice: 8.76 }) });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    expect(view.queryByText("¥12.34")).toBeNull();
    expect(view.queryByText("¥8.76")).toBeNull();
    expect(view.queryByText("¥99.99")).toBeNull();
  });

  it("shows an authorized nonzero price while preserving true zero and null states", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchById.mockResolvedValue({ success: true, data: batch({ unitPrice: 12.34, totalCost: 99.99, movingAvgPrice: 8.76 }) });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("¥12.34").length).toBeGreaterThan(0));
    expect(view.getAllByText("¥8.76").length).toBeGreaterThan(0);
    getBatchById.mockResolvedValue({ success: true, data: batch({ unitPrice: 0, movingAvgPrice: null }) });
    liveUser = actor("factory_super_admin", "F006", true);
    await act(async () => { view.rerender(<WHBatchDetailScreen />); });
    await waitFor(() => expect(view.getAllByText("¥0").length).toBeGreaterThan(0));
    expect(view.queryByText("¥8.76")).toBeNull();
  });

  it("keeps an authorized zero price and never invents a missing price", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchById.mockResolvedValue({ success: true, data: batch({ unitPrice: 0, movingAvgPrice: 0 }) });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("¥0").length).toBeGreaterThan(0));
    expect(view.queryByText("¥30")).toBeNull();
    getBatchById.mockResolvedValue({ success: true, data: batch({ unitPrice: null, movingAvgPrice: null, expiryDate: undefined }) });
    await act(async () => { liveUser = actor("warehouse_worker"); view.rerender(<WHBatchDetailScreen />); });
    await waitFor(() => expect(view.getAllByText("batch.detail.expiryUnknown").length).toBeGreaterThan(0));
    expect(view.queryByText("¥0")).toBeNull();
    expect(view.queryByText("999")).toBeNull();
  });

  it("masks the old price and detail on the same user id when permissions are revoked", async () => {
    const allowed = actor("factory_super_admin", "F006", true);
    liveUser = allowed;
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchById.mockResolvedValue({ success: true, data: batch({ unitPrice: 12.34, movingAvgPrice: 8.76 }) });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getByText("¥12.34")).toBeTruthy());
    let resolveRevoked: (value: { success: boolean; data: MaterialBatch }) => void = () => undefined;
    getBatchById.mockReturnValue(new Promise((resolve) => { resolveRevoked = resolve; }));
    liveUser = { ...allowed, factoryUser: { ...allowed.factoryUser, role: "warehouse_worker", modulePermissions: { procurement: "-", warehouse: "r" } } };
    await act(async () => { view.rerender(<WHBatchDetailScreen />); });
    expect(view.queryByText("¥12.34")).toBeNull();
    expect(view.queryByText("鸡胸肉")).toBeNull();
    resolveRevoked({ success: true, data: batch({ unitPrice: null, movingAvgPrice: null }) });
  });

  it("keeps a missing quantityUnit unknown in detail and print payload", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchById.mockResolvedValue({ success: true, data: batch({ quantityUnit: undefined, unit: "kg" }) });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText(/inventoryDetail\.unknownUnit/).length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(printBatchLabel).toHaveBeenCalledWith(expect.objectContaining({ quantityUnit: "inventoryDetail.unknownUnit" })));
  });

  it("resolves inventoryId as a batch before requesting its material type", async () => {
    mockRouteParams = { inventoryId: "BATCH-1" };
    const view = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(view.getAllByText("鸡胸肉").length).toBeGreaterThan(0));
    expect(getBatchById).toHaveBeenCalledWith("BATCH-1", "F006");
    expect(getMaterialBatches).toHaveBeenCalledWith({ factoryId: "F006", materialTypeId: "MAT-1", page: 1, size: 50 });
  });

  it("looks up a warning batch by scoped batch number without treating the number as an id", async () => {
    mockRouteParams = { batchNumber: "MB-F006-1" };
    getMaterialBatches.mockImplementation((params: { page?: number }) => params.page === 1
      ? listResponse([batch({ batchNumber: "MB-F006-other", materialName: "第一页" })], 2)
      : listResponse([batch()], 2));
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    expect(getMaterialBatches).toHaveBeenNthCalledWith(1, { factoryId: "F006", keyword: "MB-F006-1", page: 1, size: 50 });
    expect(getMaterialBatches).toHaveBeenNthCalledWith(2, { factoryId: "F006", keyword: "MB-F006-1", page: 2, size: 50 });
    expect(getBatchById).not.toHaveBeenCalledWith("MB-F006-1", "F006");
    expect(view.getByText("80 box")).toBeTruthy();
  });

  it("rejects duplicate or wrong-factory exact batch numbers", async () => {
    mockRouteParams = { batchNumber: "MB-F006-1" };
    getMaterialBatches.mockResolvedValueOnce(listResponse([batch({ factoryId: "F007" }), batch()], 1));
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getByText("messages.batchIdentityMismatch")).toBeTruthy());
    expect(view.queryByText("80 box")).toBeNull();
  });

  it.each([
    ["wrong batch id", { id: "OTHER-BATCH" }],
    ["wrong factory", { factoryId: "F007" }],
    ["wrong material type", { materialTypeId: "MAT-OTHER" }],
  ])("rejects %s from a direct batch response", async (_label, override) => {
    mockRouteParams = { batchId: "BATCH-1", ...(_label === "wrong material type" ? { materialTypeId: "MAT-1" } : {}) };
    getBatchById.mockResolvedValue({ success: true, data: batch(override) });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getByText("批次身份校验失败")).toBeTruthy());
    expect(view.queryByText("80 box")).toBeNull();
  });

  it("does not let an old actor response render after the actor changes", async () => {
    let resolveOld: (value: { success: boolean; code: number; message: string; data: { content: MaterialBatch[]; totalElements: number; totalPages: number } }) => void = () => undefined;
    getMaterialBatches.mockReturnValue(new Promise((resolve) => { resolveOld = resolve; }));
    const view = render(<WHInventoryDetailScreen />);
    liveUser = actor("warehouse_worker", "F007");
    await act(async () => { view.rerender(<WHInventoryDetailScreen />); });
    await act(async () => { resolveOld(listResponse([batch({ factoryId: "F006", materialName: "旧工厂" })])); });
    expect(view.queryByText("旧工厂")).toBeNull();
    expect(view.queryByText("¥30")).toBeNull();
  });

  it("invalidates a retained list response when the same actor changes material route", async () => {
    let resolveOld: (value: ReturnType<typeof listResponse>) => void = () => undefined;
    let resolveNew: (value: ReturnType<typeof listResponse>) => void = () => undefined;
    getMaterialBatches.mockReset();
    getMaterialBatches
      .mockImplementationOnce(() => new Promise<ReturnType<typeof listResponse>>((resolve) => { resolveOld = resolve; }))
      .mockImplementationOnce(() => new Promise<ReturnType<typeof listResponse>>((resolve) => { resolveNew = resolve; }));

    const view = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(getMaterialBatches).toHaveBeenCalledTimes(1));
    mockRouteParams = { materialId: "MAT-2" };
    await act(async () => { view.rerender(<WHInventoryDetailScreen />); });
    await waitFor(() => expect(getMaterialBatches).toHaveBeenCalledTimes(2));

    await act(async () => { resolveOld(listResponse([batch({ materialName: "旧物料", materialTypeId: "MAT-1" })])); });
    expect(view.queryByText("旧物料")).toBeNull();
    await act(async () => { resolveNew(listResponse([batch({ materialName: "新物料", materialTypeId: "MAT-2" })])); });
    await waitFor(() => expect(view.getAllByText("新物料").length).toBeGreaterThan(0));
  });

  it("blocks retained expiry and trace navigation callbacks after an actor switch", async () => {
    mockRouteParams = { materialId: "MAT-1" };
    const inventoryView = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(inventoryView.getByText("batch.detail.actions.expireHandle")).toBeTruthy());
    const currentExpire = onPressFor(inventoryView.getByText("batch.detail.actions.expireHandle"));
    expect(currentExpire).toEqual(expect.any(Function));
    await act(async () => { currentExpire?.(); });
    expect(navigationNavigate).toHaveBeenCalledWith("WHExpireHandle", {});
    navigationNavigate.mockClear();
    const oldExpire = currentExpire;
    liveUser = actor("warehouse_worker", "F007", false);
    await act(async () => { inventoryView.rerender(<WHInventoryDetailScreen />); });
    oldExpire?.();
    expect(navigationNavigate).not.toHaveBeenCalledWith("WHExpireHandle", expect.anything());

    liveUser = actor("warehouse_worker", "F006", false);
    mockRouteParams = { batchId: "BATCH-1" };
    const batchView = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(batchView.getByText("batch.detail.actions.trace")).toBeTruthy());
    const currentTrace = onPressFor(batchView.getByText("batch.detail.actions.trace"));
    expect(currentTrace).toEqual(expect.any(Function));
    await act(async () => { currentTrace?.(); });
    expect(navigationNavigate).toHaveBeenCalledWith("WHBatchTrace", { batchId: "BATCH-1", batchNumber: "MB-F006-1" });
    navigationNavigate.mockClear();
    const oldTrace = currentTrace;
    liveUser = actor("warehouse_worker", "F007", false);
    await act(async () => { batchView.rerender(<WHBatchDetailScreen />); });
    oldTrace?.();
    expect(navigationNavigate).not.toHaveBeenCalledWith("WHBatchTrace", expect.anything());
  });

  it("blocks retained retry, expire, trace and recall callbacks after a same-role account switch", async () => {
    getMaterialBatches.mockRejectedValueOnce(new Error("old load"));
    mockRouteParams = { materialId: "MAT-1" };
    const inventoryView = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(inventoryView.getByText("messages.retry")).toBeTruthy());
    const oldRetry = onPressFor(inventoryView.getByText("messages.retry"));
    expect(oldRetry).toEqual(expect.any(Function));
    const oldGetCount = getMaterialBatches.mock.calls.length;
    await act(async () => { oldRetry?.(); });
    expect(getMaterialBatches.mock.calls.length).toBe(oldGetCount + 1);
    const callsBeforeOwnerSwitch = getMaterialBatches.mock.calls.length;
    liveUser = { ...actor("warehouse_worker", "F006"), id: 77, username: "new-worker" };
    await act(async () => { inventoryView.rerender(<WHInventoryDetailScreen />); });
    const callsAfterOwnerSwitch = getMaterialBatches.mock.calls.length;
    oldRetry?.();
    expect(getMaterialBatches.mock.calls.length).toBe(callsAfterOwnerSwitch);
    expect(callsAfterOwnerSwitch).toBeGreaterThan(callsBeforeOwnerSwitch);
    expect(navigationNavigate).not.toHaveBeenCalled();

    liveUser = actor("warehouse_worker", "F006");
    mockRouteParams = { batchId: "BATCH-1" };
    const batchView = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(batchView.getByText("batch.detail.actions.expireHandle")).toBeTruthy());
    const currentExpire = onPressFor(batchView.getByText("batch.detail.actions.expireHandle"));
    const currentTrace = onPressFor(batchView.getByText("batch.detail.actions.trace"));
    expect(currentExpire).toEqual(expect.any(Function));
    expect(currentTrace).toEqual(expect.any(Function));
    await act(async () => { currentExpire?.(); currentTrace?.(); });
    expect(navigationNavigate).toHaveBeenCalledWith("WHExpireHandle", { batchId: "BATCH-1", batchNumber: "MB-F006-1" });
    expect(navigationNavigate).toHaveBeenCalledWith("WHBatchTrace", { batchId: "BATCH-1", batchNumber: "MB-F006-1" });
    navigationNavigate.mockClear();
    const oldExpire = currentExpire;
    const oldTrace = currentTrace;
    liveUser = { ...actor("warehouse_worker", "F006"), id: 78, username: "other-worker" };
    await act(async () => { batchView.rerender(<WHBatchDetailScreen />); });
    oldExpire?.();
    oldTrace?.();
    expect(navigationNavigate).not.toHaveBeenCalled();

    liveUser = actor("warehouse_worker", "F006");
    const traceView = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(traceView.getByText("shared.batchTrace.recall")).toBeTruthy());
    const currentRecall = onPressFor(traceView.getByText("shared.batchTrace.recall"));
    expect(currentRecall).toEqual(expect.any(Function));
    currentRecall?.();
    expect(parentNavigate).toHaveBeenCalledWith("WHProfileTab", { screen: "WHRecallManage" });
    parentNavigate.mockClear();
    const oldRecall = currentRecall;
    liveUser = { ...actor("warehouse_worker", "F006"), id: 79, username: "recall-worker" };
    await act(async () => { traceView.rerender(<WHBatchTraceScreen />); });
    oldRecall?.();
    expect(parentNavigate).not.toHaveBeenCalled();
  });

  it("keeps new owner data after an old request rejects late", async () => {
    let rejectOld: (reason?: unknown) => void = () => undefined;
    getMaterialBatches.mockReset();
    getMaterialBatches
      .mockImplementationOnce(() => new Promise((_, reject) => { rejectOld = reject; }))
      .mockResolvedValueOnce(listResponse([batch({ factoryId: "F007", materialName: "新工厂数据" })]));
    const view = render(<WHInventoryDetailScreen />);
    await waitFor(() => expect(getMaterialBatches).toHaveBeenCalledTimes(1));
    liveUser = actor("warehouse_worker", "F007");
    await act(async () => { view.rerender(<WHInventoryDetailScreen />); });
    await waitFor(() => expect(view.getAllByText("新工厂数据").length).toBeGreaterThan(0));
    await act(async () => { rejectOld(new Error("old late failure")); });
    expect(view.queryByText("old late failure")).toBeNull();
    expect(view.getAllByText("新工厂数据").length).toBeGreaterThan(0);
  });

  it("resolves the real batch number before trace calls and exposes partial failures with retry", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    getImpact.mockRejectedValueOnce(new Error("trace unavailable"));
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(getImpact).toHaveBeenCalledWith("MATERIAL_BATCH", "BATCH-1", { factoryId: "F006" }));
    expect(getBatchUsageHistory).toHaveBeenCalledWith("BATCH-1", "F006");
    await waitFor(() => expect(view.getByText("shared.batchTrace.partialFailure")).toBeTruthy());
    getImpact.mockResolvedValueOnce(impactResponse());
    await act(async () => { fireEvent.press(view.getByText("messages.retry")); });
    await waitFor(() => expect(view.queryByText("shared.batchTrace.partialFailure")).toBeNull());
  });

  it("uses parent tab navigation for recall from traceability", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.recall")).toBeTruthy());
    fireEvent.press(view.getByText("shared.batchTrace.recall"));
    expect(parentNavigate).toHaveBeenCalledWith("WHProfileTab", { screen: "WHRecallManage" });
  });

  it("paginates trace batch-number lookup before calling trace APIs", async () => {
    mockRouteParams = { batchNumber: "MB-F006-1" };
    getMaterialBatches.mockImplementation((params: { page?: number }) => params.page === 1
      ? listResponse([batch({ batchNumber: "MB-F006-other" })], 2)
      : listResponse([batch()], 2));
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(getImpact).toHaveBeenCalledWith("MATERIAL_BATCH", "BATCH-1", { factoryId: "F006" }));
    expect(getMaterialBatches).toHaveBeenNthCalledWith(1, { factoryId: "F006", keyword: "MB-F006-1", page: 1, size: 50 });
    expect(getMaterialBatches).toHaveBeenNthCalledWith(2, { factoryId: "F006", keyword: "MB-F006-1", page: 2, size: 50 });
    expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0);
  });

  it("renders validated material impact nodes with their real units and names", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    getImpact.mockResolvedValueOnce(impactResponse({
      productionBatches: [{ nodeType: "PRODUCTION_BATCH", nodeId: "PB-ID-1", batchNumber: "PB-1", name: "生产批次一", quantity: 12, unit: "box", eventTime: "2026-10-02T08:00:00" }],
      finishedBatches: [{ nodeType: "FINISHED_BATCH", nodeId: "FG-ID-1", batchNumber: "FG-1", name: "成品批次一", quantity: 3, unit: null, eventTime: "2026-10-03" }],
      shipments: [{ nodeType: "SHIPMENT_RECORD", nodeId: "SHIP-ID-1", batchNumber: "SHIP-1", name: "客户一", quantity: 2, eventTime: "2026-10-04" }],
    }));
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(view.getByText("生产批次一")).toBeTruthy());
    expect(view.getByText("shared.batchTrace.productionImpact")).toBeTruthy();
    expect(view.queryByText("shared.batchTrace.upstream")).toBeNull();
    expect(view.getByText("12 box")).toBeTruthy();
    expect(view.getByText("成品批次一")).toBeTruthy();
    expect(view.getAllByText(/inventoryDetail\.unknownUnit/).length).toBeGreaterThanOrEqual(2);
    expect(view.getByText("客户一")).toBeTruthy();
    expect(view.getByText("shared.batchTrace.qualityUnavailable")).toBeTruthy();
  });

  it("treats a wrong-root or malformed material impact response as partial failure", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    getImpact.mockResolvedValueOnce(impactResponse({ rootType: "PRODUCTION_BATCH" }));
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.partialFailure")).toBeTruthy());
    getImpact.mockResolvedValueOnce(impactResponse({ productionBatches: [{ nodeType: "PRODUCTION_BATCH", quantity: 12 }] }));
    await act(async () => { fireEvent.press(view.getByText("messages.retry")); });
    await waitFor(() => expect(view.getByText("shared.batchTrace.partialFailure")).toBeTruthy());
    getImpact.mockResolvedValueOnce({ success: false, data: [] });
    await act(async () => { fireEvent.press(view.getByText("messages.retry")); });
    await waitFor(() => expect(view.getByText("shared.batchTrace.partialFailure")).toBeTruthy());
  });

  it("treats a fulfilled unsuccessful usage response as partial failure, while an empty success is complete", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchUsageHistory.mockResolvedValueOnce({ success: false, data: [] });
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.partialFailure")).toBeTruthy());
    getBatchUsageHistory.mockResolvedValueOnce({ success: true, data: [] });
    await act(async () => { fireEvent.press(view.getByText("messages.retry")); });
    await waitFor(() => expect(view.queryByText("shared.batchTrace.partialFailure")).toBeNull());
    expect(view.getByText("shared.batchTrace.qualityUnavailable")).toBeTruthy();
  });

  it("treats a malformed fulfilled usage record as partial failure", async () => {
    mockRouteParams = { batchId: "BATCH-1" };
    getBatchUsageHistory.mockResolvedValueOnce({ success: true, data: [{}] });
    const view = render(<WHBatchTraceScreen />);
    await waitFor(() => expect(view.getByText("shared.batchTrace.partialFailure")).toBeTruthy());
  });

  it("passes captured factory to label routes and rejects a wrong factory label before printing", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    generateMaterialBatchLabel.mockResolvedValueOnce({ success: true, data: { id: "L-1", factoryId: "F007", batchId: "BATCH-1", labelCode: "WRONG", status: "ACTIVE" } });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(view.getByText("messages.printIdentityMismatch")).toBeTruthy());
    expect(generateMaterialBatchLabel).toHaveBeenCalledWith("BATCH-1", "F006");
    expect(printBatchLabel).not.toHaveBeenCalled();
  });

  it("rejects a correctly factory-scoped label for a different batch", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    generateMaterialBatchLabel.mockResolvedValueOnce({ success: true, data: { id: "L-1", factoryId: "F006", batchId: "OTHER-BATCH", labelCode: "WRONG-BATCH", status: "ACTIVE" } });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(view.getByText("messages.printIdentityMismatch")).toBeTruthy());
    expect(printBatchLabel).not.toHaveBeenCalled();
    expect(recordPrint).not.toHaveBeenCalled();
  });

  it("prints with the current factory and records the real label success", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(recordPrint).toHaveBeenCalledWith("L-1", "F006"));
    expect(printBatchLabel).toHaveBeenCalledWith(expect.objectContaining({ batchNumber: "MB-F006-1", qrContent: "LBL-1" }));
    expect(view.queryByText("batch.detail.printFailed")).toBeNull();
  });

  it("does not call physical printing when the label has no trusted QR or trace code", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    generateMaterialBatchLabel.mockResolvedValueOnce({ success: true, data: { id: "L-1", factoryId: "F006", batchId: "BATCH-1", status: "ACTIVE" } });
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(view.getByText("messages.printLabelUnavailable")).toBeTruthy());
    expect(printBatchLabel).not.toHaveBeenCalled();
  });

  it("does not record a print after the actor changes while printing is pending", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    let finishPrint: () => void = () => undefined;
    printBatchLabel.mockReturnValueOnce(new Promise<void>((resolve) => { finishPrint = resolve; }));
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(printBatchLabel).toHaveBeenCalledTimes(1));
    liveUser = actor("warehouse_worker", "F006", false);
    await act(async () => { view.rerender(<WHBatchDetailScreen />); });
    await act(async () => { finishPrint(); });
    expect(recordPrint).not.toHaveBeenCalled();
    expect(view.queryByText("batch.detail.printFailed")).toBeNull();
  });

  it("shows printer failure retry and lets a new owner print successfully", async () => {
    liveUser = actor("factory_super_admin", "F006", true);
    mockRouteParams = { batchId: "BATCH-1" };
    printBatchLabel.mockRejectedValueOnce(new Error("printer unavailable"));
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(view.getByText("printer unavailable")).toBeTruthy());
    const newOwner = { ...actor("factory_super_admin", "F006", true), id: 88, username: "new-admin" };
    liveUser = newOwner;
    printBatchLabel.mockResolvedValueOnce(undefined);
    await act(async () => { view.rerender(<WHBatchDetailScreen />); });
    await waitFor(() => expect(view.getByText("batch.detail.print")).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(recordPrint).toHaveBeenCalledWith("L-1", "F006"));
    expect(printBatchLabel).toHaveBeenCalledTimes(2);
  });

  it("invalidates an old print promise across an A to B to A actor return", async () => {
    const actorA = actor("factory_super_admin", "F006", true);
    liveUser = actorA;
    mockRouteParams = { batchId: "BATCH-1" };
    let finishPrint: () => void = () => undefined;
    printBatchLabel
      .mockReturnValueOnce(new Promise<void>((resolve) => { finishPrint = resolve; }))
      .mockResolvedValueOnce(undefined);
    const view = render(<WHBatchDetailScreen />);
    await waitFor(() => expect(view.getAllByText("MB-F006-1").length).toBeGreaterThan(0));
    await act(async () => { fireEvent.press(view.getByText("batch.detail.print")); });
    await waitFor(() => expect(printBatchLabel).toHaveBeenCalledTimes(1));
    liveUser = actor("warehouse_worker", "F006", false);
    await act(async () => { view.rerender(<WHBatchDetailScreen />); });
    liveUser = actorA;
    await act(async () => { view.rerender(<WHBatchDetailScreen />); });
    await act(async () => { finishPrint(); });
    expect(recordPrint).not.toHaveBeenCalled();
    const printButton = view.getByText("batch.detail.print");
    expect(printButton).toBeTruthy();
    await act(async () => { fireEvent.press(printButton); });
    await waitFor(() => expect(recordPrint).toHaveBeenCalledWith("L-1", "F006"));
  });
});
