import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { RouteProp, useRoute } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { FAAIStackParamList } from '../../../types/navigation';
import { aiApiClient, AICostAnalysisResponse } from '../../../services/api/aiApiClient';

export default function AIReportDetailScreen() {
  const route = useRoute<RouteProp<FAAIStackParamList, 'AIReportDetail'>>();
  const [report, setReport] = useState<AICostAnalysisResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    setLoading(true); setError(null); setReport(null);
    try { setReport(await aiApiClient.getReportDetail(route.params.reportId)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : '报告加载失败，请重试。'); }
    finally { setLoading(false); }
  }, [route.params.reportId]);
  useEffect(() => { void load(); }, [load]);
  if (loading) return <View style={styles.center}><ActivityIndicator size="large" /></View>;
  return <SafeAreaView style={styles.page} edges={['bottom']}>
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={styles.title}>{route.params.title || '经营分析报告'}</Text>
      {report?.generatedAt ? <Text style={styles.date}>生成时间：{report.generatedAt}</Text> : null}
      {error ? <View style={styles.failure}><Text style={styles.failureTitle}>{error}</Text>
        <TouchableOpacity onPress={() => void load()} accessibilityRole="button"><Text style={styles.retry}>重新加载</Text></TouchableOpacity>
      </View> : null}
      {report?.generationStatus === 'FAILED' || report?.success === false ? <View style={styles.failure}>
        <Text style={styles.failureTitle}>生成失败</Text>
        <Text style={styles.body}>{report.errorMessage || '报告生成失败，请重新生成。'}</Text>
      </View> : null}
      {report?.analysis ? <Text selectable style={styles.body}>{report.analysis}</Text> : null}
    </ScrollView>
  </SafeAreaView>;
}
const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#f5f7fa' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: 20, gap: 16 },
  title: { fontSize: 22, color: '#172c3e', fontWeight: '600' },
  date: { color: '#657786', fontSize: 12 },
  body: { fontSize: 15, lineHeight: 25, color: '#243746' },
  failure: { backgroundColor: '#fff1ef', padding: 16, borderRadius: 8, gap: 8 },
  failureTitle: { color: '#b42318', fontWeight: '600' },
  retry: { color: '#287c8e', paddingVertical: 8 }
});
