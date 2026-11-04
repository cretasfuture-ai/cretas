/**
 * RawTimeLimitNotice — 本道原料批次的投料时限提示 (食品 DAG 缺口 14)
 *
 * 用途: MaterialBatchPicker 是按量分摊的多选, 不便把每行改成可禁用; 这里在它上方列出配了时限的原料批次,
 *       选之前就看得到哪批快到点、哪批不能投。能不能投仍由后端在提交时判定, 被拦时原话显示且不清空已填内容。
 *
 * UX Flow Analysis (ux-flow 门控, 2026-11-04): 见 PR #1426 的「UX Flow Analysis」。
 *  F2 不知道为什么投不了 → 行内写原因; F4 倒计时变旧 → 每分钟按截止时间重算, 且写明以提交结果为准。
 *  没配时限的工序不渲染, 不多占一行。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { processTimeLimitApi, ProcessTimeLimitStatus } from '../../services/api/processTimeLimitApi';
import { timeLimitBlockReason, timeLimitLine } from '../../utils/processTimeLimit';

interface RawTimeLimitNoticeProps {
  taskId?: number | null;
  factoryId?: string;
}

const RAW_TARGET = 'RAW_MATERIAL_BATCH';
const MINUTE_MS = 60000;

export const RawTimeLimitNotice: React.FC<RawTimeLimitNoticeProps> = ({ taskId, factoryId }) => {
  const [rows, setRows] = useState<ProcessTimeLimitStatus[]>([]);
  const [loadFailed, setLoadFailed] = useState(false);
  const [nowMs, setNowMs] = useState(() => Date.now());

  const load = useCallback(async () => {
    if (taskId == null) return;
    setLoadFailed(false);
    try {
      const res = await processTimeLimitApi.getTaskStatuses(taskId, factoryId);
      if (res.success && Array.isArray(res.data)) {
        setRows(res.data.filter((row) => row.targetType === RAW_TARGET));
      } else {
        setLoadFailed(true);
      }
    } catch {
      setLoadFailed(true);
    }
  }, [taskId, factoryId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), MINUTE_MS);
    return () => clearInterval(timer);
  }, []);

  if (loadFailed) {
    return (
      <View style={styles.wrap} testID="raw-time-limit-load-failed">
        <Text style={styles.line}>投料时限没有加载出来，能不能投以提交结果为准</Text>
        <TouchableOpacity onPress={load} style={styles.retry} accessibilityRole="button">
          <Text style={styles.retryText}>重新加载</Text>
        </TouchableOpacity>
      </View>
    );
  }
  if (rows.length === 0) return null;

  return (
    <View style={styles.wrap} testID="raw-time-limit-notice">
      <Text style={styles.title}>原料投料时限</Text>
      {rows.map((row) => {
        const blocked = timeLimitBlockReason(row, nowMs);
        return (
          <View key={row.targetId} style={styles.row}>
            <Text style={styles.batch}>{row.batchNumber}</Text>
            <Text
              style={blocked != null ? styles.blocked : styles.line}
              testID={`raw-time-limit-${row.targetId}`}
            >
              {blocked ?? timeLimitLine(row, nowMs) ?? '未超时'}
            </Text>
          </View>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: {
    marginBottom: 12, padding: 12, borderRadius: 8, borderWidth: 1,
    borderColor: '#F5DAB1', backgroundColor: '#FDF6EC',
  },
  title: { fontSize: 15, fontWeight: '700', color: '#B88230', marginBottom: 6 },
  row: { paddingVertical: 6 },
  batch: { fontSize: 14, fontWeight: '600', color: '#303133' },
  line: { fontSize: 13, color: '#606266', marginTop: 2 },
  blocked: { fontSize: 13, color: '#F56C6C', marginTop: 2, fontWeight: '600' },
  retry: {
    marginTop: 8, minHeight: 44, justifyContent: 'center', alignItems: 'center',
    backgroundColor: '#FFEFD5', borderRadius: 6,
  },
  retryText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },
});

export default RawTimeLimitNotice;
