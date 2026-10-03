/**
 * AI报告列表页面
 * 显示历史AI分析报告
 */
import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  RefreshControl,
  ActivityIndicator,
  FlatList,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Button, Icon, TouchableRipple } from 'react-native-paper';
import { useAuthStore } from '../../../store/authStore';
import { operatingReportReaderKey } from '../../../utils/operatingReportAccess';
import { useTranslation } from 'react-i18next';
import { FAAIStackParamList } from '../../../types/navigation';
import { aiApiClient, ReportSummary } from '../../../services/api/aiApiClient';

type NavigationProp = NativeStackNavigationProp<FAAIStackParamList, 'AIReport'>;

type ReportType = 'all' | 'batch' | 'daily' | 'weekly' | 'monthly' | 'custom';

function reportPreview(markdown: string): string {
  return markdown
    .replace(/^\s{0,3}(?:#{1,6}\s+|>\s*|[-*+]\s+)/gm, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*|__([^_]+)__/g, '$1$2')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}

export function AIReportScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t, i18n } = useTranslation('home');
  const user = useAuthStore((state) => state.user);
  const readerKey = operatingReportReaderKey(user);
  const requestSequence = useRef(0);
  const [loadedReaderKey, setLoadedReaderKey] = useState(readerKey);

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [reports, setReports] = useState<ReportSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filterType, setFilterType] = useState<ReportType>('all');

  const loadReports = useCallback(async () => {
    const sequence = ++requestSequence.current;
    try {
      setError(null);
      const params = filterType !== 'all' ? { reportType: filterType } : undefined;
      const response = await aiApiClient.getReports(params);
      if (sequence !== requestSequence.current) return;
      setLoadedReaderKey(readerKey);
      setReports(response.reports || []);
    } catch (err) {
      if (sequence !== requestSequence.current) return;
      setLoadedReaderKey(readerKey);
      setError('报告未能加载。请检查网络后重新加载。');
    } finally {
      if (sequence === requestSequence.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [filterType, readerKey]);

  useEffect(() => {
    setReports([]);
    setError(null);
    setLoading(true);
    void loadReports();
    return () => { requestSequence.current += 1; };
  }, [loadReports]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadReports();
  }, [loadReports]);

  const getReportTypeIcon = (type: string): string => {
    const icons: Record<string, string> = {
      batch: 'package-variant',
      daily: 'calendar-today',
      weekly: 'calendar-week',
      monthly: 'calendar-month',
      custom: 'tune',
    };
    return icons[type] || 'file-document';
  };

  const getReportTypeColor = (type: string): string => {
    const colors: Record<string, string> = {
      batch: '#667eea',
      daily: '#287c8e',
      weekly: '#48bb78',
      monthly: '#ed8936',
      custom: '#9f7aea',
    };
    return colors[type] || '#667eea';
  };

  const getReportTypeLabel = (type: string): string => {
    const labels: Record<string, string> = {
      batch: t('aiReport.batchAnalysis'),
      daily: '日报',
      weekly: t('aiReport.weeklyReport'),
      monthly: t('aiReport.monthlyReport'),
      custom: t('aiReport.customReport'),
    };
    return labels[type] || type;
  };

  const formatDate = (dateStr?: string): string => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleDateString(i18n.language, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  const renderFilterTabs = () => (
    <View style={styles.filterTabs}>
      {(['all', 'daily', 'weekly', 'monthly'] as ReportType[]).map((type) => (
        <TouchableRipple
          key={type}
          style={[styles.filterTab, filterType === type && styles.filterTabActive]}
          onPress={() => setFilterType(type)}
        >
          <Text
            style={[styles.filterTabText, filterType === type && styles.filterTabTextActive]}
          >
            {type === 'all' ? t('common:all') : getReportTypeLabel(type)}
          </Text>
        </TouchableRipple>
      ))}
    </View>
  );

  const renderReportItem = ({ item }: { item: ReportSummary }) => (
    <TouchableRipple
      style={styles.reportCard}
      onPress={() => navigation.navigate('AIReportDetail', {
        reportId: item.reportId,
        reportType: item.reportType,
        title: item.title,
      })}
    >
      <View>
      <View style={styles.reportHeader}>
        <View style={[styles.typeIcon, { backgroundColor: getReportTypeColor(item.reportType) + '20' }]}>
          <Icon
            source={getReportTypeIcon(item.reportType)}
            size={20}
            color={getReportTypeColor(item.reportType)}
          />
        </View>
        <View style={styles.reportInfo}>
          <Text style={styles.reportTitle} numberOfLines={2}>{item.title.replace(/^(日报|周报|月报)( · \d{4}-\d{2}-\d{2}) 00:00:00$/, '$1$2')}</Text>
          <View style={styles.reportMeta}>
            <Text style={styles.reportDate}>{formatDate(item.createdAt)}</Text>
            <View style={[styles.typeBadge, { backgroundColor: getReportTypeColor(item.reportType) + '20' }]}>
              <Text style={[styles.typeText, { color: getReportTypeColor(item.reportType) }]}>
                {getReportTypeLabel(item.reportType)}
              </Text>
            </View>
          </View>
        </View>
        <Icon source="chevron-right" size={20} color="#ccc" />
      </View>

      {item.generationStatus === 'FAILED' ? (
        <View style={styles.failurePanel}>
          <Text style={styles.failureTitle}>生成失败</Text>
          <Text style={styles.failureReason}>{item.failureReason || '报告生成失败，请重新生成。'}</Text>
        </View>
      ) : item.summary ? <Text style={styles.summary} numberOfLines={3}>{reportPreview(item.summary)}</Text> : null}

      {/* 报告统计 */}
      {(item.totalCost !== undefined || item.keyFindingsCount !== undefined) && (
        <View style={styles.reportStats}>
          {item.totalCost !== undefined && (
            <View style={styles.statItem}>
              <Text style={styles.statLabel}>{t('aiReport.totalCost')}</Text>
              <Text style={styles.statValue}>¥{Number(item.totalCost ?? 0).toFixed(2)}</Text>
            </View>
          )}
          {item.keyFindingsCount !== undefined && (
            <View style={styles.statItem}>
              <Text style={styles.statLabel}>{t('aiReport.keyFindings')}</Text>
              <Text style={styles.statValue}>{item.keyFindingsCount} {t('aiReport.items')}</Text>
            </View>
          )}
          {item.suggestionsCount !== undefined && (
            <View style={styles.statItem}>
              <Text style={styles.statLabel}>{t('aiReport.suggestions')}</Text>
              <Text style={styles.statValue}>{item.suggestionsCount} {t('aiReport.items')}</Text>
            </View>
          )}
        </View>
      )}
      </View>
    </TouchableRipple>
  );

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['bottom']}>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
          <Text style={styles.loadingText}>{t('loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['bottom']}>
      {/* Filter Tabs */}
      {renderFilterTabs()}

      {/* 错误提示 */}
      {error && (
        <View style={styles.errorBanner}>
          <Icon source="alert-circle" size={20} color="#e53e3e" />
          <Text style={styles.errorText}>{error}</Text>
          <Button onPress={onRefresh} contentStyle={styles.actionTarget}>重新加载</Button>
        </View>
      )}

      <FlatList
        data={loadedReaderKey === readerKey ? reports : []}
        keyExtractor={(item) => String(item.reportId)}
        renderItem={renderReportItem}
        contentContainerStyle={styles.listContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Icon source="file-document-outline" size={48} color="#ccc" />
            <Text style={styles.emptyText}>{t('aiReport.noReports')}</Text>
            <Text style={styles.emptyHint}>{t('aiReport.noReportsHint')}</Text>
            <Button mode="outlined" onPress={onRefresh} contentStyle={styles.actionTarget}>重新加载</Button>
          </View>
        }
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  filterTabs: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 8,
  },
  filterTab: {
    flex: 1,
    minHeight: 44,
    minWidth: 44,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 4,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: '#f0f0f0',
  },
  filterTabActive: {
    backgroundColor: '#667eea',
  },
  filterTabText: {
    fontSize: 14,
    color: '#666',
  },
  filterTabTextActive: {
    color: '#fff',
    fontWeight: '500',
  },
  actionTarget: { minHeight: 44, minWidth: 44 },
  errorBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fed7d7',
    marginHorizontal: 16,
    marginTop: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 8,
    gap: 8,
  },
  errorText: {
    flex: 1,
    fontSize: 14,
    color: '#c53030',
  },
  listContent: {
    padding: 16,
  },
  reportCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  reportHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  typeIcon: {
    width: 44,
    height: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  reportInfo: {
    flex: 1,
    marginLeft: 12,
  },
  reportTitle: {
    fontSize: 15,
    fontWeight: '500',
    color: '#1a202c',
    lineHeight: 20,
  },
  reportMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 6,
    gap: 8,
  },
  reportDate: {
    fontSize: 12,
    color: '#999',
  },
  typeBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
  },
  typeText: {
    fontSize: 11,
    fontWeight: '500',
  },
  summary: { marginTop: 12, color: '#526170', fontSize: 13, lineHeight: 20 },
  failurePanel: { marginTop: 12, padding: 12, borderRadius: 8, backgroundColor: '#fff1ef' },
  failureTitle: { color: '#b42318', fontWeight: '600', marginBottom: 4 },
  failureReason: { color: '#8b2c25', lineHeight: 20 },
  reportStats: {
    flexDirection: 'row',
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#f0f0f0',
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statLabel: {
    fontSize: 11,
    color: '#999',
    marginBottom: 2,
  },
  statValue: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  emptyContainer: {
    alignItems: 'center',
    paddingVertical: 60,
  },
  emptyText: {
    marginTop: 12,
    fontSize: 16,
    color: '#666',
  },
  emptyHint: {
    marginTop: 4,
    fontSize: 13,
    color: '#999',
  },
});

export default AIReportScreen;
