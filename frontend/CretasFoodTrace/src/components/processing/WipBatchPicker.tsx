/**
 * WipBatchPicker — 上道多笔半成品 (WIP) 领用批次选择器 (G7 部分领用 · F006 #5)
 *
 * 用途: 当上道工序产出了 >1 笔 AVAILABLE 半成品 (例如跨两天分两批产出 → 2 行 WIP),
 *       后端无法自动判定本道该领哪一笔 (limits.sourceWipNo = null, 但 wipAvailable > 0)。
 *       此时让操作工显式单选要领用的半成品批次, 选中后回传 intermediateBatchNo (= sourceWipNo)
 *       与该批次单位, 供报工单提交时扣减对应 WIP 余额。
 *
 * 调用方: YieldStepReportScreen (非首道 && limits.sourceWipNo == null && wipAvailable > 0)。
 *
 * 设计 (防呆, 低文化操作工友好):
 *  - 单选 (radio), 不是 MaterialBatchPicker 的多选 —— 一道只领一笔来源 WIP。
 *  - 默认展开 (非折叠), 因为这一步是必选的, 不让操作工漏看。
 *  - 每行: 工序批次号 · 工序名 · 可领 X单位; 选中行高亮。
 *  - 未选时父组件 disable 提交 + 显示"请选择要领用的半成品批次"。
 *  - 质检放行 (缺口 11): 按数量 —— 一行里只有一部分待检时, 「可领」写的是放行了的那部分, 并写明另有多少待检;
 *    整行都待检 / 不合格的批次照样列出 (藏起来操作员会以为系统坏了),
 *    但灰显、不可点, 行内写明「待检，质检员放行后才能领用」; 全部待检时提示去找质检员,
 *    且【不】触发 onEmpty —— 那会放开「无半成品可领」的主管兜底, 把待检料绕过去。
 *
 * UX Flow Analysis (ux-flow 门控, 2026-10-26):
 *  用户: 产线操作员, 年纪偏大、只认「能不能领 / 为什么不能」。
 *  旅程: 打开本道报工 → 看到上道半成品清单 → 待检行灰显带原因 → 选可领的一行 → 填量提交。
 *  摩擦点: F1 全部待检 = 死胡同 (Rule 5, HIGH) → 写明「请通知质检员放行」+ 重新加载;
 *          F2 不知道为什么点不动 (Rule 1, HIGH) → 行内原因文字, 不靠颜色单独表达;
 *          F3 误触 (内联规则) → 待检行 disabled, 行高沿用 ≥44pt。
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ActivityIndicator,
  StyleSheet,
  ScrollView,
} from 'react-native';
import { yieldReportApi, WipRowDTO } from '../../services/api/yieldReportApi';

type WipQcFields = Partial<Pick<WipRowDTO, 'inspectionStatus' | 'availableQuantity' | 'inspectionPendingQuantity'>>;

/**
 * 这一行里待检的数量 (缺口 11 审查高 2: 一行半成品跨批次累加, 待检的只是其中一部分)。
 * 不在待检 → 0; 待检但后端没给数量 (旧数据) → 整个余额都算待检 (宁可多挡)。
 */
export function wipPendingQuantity(row: WipQcFields): number {
  if (row.inspectionStatus !== 'PENDING') return 0;
  const balance = Math.max(0, row.availableQuantity ?? 0);
  const pending = row.inspectionPendingQuantity ?? balance;
  return Math.min(balance, Math.max(0, pending));
}

/** 现在能领的量 = 余额 − 待检数量 (与后端 SemiFinishedInventory#drawableBalance 同一口径)。 */
export function wipFreeQuantity(row: WipQcFields): number {
  return Math.max(0, (row.availableQuantity ?? 0) - wipPendingQuantity(row));
}

/** 质检放行 (缺口 11): 这一行能不能领, 不能领的原因 (null = 可领, 可领多少看 wipFreeQuantity)。 */
export function wipQcBlockReason(row: WipQcFields): string | null {
  if (row.inspectionStatus === 'REJECTED') return '质检不合格，不能领用';
  if (row.inspectionStatus === 'PENDING' && wipFreeQuantity(row) <= 0) return '待检，质检员放行后才能领用';
  return null;
}

export interface WipSelection {
  /** 选中的工序批次号 = 报工 sourceWipNo */
  sourceWipNo: string;
  /** 该批次余额单位 (= 上道 outputUnit, 可能 ≠ 本道 unit), 供投入 :max 用 */
  unit: string | null;
  /** 该批次可领余额 (= availableQuantity), 供投入 :max 用 */
  availableQuantity: number | null;
}

