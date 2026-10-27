/**
 * CcpReadingsSection — 工序报工屏的「关键控制点 (CCP)」录入栏 (缺口 13)。
 *
 * 本道工序挂了 CCP 才出现。每项先写「名称 + 合格范围 + 单位」, 再给大字号数字输入框;
 * 填的数一超出范围立刻红字说清后果 (提交后这批转待检, 要通知质检员)。
 *
 * UX Flow Analysis (ux-flow 门控, 2026-10-26):
 *  用户: 产线操作员, 年纪偏大, 手机上逐道报工, 只看得懂「填什么数、合不合格」。
 *  旅程: 进入完工出成 → 看到「关键控制点（必填）」卡 → 按卡上写的范围测量 → 填数 →
 *        超范围当场红字 → 点提交 → 实测值随报工一起提交 (同一事务, 缺值整笔不收), 超限时弹窗告诉他「已转待检, 通知质检员」。
 *  摩擦点: F1 不知道合格范围 (Rule 1, HIGH) → 输入框上方先写范围和测量方法;
 *          F2 漏填 (Rule 1, HIGH) → 提交按钮禁用 + 按钮上方写「请先填写：xx」;
 *          F3 不知道超限后果 (MED) → 当场红字 + 提交后弹窗;
 *          F4 CCP 没加载出来 (Rule 5, HIGH) → 错误文案 + 「重新加载」按钮, 期间不让提交 (不能当成不用录);
 *          F5 看错数字 (内联规则) → 输入框字号 24、数字键盘。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { ccpApi, CcpStepCheckpoint } from '../../services/api/ccpApi';
import { CcpLoadStatus, formatCcpLimit, isCcpOutOfLimit, parseCcpValue } from './ccpReadings';

interface Props {
  workProcessId: string | null | undefined;
  factoryId?: string;
  values: Record<number, string>;
  onChangeValue: (checkpointId: number, text: string) => void;
  /** 加载状态 + 本道的 CCP 列表回报给父组件 (父组件据此挡提交)。 */
  onLoaded: (status: CcpLoadStatus, checkpoints: CcpStepCheckpoint[]) => void;
  disabled?: boolean;
}

export const CcpReadingsSection: React.FC<Props> = ({
  workProcessId, factoryId, values, onChangeValue, onLoaded, disabled = false,
}) => {
  const [status, setStatus] = useState<CcpLoadStatus>('loading');
  const [checkpoints, setCheckpoints] = useState<CcpStepCheckpoint[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!workProcessId) {
      setStatus('ready');
      setCheckpoints([]);
      onLoaded('ready', []);
      return;
    }
    setStatus('loading');
    setError(null);
    onLoaded('loading', []);
    try {
      const res = await ccpApi.listStepCheckpoints(workProcessId, factoryId);
      if (res.success && Array.isArray(res.data)) {
        setCheckpoints(res.data);
        setStatus('ready');
        onLoaded('ready', res.data);
      } else {
        setStatus('error');
        setError(res.message || '关键控制点加载失败');
        onLoaded('error', []);
      }
    } catch (e) {
      setStatus('error');
      setError(e instanceof Error ? e.message : '关键控制点加载失败');
      onLoaded('error', []);
    }
  }, [workProcessId, factoryId, onLoaded]);

  useEffect(() => {
    void load();
  }, [load]);

  if (status === 'ready' && checkpoints.length === 0) {
    return null;   // 本道没挂 CCP: 什么都不显示, 不打扰操作员
  }

  return (
    <View style={styles.card} testID="ccp-section">
      <Text style={styles.title}>关键控制点（必填）</Text>
      {status === 'loading' ? (
        <View style={styles.center}>
          <ActivityIndicator size="small" color="#E8732E" />
          <Text style={styles.muted}>正在加载本道的关键控制点…</Text>
        </View>
      ) : status === 'error' ? (
        <View style={styles.center}>
          <Text style={styles.errorText}>没加载出关键控制点：{error}</Text>
          <Text style={styles.muted}>加载出来之前不能提交本道产出</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={load} testID="ccp-reload">
            <Text style={styles.retryText}>重新加载</Text>
          </TouchableOpacity>
        </View>
      ) : (
        checkpoints.map((cp) => {
          const text = values[cp.checkpointId] ?? '';
          const value = parseCcpValue(text);
          const out = isCcpOutOfLimit(value, cp);
          return (
            <View key={cp.checkpointId} style={styles.item}>
              <Text style={styles.name}>{cp.name}</Text>
              <Text style={styles.limit}>合格范围：{formatCcpLimit(cp)}</Text>
              {cp.monitoringProcedure ? <Text style={styles.muted}>测量方法：{cp.monitoringProcedure}</Text> : null}
              <View style={styles.inputRow}>
                <TextInput
                  style={[styles.input, out && styles.inputBad]}
                  value={text}
                  onChangeText={(t) => onChangeValue(cp.checkpointId, t)}
                  keyboardType="decimal-pad"
                  placeholder="填实测值"
                  editable={!disabled}
                  accessibilityLabel={`${cp.name}实测值，合格范围${formatCcpLimit(cp)}`}
                  testID={`ccp-input-${cp.checkpointCode}`}
                />
                <Text style={styles.unit}>{cp.unit}</Text>
              </View>
              {out ? (
                <Text style={styles.badText} testID={`ccp-out-${cp.checkpointCode}`}>
                  超出合格范围！提交后这批会转为「待检」，请同时通知质检员。
                  {cp.correctiveAction ? `\n纠偏参考：${cp.correctiveAction}` : ''}
                </Text>
              ) : null}
            </View>
          );
        })
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  card: {
    marginTop: 12, marginBottom: 12, padding: 14, borderRadius: 8,
    borderWidth: 1, borderColor: '#F3D19E', backgroundColor: '#FDF6EC',
  },
  title: { fontSize: 16, fontWeight: '700', color: '#B88230', marginBottom: 8 },
  center: { alignItems: 'center', paddingVertical: 12 },
  muted: { fontSize: 13, color: '#909399', marginTop: 4 },
  errorText: { fontSize: 14, color: '#F56C6C', textAlign: 'center' },
  retryBtn: {
    marginTop: 10, minHeight: 44, minWidth: 120, paddingHorizontal: 16, alignItems: 'center',
    justifyContent: 'center', backgroundColor: '#FFEFD5', borderRadius: 6,
  },
  retryText: { fontSize: 15, color: '#E8732E', fontWeight: '600' },
  item: { paddingVertical: 10, borderTopWidth: 1, borderTopColor: '#FAECD8' },
  name: { fontSize: 16, fontWeight: '700', color: '#303133' },
  limit: { fontSize: 15, color: '#303133', marginTop: 2 },
  inputRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8 },
  input: {
    flex: 1, minHeight: 52, fontSize: 24, paddingHorizontal: 12, borderWidth: 1.5,
    borderColor: '#DCDFE6', borderRadius: 6, backgroundColor: '#FFFFFF', color: '#303133',
  },
  inputBad: { borderColor: '#F56C6C' },
  unit: { fontSize: 18, color: '#606266', marginLeft: 10, minWidth: 36 },
  badText: { fontSize: 14, color: '#F56C6C', marginTop: 6, fontWeight: '600', lineHeight: 20 },
});

export default CcpReadingsSection;
