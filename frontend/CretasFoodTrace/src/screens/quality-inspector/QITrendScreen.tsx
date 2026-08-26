/**
 * 趋势分析页面
 * Quality Inspector - Trend Analysis Screen
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
  Dimensions,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
import { useTranslation } from 'react-i18next';

import {
  QI_COLORS,
} from '../../types/qualityInspector';
import {
  qualityInspectorApi,
  QITrendPointWire,
} from '../../services/api/qualityInspectorApi';
import { useAuthStore } from '../../store/authStore';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const CHART_WIDTH = SCREEN_WIDTH - 64;

/**
 * 页面模型。
 *
 * APP-CONTRACT-005 (2026-08-25): 这个页面此前有两个互相掩盖的缺陷 ——
 *
 *  (1) 它声明的 dates / passRates / avgScores / inspectionCounts / gradesTrend
 *      在后端全仓**零命中**。它打的 `/reports/dashboard/trends` 返回的是
 *      productionTrend / qualityTrend / costTrend / materialTrend / equipmentTrend。
 *      也就是说请求**成功**时 getMetricData() 拿到 undefined, 下一行 values.length
 *      直接崩 —— 没有任何一条路径能显示真实数据。
 *  (2) catch 分支用 `Math.random()` 现编合格率 / 平均分 / A-D 等级分布, 而且不带
 *      任何"演示数据"标记。于是 (1) 的崩溃被 (2) 掩盖成"页面看起来正常", 用户看到的
 *      是一屏随机数。
 *
 * 现在: 换到后端真实存在的质检趋势端点
 *   GET /processing/quality/trends?days=N -> [{ date, passRate, inspectionCount }]
 * (ProcessingServiceImpl#getQualityTrends, 数据源是 quality_inspections 表)。
 *
 * 后端**没有数据源**的两个指标一律不画:
 *   - 综合评分 avgScore  : quality_inspections 表里没有综合评分字段
 *                          (同一结论已写在 QIAnalysisScreen 的 APP-CONTRACT-004 里)
 *   - 逐日等级分布 A/B/C/D: 该端点只逐日返回合格率与检验数, 不返回等级
 * 它们显示"该指标暂无数据源 + 原因", **不编数字**。
 *
 * 空日的处理: 后端对没有质检记录的日期 put 的是 passRate=0 / inspectionCount=0。
 * `0%` 与"当天没人做质检"在图上长得一模一样, 所以这里用 inspectionCount === 0
 * 把它还原成 null(无读数), 图上留空, 统计摘要也不把它算进平均 —— 检验数(count)
 * 例外, 计数的 0 是合法读数。
 */
interface TrendPoint {
  /** 后端原样返回的 YYYY-MM-DD */
  date: string;
  /** X 轴短标签 M/D */
  label: string;
  /** null = 当天没有质检记录, 不是"合格率 0%" */
  passRate: number | null;
  inspectionCount: number;
}

type MetricType = 'passRate' | 'avgScore' | 'count';

/** 每个指标在**当前后端**有没有数据源。null 的那个不画图, 显示原因。 */
const METRIC_HAS_SOURCE: Record<MetricType, boolean> = {
  passRate: true,
  avgScore: false,
  count: true,
};

/** 把后端逐日项转成页面模型; 形状不对就抛, 不做"猜一个默认值"式的兜底。 */
function adaptTrendPoints(raw: unknown): TrendPoint[] {
  if (!Array.isArray(raw)) {
    throw new Error('TREND_SHAPE_UNEXPECTED');
  }
  return (raw as QITrendPointWire[]).map((item) => {
    const date = String(item?.date ?? '');
    const inspectionCount = Number(item?.inspectionCount ?? 0);
    const rawRate = Number(item?.passRate);
    const [, month, day] = date.split('-');
    return {
      date,
      label: month && day ? `${Number(month)}/${Number(day)}` : date,
      // 没有质检记录的那天后端给的是 0 —— 那不是一个读数, 还原成 null
      passRate: inspectionCount > 0 && Number.isFinite(rawRate) ? rawRate : null,
      inspectionCount: Number.isFinite(inspectionCount) ? inspectionCount : 0,
    };
  });
}

