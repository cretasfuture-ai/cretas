/**
 * MaterialBatchPicker — 首道领料批次选择器 (A2b)
 *
 * 用途: 让操作员选择本次领料的原料批次 (1 or N) + 每批次用量, 生成 materialBatchRefs 数组.
 * 调用方: YieldStepReportScreen (currentStepIndex === 0, 首道).
 *
 * ── 单一数据源规则 (Q1) ─────────────────────────────────────────────────────
 * 单批次已选时: 无独立的 per-batch 用量输入。该批次的 quantity = 屏幕的 inputQty
 * (由 singleBatchQty prop 传入)。屏幕的 YieldQuantityInput "投入量" IS 用量。
 * 多批次已选时: 每批显示独立用量输入；投入量 = Σ(各批次用量)，在屏幕层只读展示。
 * 效果：两字段永远不会产生分歧。
 * ────────────────────────────────────────────────────────────────────────────
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  ActivityIndicator,
  StyleSheet,
  ScrollView,
} from 'react-native';
import { materialBatchApiClient, MaterialBatch } from '../../services/api/materialBatchApiClient';
import { yieldReportApi, TaskMaterialBatchOption } from '../../services/api/yieldReportApi';
import BarcodeScannerModal from './BarcodeScannerModal';
import {
  materialLabelApiClient,
  MaterialBatchLabelScanResponse,
} from '../../services/api/materialLabelApiClient';

export interface MaterialBatchRef {
  materialBatchId: string;
  quantity: number;
  unit?: string;
}

// F2: 厂号筛选哨兵 — 批次无厂号时归入此分组 chip
export const NO_FACTORY_NUMBER = '__NO_FACTORY_NUMBER__';

/**
 * F2 厂号筛选纯逻辑 (抽出供单测). 返回:
 *  - keys: 列表里出现的去重厂号顺序 (无厂号 → NO_FACTORY_NUMBER 哨兵)
 *  - showFilter: 是否展示筛选 (≥2 个不同厂号才展示)
 */
export function deriveFactoryNumberKeys(
  factoryNumbers: ReadonlyArray<string | null | undefined>,
): { keys: string[]; showFilter: boolean } {
  const seen = new Set<string>();
  const ordered: string[] = [];
  for (const fn of factoryNumbers) {
    const key = fn != null && fn !== '' ? fn : NO_FACTORY_NUMBER;
    if (!seen.has(key)) {
      seen.add(key);
      ordered.push(key);
    }
  }
  return { keys: ordered, showFilter: ordered.length > 1 };
}

/**
 * F2 一行是否在当前厂号筛选下可见. 已选中行始终可见 (防止操作工"丢失"已选批次).
 */
export function isRowVisibleUnderFactoryFilter(
  factoryNumber: string | null | undefined,
  selected: boolean,
  filter: string | null,
  showFilter: boolean,
): boolean {
  if (!showFilter || filter == null) return true;
  const key = factoryNumber != null && factoryNumber !== '' ? factoryNumber : NO_FACTORY_NUMBER;
  return key === filter || selected;
}

export interface MaterialBatchPickerValidation {
  hasOverLimit: boolean;
  message?: string;
}

interface MaterialBatchPickerProps {
  /** 当前工序单位 (e.g. 'kg'), 作为 ref.unit 默认值 */
  unit: string;
  /** 当前工厂 ID (可选, 不传则 materialBatchApiClient 自动解析) */
  factoryId?: string;
  /**
   * 产品类型 ID (可选, 防呆 BOM 过滤).
   * 传入时后端只返回该产品 ACTIVE BOM 中的原料类型批次;
   * 若产品无 ACTIVE BOM 或 BOM 无明细, 后端回退返回全部 (不阻断操作员).
   */
  productTypeId?: string;
  taskId?: number;
  productionBatchId?: number;
  /** 选中的 refs (受控) */
  value: MaterialBatchRef[];
  /** 变更回调 */
  onChange: (refs: MaterialBatchRef[]) => void;
  /**
   * Q1 单一数据源: 单批次已选时, 该批次的 quantity 由此 prop 驱动 (= 屏幕投入量).
   * 仅在单批次模式生效; 多批次模式下忽略 (各批次用独立输入).
   * 屏幕在 onChange 返回的 refs 里 quantity 若是 single-batch 会自动同步最新 singleBatchQty.
   */
  singleBatchQty?: string;
  /** Force per-batch quantity input even when only one batch is selected. */
  forceIndependentQuantity?: boolean;
  /** 是否禁用 (提交进行中) */
  disabled?: boolean;
  /**
   * B1: When true the header shows a red asterisk (领料批次 *) to signal this field
   * is required. Caller (YieldStepReportScreen first-step) passes required={true}.
   */
  required?: boolean;
  /** Emits client-side stock limit state so parent forms can disable submit before backend 409. */
  onValidationChange?: (validation: MaterialBatchPickerValidation) => void;
}

/** 组件内部行状态 */
interface RowState {
  batch: MaterialBatch;
  selected: boolean;
  qtyStr: string; // 仅多批次模式使用; 单批次模式下忽略 (由 singleBatchQty 驱动)
}

export interface MaterialBatchQuantityAllocation {
  batchId: string;
  quantity: number;
}

export interface MaterialBatchAllocationResult {
  allocations: MaterialBatchQuantityAllocation[];
  allocatedTotal: number;
  shortage: number;
}

const QUANTITY_PRECISION = 6;
const QUANTITY_EPSILON = 0.000001;

function roundQuantity(value: number): number {
  return Number(value.toFixed(QUANTITY_PRECISION));
}

/** 按服务端 FEFO 返回顺序分配；纯函数供目标测试与变异对照使用。 */
export function allocateQuantityAcrossBatches(
  batches: ReadonlyArray<Pick<MaterialBatch, 'id' | 'remainingQuantity'>>,
  targetQuantity: number,
): MaterialBatchAllocationResult {
  if (!Number.isFinite(targetQuantity) || targetQuantity <= 0) {
    return { allocations: [], allocatedTotal: 0, shortage: 0 };
  }

  let remainingTarget = roundQuantity(targetQuantity);
  const allocations: MaterialBatchQuantityAllocation[] = [];

  for (const batch of batches) {
    if (remainingTarget <= QUANTITY_EPSILON) break;
    const available = Number(batch.remainingQuantity);
    if (!Number.isFinite(available) || available <= 0) continue;
    const quantity = roundQuantity(Math.min(available, remainingTarget));
    if (quantity <= 0) continue;
    allocations.push({ batchId: batch.id, quantity });
    remainingTarget = roundQuantity(Math.max(0, remainingTarget - quantity));
  }

  const shortage = remainingTarget <= QUANTITY_EPSILON ? 0 : remainingTarget;
  return {
    allocations,
    allocatedTotal: roundQuantity(targetQuantity - shortage),
    shortage,
  };
}

type AllocationMode = 'choice' | 'auto' | 'manual';

function normalizeQuantityUnit(value: string | null | undefined): string {
  return value?.trim().toLowerCase() ?? '';
}

function formatAllocatedQuantity(value: number): string {
  return String(roundQuantity(value));
}

