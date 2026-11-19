import React, { useCallback, useEffect, useState, useRef } from 'react';
import { ActivityIndicator, Alert, ScrollView, StyleSheet, Text, View } from 'react-native';
import { RouteProp, useRoute } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { FAAIStackParamList } from '../../../types/navigation';
import { Button } from 'react-native-paper';
import { useAuthStore } from '../../../store/authStore';
import { operatingReportReaderKey } from '../../../utils/operatingReportAccess';
import { aiApiClient, AICostAnalysisResponse } from '../../../services/api/aiApiClient';
import MarkdownRenderer from '../../../components/common/MarkdownRenderer';

export default function AIReportDetailScreen() {
  const route = useRoute<RouteProp<FAAIStackParamList, 'AIReportDetail'>>();
  const user = useAuthStore((state) => state.user);
  const readerKey = operatingReportReaderKey(user);
  const requestSequence = useRef(0);
  const [loadedReaderKey, setLoadedReaderKey] = useState(readerKey);
  const [report, setReport] = useState<AICostAnalysisResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    const sequence = ++requestSequence.current;
    setLoading(true); setError(null); setReport(null);
    try {
      const response = await aiApiClient.getReportDetail(route.params.reportId);
      if (sequence === requestSequence.current) {
        setLoadedReaderKey(readerKey); setReport(response);
      }
    } catch {
      if (sequence === requestSequence.current) {
        setLoadedReaderKey(readerKey); setError('报告未能加载。请检查网络后重新加载。');
      }
    } finally { if (sequence === requestSequence.current) setLoading(false); }
  }, [route.params.reportId, readerKey]);
  useEffect(() => { void load(); return () => { requestSequence.current += 1; }; }, [load]);
  const [exporting, setExporting] = useState<'pdf' | 'pptx' | null>(null);
  const exportReport = useCallback(async (format: 'pdf' | 'pptx') => {
    if (exporting) return;
    setExporting(format);
    try {
      // 按需加载: 分享模块只在用户点导出时才需要 (原生模块, 不拖慢页面加载)
      const Sharing = await import('expo-sharing');
      const uri = await aiApiClient.downloadReportFile(route.params.reportId, format);
      if (!(await Sharing.isAvailableAsync())) { Alert.alert('无法分享', '当前设备不支持分享文件。'); return; }
      await Sharing.shareAsync(uri, {
        mimeType: format === 'pdf' ? 'application/pdf' : 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        dialogTitle: format === 'pdf' ? '导出经营报告 PDF' : '导出经营报告 PPT',
      });
    } catch (failure) {
      Alert.alert('导出失败', failure instanceof Error ? failure.message : '请稍后重试。');
    } finally { setExporting(null); }
  }, [exporting, route.params.reportId]);
  const visibleReport = loadedReaderKey === readerKey ? report : null;
  if (loading) return <View style={styles.center}><ActivityIndicator size="large" /></View>;
  return <SafeAreaView style={styles.page} edges={['bottom']}>
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.title}>{route.params.title?.replace(/^(日报|周报|月报)( · \d{4}-\d{2}-\d{2}) 00:00:00$/, '$1$2') || '经营分析报告'}</Text>
      {visibleReport?.generatedAt ? <Text style={styles.date}>生成时间：{visibleReport.generatedAt}</Text> : null}
      {error ? <View style={styles.failure}><Text style={styles.failureTitle}>{error}</Text>
        <Button onPress={() => void load()} contentStyle={styles.actionTarget}>重新加载</Button>
      </View> : null}
      {visibleReport?.generationStatus === 'FAILED' || visibleReport?.success === false ? <View style={styles.failure}>
        <Text style={styles.failureTitle}>生成失败</Text>
        <Text style={styles.body}>{visibleReport.errorMessage || '报告生成失败，请重新生成。'}</Text>
      </View> : null}
      {visibleReport && visibleReport.success !== false && visibleReport.generationStatus !== 'FAILED' ? <View style={styles.exportRow}>
        <Button mode="contained" icon="file-pdf-box" loading={exporting === 'pdf'} disabled={!!exporting} onPress={() => void exportReport('pdf')} contentStyle={styles.actionTarget}>导出 PDF</Button>
        <Button mode="outlined" icon="file-powerpoint-box" loading={exporting === 'pptx'} disabled={!!exporting} onPress={() => void exportReport('pptx')} contentStyle={styles.actionTarget}>导出 PPT</Button>
      </View> : null}
      {visibleReport?.analysis ? (
        <MarkdownRenderer content={visibleReport.analysis} style={styles.body} />
      ) : null}
    </ScrollView>
  </SafeAreaView>;
}
const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#f5f7fa' },
  exportRow: { flexDirection: 'row', gap: 12 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: 20, gap: 16 },
  title: { fontSize: 22, color: '#172c3e', fontWeight: '600' },
  date: { color: '#657786', fontSize: 12 },
  body: { fontSize: 15, lineHeight: 25, color: '#243746' },
  failure: { backgroundColor: '#fff1ef', padding: 16, borderRadius: 8, gap: 8 },
  failureTitle: { color: '#b42318', fontWeight: '600' },
  actionTarget: { minHeight: 44, minWidth: 44 }
});
