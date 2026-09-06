import React, { useState, useEffect } from 'react';
import { appAlert } from '../../components/ui/AppDialog';
import { View, StyleSheet, FlatList, RefreshControl, TouchableOpacity } from 'react-native';
import {
  Text,
  Appbar,
  Card,
  SegmentedButtons,
  Chip,
  ActivityIndicator,
  IconButton,
  Divider,
  FAB,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { ProcessingScreenProps } from '../../types/navigation';
import { aiApiClient, ReportSummary } from '../../services/api/aiApiClient';
import { useAuthStore } from '../../store/authStore';
import { getFactoryId } from '../../types/auth';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';

// 创建AIReportList专用logger
const aiReportListLogger = logger.createContextLogger('AIReportList');

type AIReportListScreenProps = ProcessingScreenProps<'AIReportList'>;

/**
 * AI报告列表界面
 *
 * 功能:
 * - 展示所有历史AI报告列表
 * - 按报告类型分类显示(批次分析/周报/月报/自定义)
 * - 显示生成时间、报告标题、批次号
 * - 点击查看完整报告详情
 * - 下拉刷新和加载更多
 *
 * @version 1.0.0
 * @since 2025-11-05
 */
export default function AIReportListScreen() {
  const navigation = useNavigation<AIReportListScreenProps['navigation']>();
  const { user } = useAuthStore();
  const factoryId = getFactoryId(user);
  const { t } = useTranslation('processing');

  // 状态管理
  const [selectedType, setSelectedType] = useState<string>('all');
  const [reports, setReports] = useState<ReportSummary[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);

  // 页面加载时获取报告
  useEffect(() => {
    fetchReports();
  }, [selectedType]);

  /**
   * 获取报告列表
   */
  const fetchReports = async () => {
    try {
      setLoading(true);

      const factoryId = user?.factoryUser?.factoryId;
      if (!factoryId) {
        appAlert(t('aiReports.messages.loadFailed'), t('aiReports.messages.userInfoError'));
        return;
      }

      // 构建查询参数
      const params: any = {};
      if (selectedType !== 'all') {
        params.reportType = selectedType;
      }

      aiReportListLogger.debug('获取AI报告列表', { factoryId, params, selectedType });

      const response = await aiApiClient.getReports(params, factoryId);

      if (response && response.reports) {
        aiReportListLogger.info('AI报告列表加载成功', {
          factoryId,
          reportCount: response.reports.length,
          reportType: selectedType,
        });
        setReports(response.reports);
      } else {
        setReports([]);
      }
    } catch (error) {
      aiReportListLogger.error('获取AI报告列表失败', error as Error, {
        factoryId: factoryId,
        selectedType,
      });
      appAlert(t('aiReports.messages.loadFailed'), getErrorMsg(error) || t('aiReports.messages.retry'));
      setReports([]);
    } finally {
      setLoading(false);
    }
  };

  /**
   * 下拉刷新
   */
  const handleRefresh = async () => {
    setRefreshing(true);
    await fetchReports();
    setRefreshing(false);
  };

  /**
   * 查看报告详情
   */
  const handleViewReport = (report: ReportSummary) => {
    navigation.navigate('AIAnalysisDetail', {
      reportId: report.reportId,
      reportType: report.reportType,
      title: report.title,
    });
  };

  /**
   * 从Markdown内容提取干净的标题
   * 移除 ### 标记、emoji等，返回可读标题
   */
  const extractCleanTitle = (title: string, reportType: string): string => {
    if (!title) {
      // 根据报告类型返回默认标题
      return t(`aiReports.reportType.${reportType}`, { defaultValue: t('aiReports.title') });
    }

    // 移除 Markdown 标记
    let cleanTitle = title
      .replace(/^#+\s*/gm, '')           // 移除 ### 标题标记
      .replace(/\*\*([^*]+)\*\*/g, '$1') // 移除粗体标记
      .replace(/\*([^*]+)\*/g, '$1')     // 移除斜体标记
      .replace(/>\s*/g, '')              // 移除引用标记
      .replace(/[🎯📊💡🔴🔍📈✅❌⚠️🔧📋]/g, '') // 移除常用emoji
      .trim();

    // 提取第一行有意义的内容
    const lines = cleanTitle.split('\n').filter(line => line.trim().length > 0);
    if (lines.length > 0 && lines[0]) {
      cleanTitle = lines[0].trim();
    }

    // 如果标题太长，截断
    if (cleanTitle.length > 30) {
      cleanTitle = cleanTitle.substring(0, 30) + '...';
    }

    // 如果标题为空或无意义，返回默认
    if (!cleanTitle || cleanTitle.length < 2) {
      return t(`aiReports.reportType.${reportType}`, { defaultValue: t('aiReports.title') });
    }

    return cleanTitle;
  };

  /**
   * 提取报告摘要（用于预览）
   */
  const extractSummary = (title: string): string | null => {
    if (!title) return null;

    // 尝试提取引用块内容作为摘要
    const quoteMatch = title.match(/>\s*(.+)/);
    if (quoteMatch && quoteMatch[1]) {
      let summary = quoteMatch[1]
        .replace(/\*\*([^*]+)\*\*/g, '$1')
        .trim();
      if (summary.length > 50) {
        summary = summary.substring(0, 50) + '...';
      }
      return summary;
    }

    return null;
  };

  /**
   * 获取报告类型徽章
   */
  const getReportTypeChip = (type: string) => {
    const typeMap = {
      batch: { labelKey: 'batch', icon: 'package-variant', color: '#2196F3' },
      weekly: { labelKey: 'weekly', icon: 'calendar-week', color: '#4CAF50' },
      monthly: { labelKey: 'monthly', icon: 'calendar-month', color: '#FF9800' },
      custom: { labelKey: 'custom', icon: 'tune', color: '#9C27B0' },
    };

    const config = typeMap[type as keyof typeof typeMap] || typeMap.custom;

    return (
      <Chip
        icon={config.icon}
        mode="outlined"
        compact
        style={[styles.typeChip, { borderColor: config.color }]}
        textStyle={{ color: config.color, fontSize: 11 }}
      >
        {t(`aiReports.reportType.${config.labelKey}`)}
      </Chip>
    );
  };

  /**
   * 格式化时间
   */
  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return t('aiReports.time.justNow');
    if (diffMins < 60) return t('aiReports.time.minutesAgo', { minutes: diffMins });
    if (diffHours < 24) return t('aiReports.time.hoursAgo', { hours: diffHours });
    if (diffDays < 7) return t('aiReports.time.daysAgo', { days: diffDays });

    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    });
  };

  /**
   * 渲染报告卡片
   */
  const renderReportCard = ({ item }: { item: ReportSummary }) => {
    const cleanTitle = extractCleanTitle(item.title, item.reportType);
    const summary = extractSummary(item.title);

    return (
      <TouchableOpacity
        onPress={() => handleViewReport(item)}
        activeOpacity={0.7}
      >
        <Card style={styles.reportCard} mode="elevated">
          <Card.Content>
            {/* 报告头部 */}
            <View style={styles.cardHeader}>
              <View style={styles.titleContainer}>
                <Text variant="titleMedium" style={styles.reportTitle} numberOfLines={1}>
                  {cleanTitle}
                </Text>
                {getReportTypeChip(item.reportType)}
              </View>
              <IconButton
                icon="chevron-right"
                size={24}
                iconColor="#9E9E9E"
                onPress={() => handleViewReport(item)}
              />
            </View>

            {/* 报告摘要预览 */}
            {summary && (
              <Text variant="bodySmall" style={styles.summaryText} numberOfLines={2}>
                {summary}
              </Text>
            )}

          {/* 报告元数据 */}
          <View style={styles.metadataContainer}>
            {/* 批次号 */}
            {item.batchNumber && (
              <View style={styles.metadataRow}>
                <Text variant="bodySmall" style={styles.metadataLabel}>{t('aiReports.metadata.batch')}</Text>
                <Text variant="bodySmall" style={styles.metadataValue}>
                  {item.batchNumber}
                </Text>
              </View>
            )}

            {/* 时间范围 */}
            {item.startDate && item.endDate && (
              <View style={styles.metadataRow}>
                <Text variant="bodySmall" style={styles.metadataLabel}>{t('aiReports.metadata.time')}</Text>
                <Text variant="bodySmall" style={styles.metadataValue}>
                  {new Date(item.startDate).toLocaleDateString('zh-CN')} - {new Date(item.endDate).toLocaleDateString('zh-CN')}
                </Text>
              </View>
            )}

            {/* 成本 */}
            {item.totalCost !== undefined && item.totalCost !== null && (
              <View style={styles.metadataRow}>
                <Text variant="bodySmall" style={styles.metadataLabel}>{t('aiReports.metadata.cost')}</Text>
                <Text variant="bodySmall" style={[styles.metadataValue, styles.costValue]}>
                  ¥{item.totalCost.toFixed(2)}
                </Text>
              </View>
            )}
          </View>

          <Divider style={styles.divider} />

          {/* 报告统计 */}
          <View style={styles.statsContainer}>
            {item.keyFindingsCount !== undefined && item.keyFindingsCount > 0 && (
              <View style={styles.statItem}>
                <Text variant="bodySmall" style={styles.statIcon}>🔍</Text>
                <Text variant="bodySmall" style={styles.statText}>
                  {t('aiReports.stats.findings', { count: item.keyFindingsCount })}
                </Text>
              </View>
            )}

            {item.suggestionsCount !== undefined && item.suggestionsCount > 0 && (
              <View style={styles.statItem}>
                <Text variant="bodySmall" style={styles.statIcon}>💡</Text>
                <Text variant="bodySmall" style={styles.statText}>
                  {t('aiReports.stats.suggestions', { count: item.suggestionsCount })}
                </Text>
              </View>
            )}

            <View style={styles.statItem}>
              <Text variant="bodySmall" style={styles.statIcon}>📅</Text>
              <Text variant="bodySmall" style={styles.statText}>
                {formatDate(item.createdAt)}
              </Text>
            </View>
          </View>
        </Card.Content>
      </Card>
    </TouchableOpacity>
    );
  };

  return (
    <View style={styles.container}>
      {/* 顶部导航栏 */}
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('aiReports.title')} />
      </Appbar.Header>

      {/* 报告类型筛选 */}
      <SegmentedButtons
        value={selectedType}
        onValueChange={setSelectedType}
        buttons={[
          { value: 'all', label: t('aiReports.types.all') },
          { value: 'batch', label: t('aiReports.types.batch') },
          { value: 'weekly', label: t('aiReports.types.weekly') },
          { value: 'monthly', label: t('aiReports.types.monthly') },
        ]}
        style={styles.segmentedButtons}
      />

      {/* 报告列表 */}
      {loading && !refreshing ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
          <Text variant="bodyMedium" style={styles.loadingText}>{t('aiReports.loading')}</Text>
        </View>
      ) : (
        <FlatList
          data={reports}
          renderItem={renderReportCard}
          keyExtractor={(item) => item.reportId.toString()}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
          }
          ListEmptyComponent={
            <View style={styles.emptyContainer}>
              <Text variant="displaySmall" style={styles.emptyIcon}>📊</Text>
              <Text variant="titleMedium" style={styles.emptyText}>
                {t('aiReports.empty.title')}
              </Text>
              <Text variant="bodyMedium" style={styles.emptyHint}>
                {t('aiReports.empty.hint')}
              </Text>
            </View>
          }
        />
      )}

      {/* 浮动按钮 - 生成新报告 */}
      <FAB
        icon="plus"
        label={t('aiReports.fab')}
        style={styles.fab}
        onPress={() => navigation.navigate('TimeRangeCostAnalysis' as never)}
        color="#FFFFFF"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  segmentedButtons: {
    marginHorizontal: 16,
    marginVertical: 12,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    color: '#757575',
  },
  listContent: {
    padding: 16,
    paddingBottom: 80,
  },
  reportCard: {
    marginBottom: 12,
    backgroundColor: '#FFFFFF',
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 12,
  },
  titleContainer: {
    flex: 1,
    gap: 8,
  },
  reportTitle: {
    fontWeight: '700',
    color: '#212121',
    flex: 1,
  },
  summaryText: {
    color: '#616161',
    marginTop: 4,
    marginBottom: 8,
    lineHeight: 18,
  },
  typeChip: {
    alignSelf: 'flex-start',
    height: 24,
  },
  metadataContainer: {
    gap: 6,
    marginBottom: 12,
  },
  metadataRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  metadataLabel: {
    color: '#757575',
    width: 50,
    fontWeight: '500',
  },
  metadataValue: {
    color: '#424242',
    flex: 1,
  },
  costValue: {
    color: '#2196F3',
    fontWeight: '600',
  },
  divider: {
    marginVertical: 12,
    backgroundColor: '#E0E0E0',
  },
  statsContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
    flexWrap: 'wrap',
  },
  statItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  statIcon: {
    fontSize: 14,
  },
  statText: {
    color: '#757575',
    fontSize: 12,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 80,
    paddingHorizontal: 24,
  },
  emptyIcon: {
    fontSize: 80,
    marginBottom: 16,
    opacity: 0.3,
  },
  emptyText: {
    color: '#9E9E9E',
    textAlign: 'center',
    marginBottom: 8,
  },
  emptyHint: {
    color: '#BDBDBD',
    textAlign: 'center',
  },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
    backgroundColor: '#9C27B0',
  },
});