export const MaterialBatchPicker: React.FC<MaterialBatchPickerProps> = ({
  unit,
  factoryId,
  productTypeId,
  taskId,
  productionBatchId,
  value,
  onChange,
  singleBatchQty = '',
  forceIndependentQuantity = false,
  disabled = false,
  required = false,
  onValidationChange,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [rows, setRows] = useState<RowState[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [loadedQueryKey, setLoadedQueryKey] = useState<string | null>(null);
  // Q2: picker 已通过「确定选择」按钮确认收起 → 显示 summary + 确认反馈
  const [confirmed, setConfirmed] = useState(false);
  const [confirmFeedback, setConfirmFeedback] = useState(false);
  const [scannerVisible, setScannerVisible] = useState(false);
  const [scanLoading, setScanLoading] = useState(false);
  const [scannedLabel, setScannedLabel] = useState<MaterialBatchLabelScanResponse | null>(null);
  // F2 防呆: 同品多厂号时按厂号筛选 (不同厂号 = 不同批次, 易选错). null = 显示全部.
  const [factoryNumberFilter, setFactoryNumberFilter] = useState<string | null>(null);
  const [allocationMode, setAllocationMode] = useState<AllocationMode>('choice');
  const [allocationTargetQty, setAllocationTargetQty] = useState<number | null>(null);
  const [allocationTargetEnforced, setAllocationTargetEnforced] = useState(false);
  const [autoAllocationShortage, setAutoAllocationShortage] = useState(0);
  const [autoAllocationError, setAutoAllocationError] = useState<string | null>(null);
  const [autoAllocationLoading, setAutoAllocationLoading] = useState(false);
  const confirmTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const autoAllocationRequestRef = useRef(0);
  const queryKeyRef = useRef<string>('');
  const rowsRef = useRef<RowState[]>([]);   // mirrors `rows` for use inside async callbacks
  const candidateBatchIdsRef = useRef<Set<string>>(new Set());
  const validationRef = useRef(onValidationChange);
  validationRef.current = onValidationChange;

  rowsRef.current = rows;
  const selectedRowEntries = rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => row.selected);
  const selectedRows = selectedRowEntries.map(({ row }) => row);
  const isMultiBatch = selectedRows.length > 1;
  const queryKey = `${factoryId ?? ''}|${productTypeId ?? ''}|${productionBatchId ?? ''}|${taskId ?? ''}`;
  queryKeyRef.current = queryKey;

  const rowQuantity = useCallback(
    (row: RowState, isSingle: boolean): number | null => {
      const useParentQty =
        isSingle &&
        !forceIndependentQuantity &&
        allocationMode !== 'auto' &&
        allocationTargetQty == null;
      const qty = parseFloat(useParentQty ? singleBatchQty : row.qtyStr);
      return Number.isNaN(qty) || qty <= 0 ? null : qty;
    },
    [allocationMode, allocationTargetQty, forceIndependentQuantity, singleBatchQty],
  );

  const rowOverLimit = useCallback(
    (row: RowState, isSingle: boolean): boolean => {
      const qty = rowQuantity(row, isSingle);
      return qty != null && qty > (row.batch.remainingQuantity ?? 0);
    },
    [rowQuantity],
  );

  // ── onChange 防抖机制 ────────────────────────────────────────────────────
  // Issue #3 fix: emit onChange in a useEffect keyed on rows, NOT inside setRows updater.
  // Calling parent setState (onChange → setMaterialBatchRefs) from inside setRows updater
  // triggers "Cannot update a component while rendering" warning.
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  // Q1: 每当 rows / singleBatchQty / unit 变化时重新 emit refs
  // 单批次: quantity = singleBatchQty (父的 投入量 即用量, 单一真相)
  // 多批次: quantity = 各自独立输入的 qtyStr
  useEffect(() => {
    const refs: MaterialBatchRef[] = [];
    const selected = rows.filter((r) => r.selected);
    const isSingle = selected.length === 1;
    const useParentQty =
      isSingle &&
      !forceIndependentQuantity &&
      allocationMode !== 'auto' &&
      allocationTargetQty == null;

    for (const row of selected) {
      if (useParentQty) {
        // 单批次: qty 由屏幕 投入量 驱动
        const qty = parseFloat(singleBatchQty);
        if (!Number.isNaN(qty) && qty > 0) {
          refs.push({ materialBatchId: row.batch.id, quantity: qty, unit });
        }
      } else {
        // 多批次: 使用各自 qtyStr
        const qty = parseFloat(row.qtyStr);
        if (!Number.isNaN(qty) && qty > 0) {
          refs.push({ materialBatchId: row.batch.id, quantity: qty, unit });
        }
      }
    }
    onChangeRef.current(refs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allocationMode, allocationTargetQty, forceIndependentQuantity, rows, singleBatchQty, unit]);

  useEffect(() => {
    const selected = rows.filter((r) => r.selected);
    const isSingle = selected.length === 1;
    if (allocationMode === 'auto' && autoAllocationShortage > QUANTITY_EPSILON) {
      validationRef.current?.({
        hasOverLimit: true,
        message: `自动分配后还缺 ${formatAllocatedQuantity(autoAllocationShortage)} ${unit}，请改为手动选择批次或联系仓管`,
      });
      return;
    }
    const over = selected.find((row) => rowOverLimit(row, isSingle));
    if (over == null) {
      validationRef.current?.({ hasOverLimit: false });
      return;
    }
    const qty = rowQuantity(over, isSingle);
    const name = over.batch.materialName ?? over.batch.batchNumber;
    validationRef.current?.({
      hasOverLimit: true,
      message: `${name} 最多可领 ${over.batch.remainingQuantity} ${unit}，当前填写 ${qty ?? 0} ${unit}`,
    });
  }, [allocationMode, autoAllocationShortage, rows, rowOverLimit, rowQuantity, unit]);

  // 从 value prop 初始化/同步 rows (仅在 batches 加载完成后)
  // Bug fix: preserve selections that are already in `rows` but not yet propagated
  // to the `value` prop (e.g. a just-scanned batch: handleLabelScan sets rows before
  // the parent's onChange round-trip completes). Without this, loadBatches() calling
  // syncRowsFromValue() wipes the scan selection.
  const syncRowsFromValue = useCallback(
    (batches: MaterialBatch[], pendingRows?: RowState[]) => {
      const refMap = new Map(value.map((r: MaterialBatchRef) => [r.materialBatchId, r]));
      // Build a map of batches already selected in the current rows (covers the scan-lag window)
      const pendingSelectedIds = new Set(
        (pendingRows ?? []).filter((r) => r.selected).map((r) => r.batch.id),
      );
      const pendingQtyMap = new Map(
        (pendingRows ?? []).filter((r) => r.selected).map((r) => [r.batch.id, r.qtyStr]),
      );
      setRows(
        batches.map((b) => {
          const existing = refMap.get(b.id);
          if (existing != null) {
            return { batch: b, selected: true, qtyStr: String(existing.quantity) };
          }
          // Preserve selections that exist in current rows but haven't propagated to value yet
          if (pendingSelectedIds.has(b.id)) {
            return { batch: b, selected: true, qtyStr: pendingQtyMap.get(b.id) ?? '' };
          }
          return { batch: b, selected: false, qtyStr: '' };
        }),
      );
    },
    [value],
  );

  // 加载 AVAILABLE 批次 (传入 productTypeId 时后端按 BOM 过滤, 无 BOM 回退全部)
  const loadBatches = useCallback(async () => {
    const requestQueryKey = queryKey;
    setLoading(true);
    setLoadError(null);
    try {
      if ((taskId == null) !== (productionBatchId == null)) {
        setLoadError('当前报工任务上下文不完整，请返回任务列表后重试');
        setLoadedQueryKey(requestQueryKey);
        return;
      }
      const res = taskId != null && productionBatchId != null
        ? await yieldReportApi.listTaskMaterialOptions(productionBatchId, taskId, factoryId)
        : await materialBatchApiClient.getBatchesByStatus('AVAILABLE', factoryId, productTypeId);
      if (queryKeyRef.current !== requestQueryKey) return;
      if (res.success && Array.isArray(res.data)) {
        const taskBound = taskId != null && productionBatchId != null;
        const batches: MaterialBatch[] = taskBound
          ? (res.data as TaskMaterialBatchOption[]).map((option) => ({
              id: option.id,
              batchNumber: option.batchNumber,
              factoryId: factoryId ?? '',
              materialTypeId: option.materialTypeId,
              materialName: option.materialName ?? undefined,
              inboundQuantity: option.currentQuantity,
              remainingQuantity: option.currentQuantity,
              reservedQuantity: 0,
              usedQuantity: 0,
              quantityUnit: option.quantityUnit,
              unit: option.quantityUnit,
              unitPrice: null,
              totalCost: null,
              supplierId: '',
              inboundDate: '',
              status: 'available',
              warehouseId: option.warehouseId,
              createdAt: '',
            }))
          : res.data as MaterialBatch[];
        candidateBatchIdsRef.current = new Set(batches.map((batch) => batch.id));
        // Pass current rows snapshot so pending scan selections survive the sync
        syncRowsFromValue(batches, rowsRef.current);
        setLoadedQueryKey(requestQueryKey);
        // F6: 单批次自动选中 — 不让操作工还要多点一次复选框
        // 仅在 value 尚未选中（首次加载）且只有一批次时触发
        if (batches.length === 1 && value.length === 0) {
          const batch = batches[0];
          if (!batch) return;
          setRows([{ batch, selected: true, qtyStr: '' }]);
          if (forceIndependentQuantity) {
            setConfirmed(false);
            setExpanded(true);
          } else {
            // 单批次模式: qty 由父组件的 singleBatchQty / 投入量 驱动; 此处先 emit qty=0 占位,
            // onChange useEffect 在 singleBatchQty 有效时会重新 emit 正确值
            onChangeRef.current([{ materialBatchId: batch.id, quantity: 0, unit }]);
            // 自动收起 + 标记已确认 (操作工无需手动按「确定选择」)
            setConfirmed(true);
            setExpanded(false);
          }
        }
      } else {
        setLoadedQueryKey(requestQueryKey);
        setLoadError(res.message || '无法加载原料批次');
      }
    } catch (err) {
      if (queryKeyRef.current !== requestQueryKey) return;
      setLoadedQueryKey(requestQueryKey);
      const msg = err instanceof Error ? err.message : '加载批次失败';
      setLoadError(msg);
    } finally {
      setLoading(false);
    }
  }, [factoryId, forceIndependentQuantity, productTypeId, productionBatchId, queryKey, syncRowsFromValue, taskId, value.length, unit]);

  // productTypeId comes from the batch detail request. If the operator opens the picker before
  // that request resolves, the first load is unfiltered. Reload once the BOM-filter key changes.
  useEffect(() => {
    autoAllocationRequestRef.current += 1;
    setRows([]);
    candidateBatchIdsRef.current = new Set();
    setConfirmed(false);
    setConfirmFeedback(false);
    setLoadedQueryKey(null);
    setFactoryNumberFilter(null);  // F2: 切换工厂/产品时重置厂号筛选
    setAllocationMode('choice');
    setAllocationTargetQty(null);
    setAllocationTargetEnforced(false);
    setAutoAllocationShortage(0);
    setAutoAllocationError(null);
    setAutoAllocationLoading(false);
  }, [queryKey]);

  // 展开时加载
  useEffect(() => {
    if (expanded && !loading && loadedQueryKey !== queryKey) {
      loadBatches();
    }
  }, [expanded, loading, loadedQueryKey, queryKey, loadBatches]);

  // Q2: 清理定时器
  useEffect(() => {
    return () => {
      if (confirmTimerRef.current) clearTimeout(confirmTimerRef.current);
    };
  }, []);

  const toggleRow = useCallback(
    (idx: number) => {
      // 切换选中时清除 confirmed 状态 (用户在重新选)
      setConfirmed(false);
      setConfirmFeedback(false);
      autoAllocationRequestRef.current += 1;
      setAutoAllocationLoading(false);
      setAutoAllocationError(null);
      setAutoAllocationShortage(0);

      const currentTarget = rows[idx];
      if (currentTarget != null) {
        const nextSelectedCount = selectedRows.length + (currentTarget.selected ? -1 : 1);
        if (nextSelectedCount > 1) {
          const parsedSingleQty = parseFloat(singleBatchQty);
          setAllocationMode('manual');
          if (
            allocationTargetQty == null &&
            !Number.isNaN(parsedSingleQty) &&
            parsedSingleQty > 0
          ) {
            setAllocationTargetQty(parsedSingleQty);
          }
        } else if (
          nextSelectedCount === 1 &&
          allocationMode === 'manual' &&
          allocationTargetQty != null
        ) {
          // 多批手动编辑删回一批时，仍保留逐批上下文和已改数量。
          // 否则会切回父层投入量，覆盖用户刚刚调整的剩余批次。
          setAllocationMode('manual');
        } else {
          setAllocationMode('choice');
          setAllocationTargetQty(null);
          setAllocationTargetEnforced(false);
        }
      }

      setRows((prev: RowState[]) => {
        const target = prev[idx];
        if (target == null) return prev;

        const selecting = !target.selected;
        const selectedBefore = prev.filter((row) => row.selected);
        const parsedSingleQty = parseFloat(singleBatchQty);
        const shouldCarrySingleQty =
          selecting &&
          selectedBefore.length === 1 &&
          !forceIndependentQuantity &&
          !Number.isNaN(parsedSingleQty) &&
          parsedSingleQty > 0;

        return prev.map((row: RowState, i: number) => {
          if (i === idx) {
            return { ...row, selected: selecting, qtyStr: selecting ? '' : row.qtyStr };
          }
          if (shouldCarrySingleQty && row.selected && row.qtyStr === '') {
            // 单批 → 多批时，屏幕“投入量”就是原首批的真实用量。
            // 把原始字符串带入逐批输入，避免切换模式后静默丢失已填数据。
            return { ...row, qtyStr: singleBatchQty };
          }
          return row;
        });
      });
    },
    [allocationMode, allocationTargetQty, forceIndependentQuantity, rows, selectedRows.length, singleBatchQty],
  );

  const switchToManualAllocation = useCallback(() => {
    const preservingAutoAllocation = allocationMode === 'auto';
    autoAllocationRequestRef.current += 1;
    const parsedTarget = parseFloat(singleBatchQty);
    if (allocationTargetQty == null && !Number.isNaN(parsedTarget) && parsedTarget > 0) {
      setAllocationTargetQty(parsedTarget);
    }
    setAllocationMode('manual');
    if (preservingAutoAllocation) setAllocationTargetEnforced(true);
    setAutoAllocationLoading(false);
    setAutoAllocationShortage(0);
    setAutoAllocationError(null);
  }, [allocationMode, allocationTargetQty, singleBatchQty]);

  const handleAutoAllocate = useCallback(async () => {
    const selected = rows.filter((row) => row.selected);
    const seed = selected[0];
    const targetQuantity = parseFloat(singleBatchQty);
    if (
      selected.length !== 1 ||
      seed == null ||
      Number.isNaN(targetQuantity) ||
      targetQuantity <= 0
    ) {
      return;
    }

    const inputUnit = normalizeQuantityUnit(unit);
    const seedUnit = normalizeQuantityUnit(seed.batch.quantityUnit ?? unit);
    if (inputUnit === '' || seedUnit !== inputUnit) {
      setAutoAllocationError(
        `当前批次单位为 ${seed.batch.quantityUnit ?? '未知'}，与投入单位 ${unit} 不一致，不能自动换算，请手动选择批次`,
      );
      return;
    }

    const requestId = autoAllocationRequestRef.current + 1;
    autoAllocationRequestRef.current = requestId;
    setAutoAllocationLoading(true);
    setAutoAllocationError(null);
    setAutoAllocationShortage(0);
    setAllocationTargetQty(targetQuantity);

    try {
      if (autoAllocationRequestRef.current !== requestId) return;
      // Task-bound mode must allocate from the already mounted task candidate set;
      // generic FEFO can cross task/BOM scope and is therefore never called here.
      const candidates = rowsRef.current.map((row) => row.batch).filter(
        (batch) =>
          batch.materialTypeId === seed.batch.materialTypeId &&
          Number.isFinite(Number(batch.remainingQuantity)) &&
          Number(batch.remainingQuantity) > 0,
      );
      const incompatible = candidates.find(
        (batch) => normalizeQuantityUnit(batch.quantityUnit) !== inputUnit,
      );
      if (incompatible != null) {
        setAutoAllocationError(
          `批次 ${incompatible.batchNumber} 的单位为 ${incompatible.quantityUnit ?? '未知'}，与投入单位 ${unit} 不一致，不能自动换算，请手动选择`,
        );
        return;
      }
      if (candidates.length === 0) {
        setAutoAllocationError('没有找到同原料、同单位的可用批次，请手动选择');
        return;
      }

      const result = allocateQuantityAcrossBatches(candidates, targetQuantity);
      const allocationMap = new Map(
        result.allocations.map((allocation) => [allocation.batchId, allocation.quantity]),
      );
      const candidateIds = new Set(candidates.map((batch) => batch.id));

      setRows((prev) => {
        const previousById = new Map(prev.map((row) => [row.batch.id, row]));
        const recommendedRows = candidates.map((batch) => {
          const quantity = allocationMap.get(batch.id);
          const previous = previousById.get(batch.id);
          return {
            batch,
            selected: quantity != null && quantity > 0,
            qtyStr: quantity != null && quantity > 0
              ? formatAllocatedQuantity(quantity)
              : (previous?.qtyStr ?? ''),
          };
        });
        const otherRows = prev
          .filter((row) => !candidateIds.has(row.batch.id))
          .map((row) => ({ ...row, selected: false }));
        return [...recommendedRows, ...otherRows];
      });
      setAllocationMode('auto');
      setAllocationTargetEnforced(true);
      setAutoAllocationShortage(result.shortage);
      setAutoAllocationError(null);
    } catch (error) {
      if (autoAllocationRequestRef.current !== requestId) return;
      const message = error instanceof Error ? error.message : '自动分配失败';
      setAutoAllocationError(`${message}，可改为手动选择批次`);
    } finally {
      if (autoAllocationRequestRef.current === requestId) {
        setAutoAllocationLoading(false);
      }
    }
  }, [rows, singleBatchQty, unit]);

  const setQty = useCallback(
    (idx: number, raw: string) => {
      // 只允许数字 + 小数点 (仅多批次模式使用)
      const cleaned = raw.replace(/[^0-9.]/g, '');
      const parts = cleaned.split('.');
      const normalized =
        parts.length > 2 ? `${parts[0]}.${parts.slice(1).join('')}` : cleaned;
      if (allocationMode === 'auto') {
        setAllocationMode('manual');
        setAutoAllocationShortage(0);
        setAutoAllocationError(null);
      }
      setRows((prev: RowState[]) =>
        prev.map((r: RowState, i: number) =>
          i === idx ? { ...r, qtyStr: normalized, selected: true } : r,
        ),
      );
    },
    [allocationMode],
  );

  const handleLabelScan = useCallback(async (code: string) => {
    setScannerVisible(false);
    const cleanCode = code.trim();
    if (!cleanCode) return;
    setScanLoading(true);
    setLoadError(null);
    try {
      const labelRes = await materialLabelApiClient.scanMaterialBatchLabel(cleanCode, factoryId);
      if (!labelRes.success || !labelRes.data?.batchId) {
        setLoadError(labelRes.message || '未识别到有效物料标签');
        return;
      }
      const label = labelRes.data;
      setScannedLabel(label);

      const taskBound = taskId != null && productionBatchId != null;
      const existingCandidate = rowsRef.current.find((row) => row.batch.id === String(label.batchId))?.batch;
      if (taskBound && !candidateBatchIdsRef.current.has(String(label.batchId))) {
        setLoadError('该标签批次不属于当前任务的可选原料，请重新扫描当前任务物料');
        return;
      }
      const batch = taskBound ? existingCandidate : (await materialBatchApiClient.getBatchById(String(label.batchId), factoryId)).data;
      if (!batch) {
        setLoadError('标签对应的批次不存在');
        return;
      }
      const status = String(batch.status ?? '').toUpperCase();
      if (status && status !== 'AVAILABLE') {
        setLoadError(`标签批次 ${batch.batchNumber} 当前状态为 ${batch.status}, 不能领料`);
        return;
      }

      setExpanded(true);
      setConfirmed(false);
      setConfirmFeedback(false);
      autoAllocationRequestRef.current += 1;
      setAllocationMode('manual');
      setAllocationTargetEnforced(false);
      setAutoAllocationLoading(false);
      setAutoAllocationShortage(0);
      setAutoAllocationError(null);
      setRows((prev) => {
        const existing = prev.find((row) => row.batch.id === batch.id);
        if (existing) {
          return prev.map((row) =>
            row.batch.id === batch.id
              ? { ...row, selected: true, qtyStr: row.qtyStr }
              : row,
          );
        }
        return [{ batch, selected: true, qtyStr: '' }, ...prev];
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : '扫码识别物料标签失败';
      setLoadError(msg);
    } finally {
      setScanLoading(false);
    }
  }, [factoryId, productionBatchId, taskId]);

  // Q2: 「确定选择」按钮点击: 校验 → 折叠 + 显示确认反馈
  const handleConfirm = useCallback(() => {
    if (allocationMode === 'auto' && autoAllocationShortage > QUANTITY_EPSILON) return;
    const selected = rows.filter((r) => r.selected);
    if (selected.length === 0) {
      // 不满足最少选 1 批
      return;
    }
    const isSingle = selected.length === 1;
    if (
      isSingle &&
      !forceIndependentQuantity &&
      allocationMode !== 'auto' &&
      allocationTargetQty == null
    ) {
      const qty = parseFloat(singleBatchQty);
      if (Number.isNaN(qty) || qty <= 0) {
        // 单批次 qty 来自屏幕 投入量, 为空时提示先填投入量
        return;
      }
    } else {
      // 多批次: 每批 qtyStr 都要有效
      const anyInvalid = selected.some((r) => {
        const q = parseFloat(r.qtyStr);
        return Number.isNaN(q) || q <= 0;
      });
      if (anyInvalid) return;
    }
    if (allocationTargetEnforced && allocationTargetQty != null) {
      const total = selected.reduce((sum, row) => sum + (parseFloat(row.qtyStr) || 0), 0);
      if (Math.abs(total - allocationTargetQty) > QUANTITY_EPSILON) return;
    }

    setConfirmed(true);
    setExpanded(false);
    // 短暂反馈 (1.5s 后消失)
    setConfirmFeedback(true);
    if (confirmTimerRef.current) clearTimeout(confirmTimerRef.current);
    confirmTimerRef.current = setTimeout(() => {
      setConfirmFeedback(false);
    }, 1500);
  }, [allocationMode, allocationTargetEnforced, allocationTargetQty, autoAllocationShortage, forceIndependentQuantity, rows, singleBatchQty]);

  // Q2 验证按钮是否可以点击
  const canConfirm = useCallback((): boolean => {
    if (allocationMode === 'auto' && autoAllocationShortage > QUANTITY_EPSILON) return false;
    const selected = rows.filter((r) => r.selected);
    if (selected.length === 0) return false;
    const isSingle = selected.length === 1;
    if (
      isSingle &&
      !forceIndependentQuantity &&
      allocationMode !== 'auto' &&
      allocationTargetQty == null
    ) {
      const first = selected[0];
      const qty = parseFloat(singleBatchQty);
      return first != null && !Number.isNaN(qty) && qty > 0 && !rowOverLimit(first, true);
    }
    const allRowsValid = selected.every((r) => {
      const q = parseFloat(r.qtyStr);
      return !Number.isNaN(q) && q > 0 && !rowOverLimit(r, false);
    });
    if (!allRowsValid) return false;
    if (!allocationTargetEnforced || allocationTargetQty == null) return true;
    const total = selected.reduce((sum, row) => sum + (parseFloat(row.qtyStr) || 0), 0);
    return Math.abs(total - allocationTargetQty) <= QUANTITY_EPSILON;
  }, [allocationMode, allocationTargetEnforced, allocationTargetQty, autoAllocationShortage, forceIndependentQuantity, rows, singleBatchQty, rowOverLimit]);

  /**
   * 「已选几批」的唯一口径 = **组件自己的行选择**。
   *
   * ⛔ 不能用父层的 `value.length` (P6, 2026-08-26 用单测复现):
   * 单批次模式下 refs 只在【用量有效】时才 emit —— 见 :184 那个 effect。
   * 于是「自动选中了、但屏幕上的投入量还没填」这段时间里父层是空的，
   * header 徽标、摘要行、「确定选择」按钮、单批次提示**四样一起消失**，
   * 屏上和「根本没选上」长得一模一样，而唯一还在的东西是那颗红星。
   *
   * 反过来这个口径还更稳: queryKey 变化时 `setRows([])` 与 emit `[]` 是同一轮，
   * 两边不会各说各话。
   */
  const selectedCount = selectedRows.length;
  // 自动结果切手动后仍是“逐批分配”上下文，即使只剩一个自动批次。
  // allocationTargetQty 是该上下文的标记，避免退回单批 UI 而隐藏已分配行。
  const usesIndependentQuantity =
    isMultiBatch ||
    forceIndependentQuantity ||
    allocationMode === 'auto' ||
    (allocationMode === 'manual' && allocationTargetQty != null);

  // 已选各批的用量 (普通单批取屏幕投入量，逐批分配上下文取各自输入)。null = 还没填。
  const selectedQuantities = selectedRows.map((r) => rowQuantity(r, selectedRows.length === 1));
  const hasPendingQty = selectedQuantities.some((q) => q == null);
  const selectedTotal = selectedQuantities.reduce((s: number, q) => s + (q ?? 0), 0);
  const parsedSingleBatchQty = parseFloat(singleBatchQty);
  const singleSelectedRow = selectedCount === 1 ? selectedRows[0] : null;
  const needsAllocationChoice =
    !forceIndependentQuantity &&
    singleSelectedRow != null &&
    !Number.isNaN(parsedSingleBatchQty) &&
    parsedSingleBatchQty > (singleSelectedRow.batch.remainingQuantity ?? 0);
  const showAllocationWindow =
    needsAllocationChoice ||
    allocationMode === 'auto' ||
    (allocationMode === 'manual' && allocationTargetQty != null);
  const selectedQuantityEditor = selectedCount > 0 && usesIndependentQuantity ? (
    <View
      style={styles.selectedQuantitySection}
      testID="material-batch-selected-quantity-section"
    >
      <Text style={styles.selectedQuantityTitle}>
        {allocationMode === 'auto' ? '自动分配结果（逐批核对）' : '已选批次用量（逐批填写）'}
      </Text>
      <Text style={styles.selectedQuantityHelp}>
        {allocationTargetEnforced && allocationTargetQty != null
          ? `目标 ${formatAllocatedQuantity(allocationTargetQty)} ${unit}；当前合计 ${hasPendingQty ? '待填' : `${formatAllocatedQuantity(selectedTotal)} ${unit}`}，合计达到目标后才能确定`
          : allocationMode === 'auto'
          ? '系统已按批次剩余量拆分；每批身份仍分别保留，可直接核对或改为手动'
          : '每批不能超过该批剩余量；全部填写后才能确定'}
      </Text>
      {selectedRowEntries.map(({ row, index }) => {
        const rowUnit = row.batch.quantityUnit ?? unit;
        const overLimit = rowOverLimit(row, false);
        return (
          <View
            key={row.batch.id}
            style={[styles.selectedQuantityRow, overLimit && styles.selectedQuantityRowError]}
            testID={`material-batch-selected-quantity-row-${row.batch.id}`}
          >
            <View style={styles.selectedQuantityIdentity}>
              <Text style={styles.batchNumber}>{row.batch.batchNumber}</Text>
              {row.batch.materialName ? (
                <Text style={styles.materialName}>{row.batch.materialName}</Text>
              ) : null}
              <Text style={styles.remaining}>
                最多可领 {row.batch.remainingQuantity} {rowUnit}
              </Text>
              {overLimit ? (
                <Text style={styles.rowLimitText}>
                  已超出本批可领量，请调小后再确定
                </Text>
              ) : null}
            </View>
            <View style={styles.qtyBox}>
              <TextInput
                style={[
                  styles.qtyInput,
                  overLimit && styles.qtyInputError,
                  disabled && styles.qtyInputDisabled,
                ]}
                keyboardType="decimal-pad"
                value={row.qtyStr}
                onChangeText={(v: string) => setQty(index, v)}
                editable={!disabled}
                placeholder="用量"
                placeholderTextColor="#C0C4CC"
                accessibilityLabel={`批次 ${row.batch.batchNumber} 用量`}
                testID={`material-batch-qty-${row.batch.id}`}
              />
              <Text style={styles.qtyUnit} testID={`material-batch-qty-unit-${row.batch.id}`}>
                {rowUnit}
              </Text>
            </View>
          </View>
        );
      })}
    </View>
  ) : null;

  // F2 厂号筛选: 收集本批次列表里出现的不同厂号 (含 "未标厂号" 哨兵).
  // 同一品名出现 ≥2 个厂号时才展示筛选 chips —— 不同厂号 = 不同批次, 仓管易选错.
  const { keys: factoryNumberKeys, showFilter: showFactoryNumberFilter } =
    deriveFactoryNumberKeys(rows.map((r) => r.batch.factoryNumber));

  // 当前筛选下可见的行索引 (保留原 idx 以驱动 toggleRow/setQty); 未选 chip 时显示全部.
  // 已选中的批次始终可见 (即使被筛选掉) —— 防止操作工"丢失"已选批次看不到.
  const visibleRowIndices: number[] = rows
    .map((_, idx) => idx)
    .filter((idx) => {
      const row = rows[idx];
      if (row == null) return false;
      return isRowVisibleUnderFactoryFilter(
        row.batch.factoryNumber,
        row.selected,
        factoryNumberFilter,
        showFactoryNumberFilter,
      );
    });

  // Q3: 折叠摘要文案 (已确认后显示)
  // ⛔ 用量还没填时不许印 0 —— 「还不知道」和「是 0」对操作工是两件事
  //    (本仓形态 A¹⁰: 兜底的默认值会把「我不知道」翻译成「是 0」)。
  const collapsedSummary = (() => {
    if (selectedCount === 0) return null;
    if (selectedCount === 1) {
      const row = selectedRows[0] as RowState;
      const name = row.batch.materialName ?? row.batch.batchNumber;
      const qty = selectedQuantities[0];
      return qty != null
        ? `已选 ${name} · 用量 ${qty} ${unit}`
        : `已选 ${name} · 用量待填 (在下方填投入量)`;
    }
    if (hasPendingQty) return `已选 ${selectedCount} 批次 · 用量待填`;
    return `已选 ${selectedCount} 批次 · 合计 ${selectedTotal.toFixed(2)} ${unit}`;
  })();

  return (
    <View style={styles.wrap}>
      {/* 折叠 header */}
      <TouchableOpacity
        style={styles.header}
        onPress={() => {
          setExpanded((e: boolean) => !e);
          // 重新展开时清除 confirmed 状态, 让用户重新确认
          if (!expanded) setConfirmed(false);
        }}
        disabled={disabled}
        accessibilityLabel="展开/折叠领料批次选择"
      >
        <Text style={styles.headerTitle}>
          {/* B1: asterisk when required and nothing selected yet; badge when batches chosen */}
          {/* F1 双领: 标明"原料"区, 与屏幕上方"领用半成品库存"卡片配对成 原料/半成品 两区 */}
          {'领料批次 (原料)'}
          {selectedCount > 0 ? (
            <Text style={styles.badge} testID="material-batch-header-badge"> · 已选 {selectedCount} 批</Text>
          ) : required ? (
            <Text style={styles.requiredMark} testID="material-batch-required-mark"> *</Text>
          ) : (
            <Text style={styles.optional}> (可选)</Text>
          )}
        </Text>
        <Text style={styles.chevron}>{expanded ? '▲' : '▼'}</Text>
      </TouchableOpacity>

      {/* Q2 + Q3: 已确认折叠后显示摘要行 + 「修改」按钮 */}
      {!expanded && confirmed && collapsedSummary ? (
        <View style={styles.summaryRow}>
          <Text style={styles.summaryText} numberOfLines={1} testID="material-batch-collapsed-summary">
            {collapsedSummary}
          </Text>
          <TouchableOpacity
            onPress={() => {
              setExpanded(true);
              setConfirmed(false);
            }}
            disabled={disabled}
            style={styles.modifyBtn}
            accessibilityLabel="修改领料批次"
          >
            <Text style={styles.modifyText}>修改</Text>
          </TouchableOpacity>
        </View>
      ) : null}

      {/* Q2: 已确认反馈 (短暂) */}
      {confirmFeedback ? (
        <View style={styles.feedbackRow}>
          <Text style={styles.feedbackText}>已确认 ✓</Text>
        </View>
      ) : null}

      <View style={styles.scanRow}>
        <TouchableOpacity
          style={[styles.scanBtn, (disabled || scanLoading) && styles.scanBtnDisabled]}
          onPress={() => setScannerVisible(true)}
          disabled={disabled || scanLoading}
          accessibilityLabel="扫码选择领料批次"
          testID="material-batch-label-scan-btn"
        >
          <Text style={styles.scanBtnText}>{scanLoading ? '识别中...' : '扫码选批次'}</Text>
        </TouchableOpacity>
        {scannedLabel ? (
          <Text style={styles.scanInfo} numberOfLines={2}>
            {scannedLabel.materialName} / {scannedLabel.batchNumber}
            {scannedLabel.factoryNumber ? ` / 厂号 ${scannedLabel.factoryNumber}` : ''}
            {scannedLabel.originPlace ? ` / ${scannedLabel.originPlace}` : ''}
          </Text>
        ) : (
          <Text style={styles.scanInfo}>扫一物一码标签后自动选中对应批次</Text>
        )}
      </View>

      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleLabelScan}
        title="扫码选择领料批次"
      />

      {expanded ? (
        <View style={styles.body}>
          {loading ? (
            <View style={styles.center}>
              <ActivityIndicator size="small" color="#E8732E" />
              <Text style={styles.loadingText}>加载批次...</Text>
            </View>
          ) : loadError != null ? (
            <View style={styles.center}>
              <Text style={styles.errorText}>{loadError}</Text>
              <TouchableOpacity onPress={loadBatches} style={styles.retryBtn}>
                <Text style={styles.retryText}>重试</Text>
              </TouchableOpacity>
            </View>
          ) : rows.length === 0 ? (
            <Text style={styles.emptyText}>暂无可用原料批次</Text>
          ) : (
            <>
            {/* F2 防呆: 同品多厂号 → 厂号筛选 chips (不同厂号=不同批次, 先选厂号锁定正确批次) */}
            {showFactoryNumberFilter ? (
              <View style={styles.factoryFilterWrap}>
                <Text style={styles.factoryFilterLabel}>按厂号筛选 (不同厂号是不同批次)</Text>
                <ScrollView
                  horizontal
                  showsHorizontalScrollIndicator={false}
                  contentContainerStyle={styles.factoryChips}
                >
                  <TouchableOpacity
                    style={[styles.factoryChip, factoryNumberFilter == null && styles.factoryChipActive]}
                    onPress={() => setFactoryNumberFilter(null)}
                    disabled={disabled}
                    accessibilityLabel="显示全部厂号"
                    testID="factory-filter-all"
                  >
                    <Text style={[styles.factoryChipText, factoryNumberFilter == null && styles.factoryChipTextActive]}>
                      全部
                    </Text>
                  </TouchableOpacity>
                  {factoryNumberKeys.map((key) => {
                    const active = factoryNumberFilter === key;
                    const display = key === NO_FACTORY_NUMBER ? '未标厂号' : key;
                    return (
                      <TouchableOpacity
                        key={key}
                        style={[styles.factoryChip, active && styles.factoryChipActive]}
                        onPress={() => setFactoryNumberFilter(active ? null : key)}
                        disabled={disabled}
                        accessibilityLabel={`筛选厂号 ${display}`}
                        accessibilityState={{ selected: active }}
                        testID={`factory-filter-${key}`}
                      >
                        <Text style={[styles.factoryChipText, active && styles.factoryChipTextActive]}>
                          {display}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </ScrollView>
              </View>
            ) : null}

            {/* 固定高度区域只负责选批次。逐批数量输入放在此 ScrollView 外，避免 Android
              * 同向嵌套滚动 + 选中行动态增高后把首个 TextInput 裁到 viewport 之外。 */}
            <ScrollView
              style={styles.listScroll}
              nestedScrollEnabled
              keyboardShouldPersistTaps="handled"
              testID="material-batch-list-scroll"
            >
              {visibleRowIndices.length === 0 ? (
                <Text style={styles.emptyText}>该厂号下暂无可领批次</Text>
              ) : null}
              {visibleRowIndices.map((idx: number) => {
                const row = rows[idx];
                if (row == null) return null;
                // B: 这一行显示用【这个批次自己的单位】, 不是工序单位 —— prod 实测三个批次
                // (成品盒/封膜卷/外箱片) 统统被显示成工序单位 "kg", 仓管按假单位领错料。
                // ⚠️ 这里故意用 quantityUnit 不是 unit —— 查过 MaterialBatchMapper.java:77
                // 之后发现 `unit` 是【原料类型】的默认单位 (dto.setUnit(materialType.getUnit())),
                // 跟这一批 remainingQuantity 实际记账的单位是两回事; remainingQuantity 对应
                // 的是 batch.quantityUnit (DB 列 NOT NULL, 批次自己的字段) —— 差点把"工序单位
                // 的假"换成"物料类型默认单位的假", 同一类缺陷换了个马甲。批次没有
                // quantityUnit(理论上不该发生, DB 约束非空) 才退回工序单位, 诚实兜底不瞎猜.
                const rowUnit = row.batch.quantityUnit ?? unit;
                return (
                <TouchableOpacity
                  key={row.batch.id}
                  style={[styles.row, row.selected && styles.rowSelected]}
                  onPress={() => !disabled && toggleRow(idx)}
                  activeOpacity={0.7}
                  accessibilityLabel={`${row.selected ? '取消选择' : '选择'}批次 ${row.batch.batchNumber}`}
                  accessibilityRole="checkbox"
                  accessibilityState={{ checked: row.selected, disabled }}
                >
                  {/* 复选框 — 纯视觉, 触摸由外层 TouchableOpacity 行处理 */}
                  <View
                    style={[styles.checkbox, row.selected && styles.checkboxChecked]}
                    pointerEvents="none"
                  >
                    {row.selected ? <Text style={styles.checkmark}>✓</Text> : null}
                  </View>

                  {/* 批次信息 */}
                  <View style={styles.batchInfo}>
                    <Text style={styles.batchNumber}>{row.batch.batchNumber}</Text>
                    {row.batch.materialName ? (
                      <Text style={styles.materialName}>{row.batch.materialName}</Text>
                    ) : null}
                    {row.batch.factoryNumber ? (
                      <Text style={[styles.materialName, { color: '#888', fontSize: 11 }]}>厂号: {row.batch.factoryNumber}</Text>
                    ) : null}
                    <Text style={styles.remaining} testID={`material-batch-remaining-${row.batch.id}`}>
                      剩余 {row.batch.remainingQuantity} {rowUnit}
                    </Text>
                  </View>

                  {/*
                   * Q1 单一数据源:
                   * 单批次已选 → 不显示 per-batch 用量输入，qty 由屏幕「投入量」驱动
                   * 多批次已选 → 输入位于固定高度列表下方的独立编辑区
                   */}
                  {row.selected && usesIndependentQuantity ? (
                    <View style={styles.multiQtyLocationHint}>
                      <Text style={styles.multiQtyLocationHintText}>下方填用量</Text>
                    </View>
                  ) : row.selected ? (
                    // 单批次: 显示提示, 投入量由屏幕控制
                    <View style={styles.singleQtyHint}>
                      <Text style={styles.singleQtyHintText}>用量 = 投入量</Text>
                    </View>
                  ) : null}
                </TouchableOpacity>
                );
              })}
            </ScrollView>
            </>
          )}

          {showAllocationWindow ? (
            <View style={styles.allocationWindow} testID="material-batch-allocation-window">
              <View style={styles.allocationWindowHeader}>
                <Text style={styles.allocationWindowTitle}>多批次用量分配</Text>
                {allocationMode === 'auto' ? (
                  <Text style={styles.allocationModeBadge}>自动</Text>
                ) : allocationMode === 'manual' ? (
                  <Text style={[styles.allocationModeBadge, styles.allocationModeBadgeManual]}>手动</Text>
                ) : null}
              </View>

              {allocationMode === 'choice' && needsAllocationChoice ? (
                <>
                  <Text style={styles.allocationWindowText}>
                    本次投入 {formatAllocatedQuantity(parsedSingleBatchQty)} {unit}，当前批次最多可领{' '}
                    {singleSelectedRow?.batch.remainingQuantity ?? 0} {unit}，需要从多个批次领料。
                  </Text>
                  <Text style={styles.allocationWindowHint}>请选择分配方式；推荐先使用系统自动分配。</Text>

                  <TouchableOpacity
                    style={[styles.allocationOption, styles.allocationOptionRecommended]}
                    onPress={handleAutoAllocate}
                    disabled={disabled || autoAllocationLoading}
                    accessibilityRole="button"
                    accessibilityLabel="按效期优先自动分配用量"
                    testID="material-batch-allocation-option-auto"
                  >
                    <View style={styles.allocationOptionCopy}>
                      <View style={styles.allocationOptionTitleRow}>
                        <Text style={styles.allocationOptionTitle}>按效期优先自动分配</Text>
                        <Text style={styles.recommendedBadge}>推荐</Text>
                      </View>
                      <Text style={styles.allocationOptionDescription}>
                        先用临近效期批次，再按入库先后补足；提交时仍逐批校验
                      </Text>
                    </View>
                    {autoAllocationLoading ? (
                      <ActivityIndicator size="small" color="#E8732E" />
                    ) : (
                      <Text style={styles.allocationOptionArrow}>›</Text>
                    )}
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={styles.allocationOption}
                    onPress={switchToManualAllocation}
                    disabled={disabled || autoAllocationLoading}
                    accessibilityRole="button"
                    accessibilityLabel="手动选择批次并逐批填写"
                    testID="material-batch-allocation-option-manual"
                  >
                    <View style={styles.allocationOptionCopy}>
                      <Text style={styles.allocationOptionTitle}>手动选择批次</Text>
                      <Text style={styles.allocationOptionDescription}>
                        自己勾选本次共同投入的批次，再在同一窗口逐批填写用量
                      </Text>
                    </View>
                    <Text style={styles.allocationOptionArrow}>›</Text>
                  </TouchableOpacity>
                </>
              ) : allocationMode === 'auto' ? (
                <>
                  <Text style={styles.allocationWindowText}>
                    目标 {formatAllocatedQuantity(allocationTargetQty ?? parsedSingleBatchQty)} {unit} · 已分配{' '}
                    {selectedCount} 个批次 · 合计 {formatAllocatedQuantity(selectedTotal)} {unit}
                  </Text>
                  {autoAllocationShortage > QUANTITY_EPSILON ? (
                    <Text style={styles.allocationError} testID="material-batch-auto-shortage">
                      当前可用批次不足，还缺 {formatAllocatedQuantity(autoAllocationShortage)} {unit}；不能确定，请改为手动选择或联系仓管。
                    </Text>
                  ) : (
                    <Text style={styles.allocationSuccess} testID="material-batch-auto-success">
                      已按效期顺序分配完成。批次身份仍分别保留，可在下方核对。
                    </Text>
                  )}
                  <TouchableOpacity
                    style={styles.allocationOption}
                    onPress={switchToManualAllocation}
                    disabled={disabled}
                    accessibilityRole="button"
                    accessibilityLabel="改为手动选择和调整批次"
                    testID="material-batch-allocation-switch-manual"
                  >
                    <View style={styles.allocationOptionCopy}>
                      <Text style={styles.allocationOptionTitle}>改为手动选择或调整</Text>
                      <Text style={styles.allocationOptionDescription}>保留当前结果，再自行增删批次或修改逐批用量</Text>
                    </View>
                    <Text style={styles.allocationOptionArrow}>›</Text>
                  </TouchableOpacity>
                </>
              ) : (
                <>
                  <Text style={styles.allocationWindowText}>
                    手动模式：继续在上方勾选批次，并在下方逐批填写；逐批合计将作为本次投入量。
                  </Text>
                  {needsAllocationChoice ? (
                    <TouchableOpacity
                      style={[styles.allocationOption, styles.allocationOptionRecommended]}
                      onPress={handleAutoAllocate}
                      disabled={disabled || autoAllocationLoading}
                      accessibilityRole="button"
                      accessibilityLabel="改用按效期优先自动分配"
                      testID="material-batch-allocation-switch-auto"
                    >
                      <View style={styles.allocationOptionCopy}>
                        <Text style={styles.allocationOptionTitle}>改用自动分配</Text>
                        <Text style={styles.allocationOptionDescription}>系统按效期和剩余量拆分本次投入</Text>
                      </View>
                      {autoAllocationLoading ? (
                        <ActivityIndicator size="small" color="#E8732E" />
                      ) : (
                        <Text style={styles.allocationOptionArrow}>›</Text>
                      )}
                    </TouchableOpacity>
                  ) : null}
                </>
              )}

              {autoAllocationError != null ? (
                <Text style={styles.allocationError} testID="material-batch-auto-error">
                  {autoAllocationError}
                </Text>
              ) : null}
              <Text style={styles.allocationFootnote}>按当前可用量生成，正式提交时系统会再次校验库存。</Text>
              {selectedQuantityEditor}
            </View>
          ) : null}

          {/*
           * 多批次数量编辑区刻意放在 listScroll 外：它随 YieldStepReportScreen 的外层
           * ScrollView 参与页面布局，因此 2–5 个输入都能滚到，不会被 320dp 选择列表裁剪。
           */}
          {!showAllocationWindow ? selectedQuantityEditor : null}

          {/* 多批次合计 */}
          {selectedCount > 0 &&
          (isMultiBatch ||
            allocationMode === 'auto' ||
            (allocationMode === 'manual' && allocationTargetQty != null)) ? (
            <View style={styles.selectedHint}>
              <Text style={styles.selectedHintText}>
                已选 {selectedCount} 批次 · 合计领料{' '}
                {hasPendingQty ? '用量待填' : `${selectedTotal.toFixed(2)} ${unit}`}
              </Text>
            </View>
          ) : null}

          {/* Q1: 单批次时提示投入量即用量 */}
          {selectedCount === 1 && !usesIndependentQuantity ? (
            <View style={styles.singleBatchNote}>
              <Text style={styles.singleBatchNoteText}>
                单批次: 投入量即该批次的领用量，请在下方「投入量」填写
              </Text>
            </View>
          ) : null}

          {/* Q2: 「确定选择」按钮 */}
          {selectedCount > 0 ? (
            <TouchableOpacity
              style={[styles.confirmBtn, !canConfirm() && styles.confirmBtnDisabled]}
              onPress={handleConfirm}
              disabled={disabled || !canConfirm()}
              accessibilityLabel="确定选择领料批次"
              testID="material-batch-confirm"
            >
              <Text style={[styles.confirmBtnText, !canConfirm() && styles.confirmBtnTextDisabled]}>
                {allocationMode === 'auto' && autoAllocationShortage > QUANTITY_EPSILON
                  ? '自动分配仍有缺口，请改为手动'
                  : allocationTargetEnforced && allocationTargetQty != null && !canConfirm() && !hasPendingQty
                    ? `逐批合计需达到 ${formatAllocatedQuantity(allocationTargetQty)} ${unit}`
                  : usesIndependentQuantity
                    ? (canConfirm()
                        ? '确定选择'
                        : forceIndependentQuantity
                          ? '请填写批次用量'
                          : '请为每批次填写用量')
                    : (canConfirm() ? '确定选择' : '请先在下方填写投入量')}
              </Text>
            </TouchableOpacity>
          ) : null}
        </View>
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginTop: 8, marginBottom: 16, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8 },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 14, paddingVertical: 12,
  },
  headerTitle: { fontSize: 15, fontWeight: '600', color: '#303133' },
  badge: { color: '#E8732E', fontWeight: '700' },
  optional: { color: '#909399', fontWeight: '400' },
  requiredMark: { color: '#F56C6C', fontWeight: '700' },
  chevron: { fontSize: 12, color: '#909399' },

  // Q3: 折叠后摘要行
  summaryRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 14, paddingVertical: 8,
    borderTopWidth: 1, borderTopColor: '#EBEEF5',
    backgroundColor: '#FFF7F0',
  },
  summaryText: { flex: 1, fontSize: 13, color: '#E8732E', fontWeight: '600' },
  modifyBtn: {
    paddingHorizontal: 12, paddingVertical: 4,
    backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#E8732E', borderRadius: 12,
    marginLeft: 8,
  },
  modifyText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },

  // Q2: 已确认反馈 (短暂)
  feedbackRow: {
    paddingHorizontal: 14, paddingVertical: 6,
    borderTopWidth: 1, borderTopColor: '#EBEEF5',
    backgroundColor: '#F0F9EB',
  },
  feedbackText: { fontSize: 13, color: '#67C23A', fontWeight: '600', textAlign: 'center' },

  scanRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#EBEEF5',
    backgroundColor: '#FAFAFA',
  },
  scanBtn: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    backgroundColor: '#ECF5FF',
    borderColor: '#409EFF',
    borderWidth: 1,
    borderRadius: 8,
  },
  scanBtnDisabled: { opacity: 0.55 },
  scanBtnText: { fontSize: 13, color: '#1D4ED8', fontWeight: '700' },
  scanInfo: { flex: 1, fontSize: 12, color: '#606266', lineHeight: 17 },

  body: { borderTopWidth: 1, borderTopColor: '#EBEEF5', paddingHorizontal: 12, paddingTop: 10, paddingBottom: 6 },

  // F2: 厂号筛选 chips
  factoryFilterWrap: { marginBottom: 8 },
  factoryFilterLabel: { fontSize: 12, color: '#909399', marginBottom: 6 },
  factoryChips: { flexDirection: 'row', alignItems: 'center', paddingRight: 8, gap: 8 },
  factoryChip: {
    minHeight: 36, paddingHorizontal: 14, paddingVertical: 7,
    borderRadius: 18, borderWidth: 1, borderColor: '#DCDFE6',
    backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginRight: 8,
  },
  factoryChipActive: { borderColor: '#E8732E', backgroundColor: '#FFF1E8' },
  factoryChipText: { fontSize: 14, color: '#606266', fontWeight: '600' },
  factoryChipTextActive: { color: '#E8732E', fontWeight: '700' },

  center: { alignItems: 'center', paddingVertical: 16 },
  loadingText: { marginTop: 8, fontSize: 13, color: '#909399' },
  errorText: { fontSize: 13, color: '#F56C6C', textAlign: 'center' },
  retryBtn: { marginTop: 8, paddingHorizontal: 16, paddingVertical: 6, backgroundColor: '#FFEFD5', borderRadius: 6 },
  retryText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },
  emptyText: { fontSize: 13, color: '#909399', textAlign: 'center', paddingVertical: 12 },
  listScroll: { maxHeight: 320 },
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 12, paddingHorizontal: 4,
    minHeight: 48, // BUG-3: 整行触摸目标 ≥ 44pt (WCAG 2.5.5 / Apple HIG)
    borderBottomWidth: 1, borderBottomColor: '#F2F6FC',
  },
  rowSelected: { backgroundColor: '#FFF7F0' },
  checkbox: {
    width: 22, height: 22, borderRadius: 4, borderWidth: 1.5, borderColor: '#DCDFE6',
    backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginRight: 10,
  },
  checkboxChecked: { borderColor: '#E8732E', backgroundColor: '#E8732E' },
  checkmark: { color: '#FFFFFF', fontSize: 13, fontWeight: '700' },
  batchInfo: { flex: 1, marginRight: 8 },
  batchNumber: { fontSize: 14, fontWeight: '600', color: '#303133' },
  materialName: { fontSize: 12, color: '#606266', marginTop: 1 },
  remaining: { fontSize: 12, color: '#909399', marginTop: 2 },
  qtyBox: { flexDirection: 'row', alignItems: 'center' },
  qtyInput: {
    // F7 防呆: 数字 ≥24px 大字号 — 仓管员年纪偏大, 怕看错数字
    width: 92, height: 48, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 6,
    paddingHorizontal: 8, fontSize: 24, fontWeight: '700', color: '#1A1A1A',
    backgroundColor: '#FFFFFF', textAlign: 'center',
  },
  qtyInputDisabled: { opacity: 0.5 },
  qtyInputError: { borderColor: '#F56C6C', backgroundColor: '#FFF5F5' },
  qtyUnit: { fontSize: 13, color: '#909399', marginLeft: 4 },
  rowLimitText: { fontSize: 12, color: '#F56C6C', marginTop: 2 },

  multiQtyLocationHint: {
    paddingHorizontal: 8, paddingVertical: 4,
    backgroundColor: '#FFF3EC', borderRadius: 6,
  },
  multiQtyLocationHintText: { fontSize: 12, color: '#E8732E', fontWeight: '600' },
  allocationWindow: {
    marginTop: 12, padding: 12,
    borderWidth: 1.5, borderColor: '#F3C6A9', borderRadius: 12,
    backgroundColor: '#FFFBF7',
  },
  allocationWindowHeader: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginBottom: 4,
  },
  allocationWindowTitle: { fontSize: 17, color: '#303133', fontWeight: '700' },
  allocationModeBadge: {
    paddingHorizontal: 9, paddingVertical: 3, borderRadius: 999,
    backgroundColor: '#E8732E', color: '#FFFFFF', fontSize: 12, fontWeight: '700',
  },
  allocationModeBadgeManual: { backgroundColor: '#6B7280' },
  allocationWindowText: { fontSize: 14, color: '#303133', lineHeight: 21, marginTop: 4 },
  allocationWindowHint: { fontSize: 12, color: '#606266', lineHeight: 18, marginTop: 4, marginBottom: 8 },
  allocationOption: {
    minHeight: 64, marginTop: 8, paddingHorizontal: 12, paddingVertical: 10,
    flexDirection: 'row', alignItems: 'center',
    borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 10,
    backgroundColor: '#FFFFFF',
  },
  allocationOptionRecommended: { borderColor: '#E8732E', backgroundColor: '#FFF5ED' },
  allocationOptionCopy: { flex: 1, marginRight: 8 },
  allocationOptionTitleRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap' },
  allocationOptionTitle: { fontSize: 15, color: '#303133', fontWeight: '700' },
  recommendedBadge: {
    marginLeft: 8, paddingHorizontal: 7, paddingVertical: 2,
    borderRadius: 999, backgroundColor: '#E8732E', color: '#FFFFFF',
    fontSize: 11, fontWeight: '700', overflow: 'hidden',
  },
  allocationOptionDescription: { fontSize: 12, color: '#606266', lineHeight: 18, marginTop: 3 },
  allocationOptionArrow: { fontSize: 28, lineHeight: 30, color: '#E8732E', fontWeight: '500' },
  allocationSuccess: {
    marginTop: 8, paddingHorizontal: 10, paddingVertical: 8,
    borderRadius: 8, backgroundColor: '#F0F9EB', color: '#3B7A1D',
    fontSize: 13, lineHeight: 19, fontWeight: '600',
  },
  allocationError: {
    marginTop: 8, paddingHorizontal: 10, paddingVertical: 8,
    borderRadius: 8, backgroundColor: '#FFF1F0', color: '#C0392B',
    fontSize: 13, lineHeight: 19, fontWeight: '600',
  },
  allocationFootnote: { fontSize: 11, color: '#909399', lineHeight: 16, marginTop: 8 },
  selectedQuantitySection: {
    marginTop: 12, padding: 10,
    borderWidth: 1, borderColor: '#F3C6A9', borderRadius: 8,
    backgroundColor: '#FFFBF7',
  },
  selectedQuantityTitle: { fontSize: 15, color: '#303133', fontWeight: '700' },
  selectedQuantityHelp: { fontSize: 12, color: '#606266', marginTop: 3, marginBottom: 8 },
  selectedQuantityRow: {
    flexDirection: 'row', alignItems: 'center',
    minHeight: 72, paddingVertical: 8,
    borderTopWidth: 1, borderTopColor: '#F2E6DE',
  },
  selectedQuantityRowError: { backgroundColor: '#FFF5F5' },
  selectedQuantityIdentity: { flex: 1, marginRight: 8 },

  // Q1: 单批次时 per-row 提示
  singleQtyHint: {
    paddingHorizontal: 8, paddingVertical: 4,
    backgroundColor: '#F0F9EB', borderRadius: 6,
  },
  singleQtyHintText: { fontSize: 12, color: '#67C23A', fontWeight: '500' },

  selectedHint: {
    backgroundColor: '#F0F9EB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 8,
  },
  selectedHintText: { fontSize: 13, color: '#67C23A', fontWeight: '500' },

  // Q1: 单批次提示
  singleBatchNote: {
    backgroundColor: '#ECF5FF', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 8,
  },
  singleBatchNoteText: { fontSize: 12, color: '#409EFF' },

  // Q2: 确定选择按钮
  confirmBtn: {
    marginTop: 12, height: 44, borderRadius: 8,
    backgroundColor: '#E8732E',
    alignItems: 'center', justifyContent: 'center',
  },
  confirmBtnDisabled: { backgroundColor: '#F5F7FA', borderWidth: 1, borderColor: '#DCDFE6' },
  confirmBtnText: { fontSize: 15, fontWeight: '700', color: '#FFFFFF' },
  confirmBtnTextDisabled: { color: '#C0C4CC' },
});

export default MaterialBatchPicker;