export default function QITrendScreen() {
  const { t } = useTranslation('quality');
  const insets = useSafeAreaInsets();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [points, setPoints] = useState<TrendPoint[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [period, setPeriod] = useState<'7d' | '30d' | '90d'>('7d');
  const [metric, setMetric] = useState<MetricType>('passRate');

  useEffect(() => {
    if (factoryId) {
      qualityInspectorApi.setFactoryId(factoryId);
      loadData();
    } else {
      setLoading(false);
      setError(t('analysis.noFactory'));
    }
  }, [factoryId, period]);

  const loadData = async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await qualityInspectorApi.getTrendData(period);
      setPoints(adaptTrendPoints(result));
    } catch (err) {
      // ⛔ 这里以前用 Math.random() 编了一整屏数据。质检是溯源链上的责任环节,
      //    编出来的合格率会被当成真的看 —— 失败就明确说失败。
      console.error('[QITrendScreen] load trend failed:', err);
      setPoints(null);
      setError(
        (err as Error)?.message === 'TREND_SHAPE_UNEXPECTED'
          ? t('analysis.dataFormatError')
          : t('analysis.loadError')
      );
    } finally {
      setLoading(false);
    }
  };

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, [period]);

  /**
   * 当前指标的逐日读数。null = 那天没有读数(不是 0)。
   * 没有数据源的指标返回空数组, 由调用方走"暂无数据源"分支。
   */
  const getMetricSeries = (): (number | null)[] => {
    if (!points || !METRIC_HAS_SOURCE[metric]) return [];
    switch (metric) {
      case 'passRate':
        return points.map((p) => p.passRate);
      case 'count':
        return points.map((p) => p.inspectionCount);
      default:
        return [];
    }
  };

  /** 有读数的那些值, 用于 max/min/avg —— 空日不参与。 */
  const getMetricValues = (): number[] =>
    getMetricSeries().filter((v): v is number => v !== null);

  const getMetricLabel = (): string => {
    switch (metric) {
      case 'passRate':
        return `${t('analysis.passRate')} (%)`;
      case 'avgScore':
        return t('analysis.avgScore');
      case 'count':
        return t('trend.inspectionCount');
      default:
        return '';
    }
  };

  const getMetricColor = (): string => {
    switch (metric) {
      case 'passRate':
        return QI_COLORS.success;
      case 'avgScore':
        return QI_COLORS.primary;
      case 'count':
        return QI_COLORS.secondary;
      default:
        return QI_COLORS.primary;
    }
  };

  /** 后端没有这个指标的数据源时显示的卡片 —— 说清"为什么没有", 不画空图。 */
  const renderNoSource = (reason: string) => (
    <View style={styles.noSourceBox}>
      <Ionicons name="information-circle-outline" size={28} color={QI_COLORS.textSecondary} />
      <Text style={styles.noSourceTitle}>{t('trend.noMetricSource')}</Text>
      <Text style={styles.noSourceReason}>{reason}</Text>
    </View>
  );

  // 简单折线图渲染。series 里的 null = 那天没有质检记录, 图上留空, 不画成 0。
  const renderLineChart = () => {
    if (!METRIC_HAS_SOURCE[metric]) {
      return renderNoSource(t('trend.avgScoreNoSource'));
    }

    const series = getMetricSeries();
    const values = getMetricValues();
    if (series.length === 0 || values.length === 0) {
      return (
        <View style={styles.noSourceBox}>
          <Ionicons name="bar-chart-outline" size={28} color={QI_COLORS.textSecondary} />
          <Text style={styles.noSourceTitle}>{t('trend.noDataInRange')}</Text>
        </View>
      );
    }

    const maxValue = Math.max(...values);
    const minValue = Math.min(...values);
    const range = maxValue - minValue || 1;
    const digits = metric === 'count' ? 0 : 1;

    return (
      <View style={styles.chartContainer}>
        <Text style={styles.chartLabel}>{getMetricLabel()}</Text>
        <View style={styles.chart}>
          {/* Y轴刻度 */}
          <View style={styles.yAxis}>
            <Text style={styles.yAxisLabel}>{maxValue.toFixed(digits)}</Text>
            <Text style={styles.yAxisLabel}>
              {((maxValue + minValue) / 2).toFixed(digits)}
            </Text>
            <Text style={styles.yAxisLabel}>{minValue.toFixed(digits)}</Text>
          </View>

          {/* 图表区域 */}
          <View style={styles.chartArea}>
            {/* 网格线 */}
            <View style={styles.gridLines}>
              <View style={styles.gridLine} />
              <View style={styles.gridLine} />
              <View style={styles.gridLine} />
            </View>

            {/* 数据点。x 位置按**完整序列**的下标算, 空日就是一个缺口 */}
            <View style={styles.dataPoints}>
              {series.map((value, index) => {
                if (value === null) return null; // 无读数 → 不画点
                const x = series.length > 1 ? (index / (series.length - 1)) * 100 : 50;
                const y = ((maxValue - value) / range) * 100;
                return (
                  <View
                    key={index}
                    style={[
                      styles.dataPoint,
                      {
                        left: `${x}%`,
                        top: `${y}%`,
                        backgroundColor: getMetricColor(),
                      },
                    ]}
                  />
                );
              })}
            </View>

            {/* X轴标签: 点多的时候只标首/中/尾, 否则挤成一团 */}
            <View style={styles.xAxis}>
              {(points ?? [])
                .filter((_, i, arr) =>
                  arr.length <= 10 ||
                  i === 0 ||
                  i === arr.length - 1 ||
                  i === Math.floor((arr.length - 1) / 2)
                )
                .map((p) => (
                  <Text key={p.date} style={styles.xAxisLabel}>
                    {p.label}
                  </Text>
                ))}
            </View>
          </View>
        </View>
        {series.some((v) => v === null) && (
          <Text style={styles.chartFootnote}>{t('trend.emptyDaysNote')}</Text>
        )}
      </View>
    );
  };

  /**
   * 等级趋势。
   *
   * 这里原来画的是 A/B/C/D 逐日堆叠柱 —— 数据来自 catch 分支的 Math.random()。
   * `/processing/quality/trends` 逐日只返回 { date, passRate, inspectionCount },
   * **不返回等级**, 所以这个图没有数据源: 说明原因, 不画。
   * (整月的等级分布在 QIAnalysisScreen 上有真数据, 走 /reports/dashboard/quality。)
   */
  const renderGradesTrend = () => (
    <View style={styles.gradesTrendContainer}>
      <Text style={styles.sectionTitle}>{t('trend.gradeTrend')}</Text>
      {renderNoSource(t('trend.gradeTrendNoSource'))}
    </View>
  );

  const renderPeriodTab = (p: '7d' | '30d' | '90d', label: string) => (
    <TouchableOpacity
      style={[styles.periodTab, period === p && styles.periodTabActive]}
      onPress={() => setPeriod(p)}
    >
      <Text style={[styles.periodText, period === p && styles.periodTextActive]}>
        {label}
      </Text>
    </TouchableOpacity>
  );

  const renderMetricTab = (m: MetricType, label: string, icon: IoniconsName) => (
    <TouchableOpacity
      style={[styles.metricTab, metric === m && styles.metricTabActive]}
      onPress={() => setMetric(m)}
    >
      <Ionicons
        name={icon}
        size={18}
        color={metric === m ? QI_COLORS.primary : QI_COLORS.textSecondary}
      />
      <Text style={[styles.metricText, metric === m && styles.metricTextActive]}>
        {label}
      </Text>
    </TouchableOpacity>
  );

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={QI_COLORS.primary} />
        <Text style={styles.loadingText}>{t('trend.loading')}</Text>
      </View>
    );
  }

  // 取不到数据就明确说取不到 (照 QIAnalysisScreen 的三种错误态), ⛔ 不编数据顶上。
  if (error && !points) {
    return (
      <ScrollView
        style={styles.container}
        contentContainerStyle={[
          styles.content,
          { paddingBottom: insets.bottom + 20, alignItems: 'center', justifyContent: 'center', flex: 1 },
        ]}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[QI_COLORS.primary]} />
        }
      >
        <Ionicons name="alert-circle-outline" size={48} color="#999" />
        <Text style={styles.errorText}>{error}</Text>
        <TouchableOpacity style={styles.retryButton} onPress={loadData}>
          <Text style={styles.retryButtonText}>{t('analysis.retry', '重试')}</Text>
        </TouchableOpacity>
      </ScrollView>
    );
  }

  const metricValues = getMetricValues();
  const hasSource = METRIC_HAS_SOURCE[metric];
  const digits = metric === 'count' ? 0 : 1;
  const daysWithData = points ? points.filter((p) => p.inspectionCount > 0).length : 0;
  /** 摘要格子: 没有数据源 / 没有读数 一律显示 "—", 不显示 Infinity 或 NaN */
  const summaryValue = (compute: (vals: number[]) => number): string =>
    hasSource && metricValues.length > 0 ? compute(metricValues).toFixed(digits) : '—';

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 20 }]}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={onRefresh}
          colors={[QI_COLORS.primary]}
        />
      }
    >
      {/* 时间周期选择 */}
      <View style={styles.periodBar}>
        {renderPeriodTab('7d', t('trend.period.week'))}
        {renderPeriodTab('30d', t('trend.period.month'))}
        {renderPeriodTab('90d', t('trend.period.quarter'))}
      </View>

      {/* 指标选择 */}
      <View style={styles.metricBar}>
        {renderMetricTab('passRate', t('analysis.passRate'), 'checkmark-circle-outline')}
        {renderMetricTab('avgScore', t('analysis.avgScore'), 'star-outline')}
        {renderMetricTab('count', t('trend.inspectionCount'), 'layers-outline')}
      </View>

      {/* 折线图 */}
      <View style={styles.chartCard}>
        {renderLineChart()}
      </View>

      {/* 统计摘要 —— 只统计**有质检记录**的那些天; 没有读数显示 "—" 而不是 0 */}
      <View style={styles.summaryCard}>
        <View style={styles.summaryItem}>
          <Text style={styles.summaryLabel}>{t('trend.maxValue')}</Text>
          <Text style={styles.summaryValue}>
            {summaryValue((vals) => Math.max(...vals))}
          </Text>
        </View>
        <View style={styles.summaryDivider} />
        <View style={styles.summaryItem}>
          <Text style={styles.summaryLabel}>{t('trend.minValue')}</Text>
          <Text style={styles.summaryValue}>
            {summaryValue((vals) => Math.min(...vals))}
          </Text>
        </View>
        <View style={styles.summaryDivider} />
        <View style={styles.summaryItem}>
          <Text style={styles.summaryLabel}>{t('trend.avgValue')}</Text>
          <Text style={styles.summaryValue}>
            {summaryValue((vals) => vals.reduce((a, b) => a + b, 0) / vals.length)}
          </Text>
        </View>
      </View>

      {/* 等级趋势 */}
      <View style={styles.card}>
        {renderGradesTrend()}
      </View>

      {/*
        分析说明。

        原来这里写死三句结论 ("合格率整体保持稳定，建议关注波动较大的时段…"),
        与实际数据无关 —— 数据再差也照打"保持稳定"。那是"结论标签硬编码"的形态,
        和 Math.random() 属于同一类问题。现在只陈述从读数算出来的事实。
      */}
      <View style={styles.insightCard}>
        <View style={styles.insightHeader}>
          <Ionicons name="bulb-outline" size={20} color={QI_COLORS.warning} />
          <Text style={styles.insightTitle}>{t('trend.trendAnalysis')}</Text>
        </View>
        <Text style={styles.insightText}>
          {t('trend.coverageNote', {
            total: points?.length ?? 0,
            withData: daysWithData,
          })}
          {hasSource && metricValues.length >= 2
            ? ` ${t('trend.changeNote', {
                first: metricValues[0]?.toFixed(digits) ?? '—',
                last: metricValues[metricValues.length - 1]?.toFixed(digits) ?? '—',
                delta: (
                  (metricValues[metricValues.length - 1] ?? 0) - (metricValues[0] ?? 0)
                ).toFixed(digits),
              })}`
            : ''}
        </Text>
        <Text style={styles.insightSource}>{t('trend.dataSourceNote')}</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  content: {
    padding: 16,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: QI_COLORS.background,
  },
  loadingText: {
    marginTop: 12,
    color: QI_COLORS.textSecondary,
    fontSize: 14,
  },

  // 错误态
  errorText: {
    fontSize: 16,
    color: '#666',
    marginTop: 12,
    textAlign: 'center',
  },
  retryButton: {
    marginTop: 16,
    paddingHorizontal: 24,
    paddingVertical: 10,
    backgroundColor: QI_COLORS.primary,
    borderRadius: 8,
  },
  retryButtonText: {
    color: '#fff',
    fontSize: 14,
  },

  // "该指标暂无数据源"
  noSourceBox: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 28,
    paddingHorizontal: 12,
  },
  noSourceTitle: {
    marginTop: 8,
    fontSize: 14,
    fontWeight: '500',
    color: QI_COLORS.text,
    textAlign: 'center',
  },
  noSourceReason: {
    marginTop: 6,
    fontSize: 12,
    lineHeight: 18,
    color: QI_COLORS.textSecondary,
    textAlign: 'center',
  },

  // 时间周期
  periodBar: {
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 4,
    marginBottom: 12,
  },
  periodTab: {
    flex: 1,
    paddingVertical: 10,
    alignItems: 'center',
    borderRadius: 8,
  },
  periodTabActive: {
    backgroundColor: QI_COLORS.primary,
  },
  periodText: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },
  periodTextActive: {
    color: '#fff',
    fontWeight: '500',
  },

  // 指标选择
  metricBar: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 16,
  },
  metricTab: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: QI_COLORS.card,
    borderRadius: 10,
    paddingVertical: 12,
    gap: 6,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  metricTabActive: {
    borderColor: QI_COLORS.primary,
    backgroundColor: '#E8F5E9',
  },
  metricText: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
  },
  metricTextActive: {
    color: QI_COLORS.primary,
    fontWeight: '500',
  },

  // 图表卡片
  chartCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 16,
    padding: 16,
    marginBottom: 16,
  },
  chartContainer: {},
  chartLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: QI_COLORS.text,
    marginBottom: 16,
  },
  chart: {
    flexDirection: 'row',
    height: 180,
  },
  yAxis: {
    width: 40,
    justifyContent: 'space-between',
    paddingRight: 8,
  },
  yAxisLabel: {
    fontSize: 11,
    color: QI_COLORS.textSecondary,
    textAlign: 'right',
  },
  chartArea: {
    flex: 1,
    position: 'relative',
  },
  gridLines: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 24,
    justifyContent: 'space-between',
  },
  gridLine: {
    height: 1,
    backgroundColor: QI_COLORS.border,
  },
  dataPoints: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 24,
  },
  dataPoint: {
    position: 'absolute',
    width: 10,
    height: 10,
    borderRadius: 5,
    marginLeft: -5,
    marginTop: -5,
  },
  xAxis: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 24,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  xAxisLabel: {
    fontSize: 10,
    color: QI_COLORS.textSecondary,
  },
  chartFootnote: {
    marginTop: 10,
    fontSize: 11,
    color: QI_COLORS.textSecondary,
  },

  // 统计摘要
  summaryCard: {
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  summaryItem: {
    flex: 1,
    alignItems: 'center',
  },
  summaryDivider: {
    width: 1,
    backgroundColor: QI_COLORS.border,
  },
  summaryLabel: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginBottom: 4,
  },
  summaryValue: {
    fontSize: 20,
    fontWeight: '600',
    color: QI_COLORS.text,
  },

  // 等级趋势
  card: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 16,
    padding: 16,
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '500',
    color: QI_COLORS.text,
    marginBottom: 16,
  },
  gradesTrendContainer: {},
  gradesTrendChart: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'flex-end',
    height: 140,
    paddingBottom: 20,
  },
  stackedBar: {
    alignItems: 'center',
    width: 32,
  },
  stackedBarInner: {
    width: 24,
    flexDirection: 'column-reverse',
    borderRadius: 4,
    overflow: 'hidden',
  },
  stackedSegment: {},
  stackedBarLabel: {
    fontSize: 10,
    color: QI_COLORS.textSecondary,
    marginTop: 4,
  },
  legend: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 16,
    marginTop: 8,
  },
  legendItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  legendDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  legendText: {
    fontSize: 12,
    color: QI_COLORS.textSecondary,
  },

  // 分析说明
  insightCard: {
    backgroundColor: '#FFF8E1',
    borderRadius: 12,
    padding: 16,
  },
  insightHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
  },
  insightTitle: {
    fontSize: 14,
    fontWeight: '500',
    color: QI_COLORS.text,
  },
  insightText: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    lineHeight: 20,
  },
  insightSource: {
    marginTop: 6,
    fontSize: 11,
    color: QI_COLORS.textSecondary,
    opacity: 0.8,
  },
});