interface WipBatchPickerProps {
  /** 当前批次 ID (传给 listWip) */
  batchId: number;
  /** 当前工厂 ID (可选, 不传则 yieldReportApi 自动解析) */
  factoryId?: string;
  /** 当前选中的 sourceWipNo (受控; null = 未选) */
  selectedSourceWipNo: string | null;
  /** 选中变更回调 (传 null 表示清空选择) */
  onChange: (sel: WipSelection | null) => void;
  /** 是否禁用 (提交进行中) */
  disabled?: boolean;
  /** 是否必须选择 WIP; false 时再次点击已选行会清空选择 */
  required?: boolean;
  /**
   * 加载完成且确认无可领半成品时触发 (防呆 Rule 5: dead-end → 导航/出路)。
   * 父组件可据此解除 submitBlockedNoWip，让操作员能走主管流程而不是死锁在界面上。
   */
  onEmpty?: () => void;
}

export const WipBatchPicker: React.FC<WipBatchPickerProps> = ({
  batchId,
  factoryId,
  selectedSourceWipNo,
  onChange,
  disabled = false,
  required = true,
  onEmpty,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [rows, setRows] = useState<WipRowDTO[]>([]);

  // 加载该批次 WIP, 过滤到 AVAILABLE && 余额>0 (服务端可能已过滤, 这里再保险一次)
  const loadWip = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await yieldReportApi.listWip(batchId, factoryId);
      if (res.success && Array.isArray(res.data)) {
        const available = res.data.filter(
          (w: WipRowDTO) =>
            w.status === 'AVAILABLE' && (w.availableQuantity ?? 0) > 0,
        );
        setRows(available);
        // 防呆 Rule 5: 确认为空时通知父组件，避免死胡同 (submitBlockedNoWip 无法解除)
        // ⚠️ 只有待检行时【不算空】: 料在, 只是等放行 —— 放开主管兜底会把待检料绕过去。
        if (available.length === 0) {
          onEmpty?.();
        }
      } else {
        setLoadError(res.message || '无法加载半成品批次');
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : '加载半成品批次失败';
      setLoadError(msg);
    } finally {
      setLoading(false);
    }
  }, [batchId, factoryId, onEmpty]);

  useEffect(() => {
    loadWip();
  }, [loadWip]);

  const selectableCount = rows.filter((r) => wipQcBlockReason(r) == null).length;

  const selectRow = useCallback(
    (row: WipRowDTO) => {
      if (disabled || wipQcBlockReason(row) != null) return;
      if (!required && row.intermediateBatchNo === selectedSourceWipNo) {
        onChange(null);
        return;
      }
      // required=true 时再次点击不取消; optional 模式下上方已处理清空。
      onChange({
        sourceWipNo: row.intermediateBatchNo,
        unit: row.unit,
        // 待检的那部分不能领 —— 投入上限按「现在能领的量」
        availableQuantity: wipFreeQuantity(row),
      });
    },
    [disabled, onChange, required, selectedSourceWipNo],
  );

  return (
    <View style={styles.wrap}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>
          上道半成品有 {rows.length} 笔, {required ? '请选择本道领用哪一笔' : '可选择本道要领用的半成品'}
        </Text>
      </View>

      <View style={styles.body}>
        {loading ? (
          <View style={styles.center}>
            <ActivityIndicator size="small" color="#E8732E" />
            <Text style={styles.loadingText}>加载半成品批次...</Text>
          </View>
        ) : loadError != null ? (
          <View style={styles.center}>
            <Text style={styles.errorText}>{loadError}</Text>
            <TouchableOpacity onPress={loadWip} style={styles.retryBtn}>
              <Text style={styles.retryText}>重试</Text>
            </TouchableOpacity>
          </View>
        ) : rows.length === 0 ? (
          /* 防呆 Rule 5: 空状态给出路，不让操作员死锁 */
          <View style={styles.center}>
            <Text style={styles.emptyText}>暂无可领半成品批次</Text>
            <Text style={styles.emptyGuide}>如确认无半成品可领，请联系主管确认是否可跳过本道</Text>
            <TouchableOpacity onPress={loadWip} style={styles.retryBtn}>
              <Text style={styles.retryText}>重新加载</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <ScrollView style={styles.listScroll} nestedScrollEnabled>
            {rows.map((row: WipRowDTO) => {
              const selected = row.intermediateBatchNo === selectedSourceWipNo;
              const blockReason = wipQcBlockReason(row);
              return (
                <TouchableOpacity
                  key={row.intermediateBatchNo}
                  style={[styles.row, selected && styles.rowSelected, blockReason != null && styles.rowBlocked]}
                  onPress={() => selectRow(row)}
                  disabled={disabled || blockReason != null}
                  accessibilityLabel={blockReason != null
                    ? `半成品批次 ${row.intermediateBatchNo}，${blockReason}`
                    : `选择半成品批次 ${row.intermediateBatchNo}`}
                  accessibilityState={{ disabled: disabled || blockReason != null, selected }}
                  testID={`wip-row-${row.intermediateBatchNo}`}
                >
                  {/* 单选圆点 (radio) */}
                  <View style={[styles.radio, selected && styles.radioChecked]}>
                    {selected ? <View style={styles.radioDot} /> : null}
                  </View>

                  {/* 批次信息 */}
                  <View style={styles.wipInfo}>
                    <Text style={styles.wipNo}>{row.intermediateBatchNo}</Text>
                    <Text style={styles.wipProcess}>
                      {row.processName ?? `第 ${row.processOrder ?? '?'} 道`}
                    </Text>
                    <Text style={styles.wipAvail}>
                      可领 {wipFreeQuantity(row)} {row.unit ?? ''}
                    </Text>
                    {wipPendingQuantity(row) > 0 && blockReason == null ? (
                      <Text style={styles.wipBlocked} testID={`wip-row-pending-${row.intermediateBatchNo}`}>
                        另有 {wipPendingQuantity(row)} {row.unit ?? ''} 待检，质检员放行后才能领
                      </Text>
                    ) : null}
                    {blockReason != null ? (
                      <Text style={styles.wipBlocked} testID={`wip-row-blocked-${row.intermediateBatchNo}`}>
                        {blockReason}
                      </Text>
                    ) : null}
                  </View>
                </TouchableOpacity>
              );
            })}
          </ScrollView>
        )}

        {/* 未选提示 (防呆 Rule 1: 事前明确告知必须选) */}
        {!loading && loadError == null && rows.length > 0 && selectableCount === 0 ? (
          <View style={styles.hint} testID="wip-all-pending-hint">
            <Text style={styles.hintText}>上道半成品都在等质检放行，现在不能领用</Text>
            <Text style={styles.emptyGuide}>请通知质检员放行，放行后点「重新加载」</Text>
            <TouchableOpacity onPress={loadWip} style={styles.retryBtn}>
              <Text style={styles.retryText}>重新加载</Text>
            </TouchableOpacity>
          </View>
        ) : !loading && loadError == null && rows.length > 0 && required && selectedSourceWipNo == null ? (
          <View style={styles.hint}>
            <Text style={styles.hintText}>请选择要领用的半成品批次</Text>
          </View>
        ) : null}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginTop: 8, marginBottom: 16, borderWidth: 1, borderColor: '#A0CFFF', borderRadius: 8, backgroundColor: '#F4F9FF' },
  header: { paddingHorizontal: 14, paddingVertical: 12 },
  headerTitle: { fontSize: 15, fontWeight: '700', color: '#409EFF' },
  body: { borderTopWidth: 1, borderTopColor: '#D9ECFF', paddingHorizontal: 12, paddingTop: 10, paddingBottom: 6 },
  center: { alignItems: 'center', paddingVertical: 16 },
  loadingText: { marginTop: 8, fontSize: 13, color: '#909399' },
  errorText: { fontSize: 13, color: '#F56C6C', textAlign: 'center' },
  retryBtn: { marginTop: 8, paddingHorizontal: 16, paddingVertical: 6, backgroundColor: '#FFEFD5', borderRadius: 6 },
  retryText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },
  emptyText: { fontSize: 13, color: '#909399', textAlign: 'center', paddingVertical: 4 },
  emptyGuide: { fontSize: 12, color: '#E6A23C', textAlign: 'center', paddingHorizontal: 12, paddingBottom: 8 },
  listScroll: { maxHeight: 320 },
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 12, paddingHorizontal: 6,
    borderBottomWidth: 1, borderBottomColor: '#E6F0FB',
  },
  rowSelected: { backgroundColor: '#ECF5FF', borderRadius: 6 },
  rowBlocked: { opacity: 0.6 },
  wipBlocked: { fontSize: 13, color: '#F56C6C', marginTop: 4, fontWeight: '600' },
  radio: {
    width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, borderColor: '#C0C4CC',
    backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginRight: 12,
  },
  radioChecked: { borderColor: '#409EFF' },
  radioDot: { width: 12, height: 12, borderRadius: 6, backgroundColor: '#409EFF' },
  wipInfo: { flex: 1 },
  wipNo: { fontSize: 15, fontWeight: '600', color: '#303133' },
  wipProcess: { fontSize: 13, color: '#606266', marginTop: 2 },
  wipAvail: { fontSize: 13, color: '#409EFF', marginTop: 2, fontWeight: '500' },
  hint: {
    backgroundColor: '#FDF6EC', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 8, marginTop: 8,
  },
  hintText: { fontSize: 13, color: '#E6A23C', fontWeight: '600' },
});

export default WipBatchPicker;
