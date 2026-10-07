/**
 * Rich Content Renderer
 *
 * Renders structured AI tool results as tables, detail cards, or confirmation previews
 * instead of raw text. Detects content type from the resultData shape.
 */

import React from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';
import { useTheme } from 'react-native-paper';
import type { AppTheme } from '../../theme';
import {
  buildDetailRows,
  formatColumnName,
  formatDisplayValue as formatValue,
  getListCellValue,
  getListDisplayKeys,
} from './richListPresentation';
import { chatFontFamily } from './chatTypography';

export interface RichData {
  /** Content type hint from backend (optional) */
  dataType?: 'LIST' | 'DETAIL' | 'CONFIRM' | 'STATS' | 'PAGINATION';
  /** The actual data payload */
  content?: unknown[] | Record<string, unknown>;
  /** Pagination info */
  totalElements?: number;
  totalPages?: number;
  currentPage?: number;
  hasMore?: boolean;
}

/**
 * Detect the best rendering mode from raw resultData
 */
export function detectRichData(resultData: Record<string, unknown> | undefined): RichData | undefined {
  if (!resultData) return undefined;

  // List/table result (from buildPageResult)
  if (Array.isArray(resultData.content) && resultData.content.length > 0) {
    return {
      dataType: 'LIST',
      content: resultData.content as unknown[],
      totalElements: resultData.totalElements as number,
      totalPages: resultData.totalPages as number,
      currentPage: resultData.currentPage as number,
      hasMore: resultData.hasMore as boolean,
    };
  }

  // Confirmation/preview
  if (resultData.status === 'PREVIEW' || resultData.status === 'PAGINATION_READY') {
    return { dataType: 'CONFIRM', content: resultData };
  }

  // Statistics result
  if (resultData.totalOrders !== undefined || resultData.totalAmount !== undefined ||
      resultData.statusCounts !== undefined) {
    return { dataType: 'STATS', content: resultData };
  }

  // Single detail result with identifiable fields
  if (resultData.orderId || resultData.planId || resultData.batchId ||
      resultData.transferId || resultData.returnOrderId) {
    return { dataType: 'DETAIL', content: resultData };
  }

  return undefined;
}

interface Props {
  data: RichData;
}

export function RichContentRenderer({ data }: Props) {
  switch (data.dataType) {
    case 'LIST':
      return <ListRenderer items={data.content as unknown[]} pagination={data} />;
    case 'DETAIL':
      return <DetailRenderer detail={data.content as Record<string, unknown>} />;
    case 'STATS':
      return <StatsRenderer stats={data.content as Record<string, unknown>} />;
    default:
      return null;
  }
}

// ==================== List/Table Renderer ====================

function ListRenderer({ items, pagination }: { items: unknown[]; pagination: RichData }) {
  const theme = useTheme<AppTheme>();
  if (!items || items.length === 0) return null;

  // Extract column keys from first item
  const firstItem = items[0] as Record<string, unknown>;
  const displayKeys = getListDisplayKeys(firstItem);

  return (
    <View style={[listStyles.container, { borderColor: theme.colors.outlineVariant }]} testID="ai-list-card">
      <ScrollView style={listStyles.body} nestedScrollEnabled>
        {items.slice(0, 10).map((item, idx) => {
          const row = item as Record<string, unknown>;
          return (
            <View key={idx} style={[listStyles.dataRow, { borderColor: theme.colors.outlineVariant }, idx % 2 === 0 && { backgroundColor: theme.colors.surfaceVariant }]}>
              {displayKeys.map(key => (
                <View key={key} style={listStyles.dataField}>
                  <Text style={[listStyles.headerCell, { color: theme.colors.onSurfaceVariant }, chatFontFamily]}>{formatColumnName(key)}</Text>
                  <Text style={[listStyles.dataCell, { color: theme.colors.onSurface }, chatFontFamily]} numberOfLines={2}>
                    {formatValue(getListCellValue(row, key), key)}
                  </Text>
                </View>
              ))}
            </View>
          );
        })}
      </ScrollView>

      {/* Pagination footer */}
      {pagination.totalElements !== undefined && (
        <View style={listStyles.footer}>
          <Text style={[listStyles.footerText, { color: theme.colors.onSurfaceVariant }, chatFontFamily]}>
            共 {pagination.totalElements} 条 · 第 {pagination.currentPage}/{pagination.totalPages} 页
            {pagination.hasMore ? ' · 说"下一页"查看更多' : ''}
          </Text>
        </View>
      )}
    </View>
  );
}

// ==================== Detail Card Renderer ====================

function DetailRenderer({ detail }: { detail: Record<string, unknown> }) {
  const theme = useTheme<AppTheme>();
  // 只给一线看得懂的行: 中文标签、中文状态、数量带单位, 内部编号不显示 (见 buildDetailRows)
  const rows = buildDetailRows(detail);
  if (rows.length === 0) return null;

  return (
    <View style={[detailStyles.container, { backgroundColor: theme.colors.surfaceVariant, borderColor: theme.colors.outlineVariant }]} testID="ai-detail-card">
      {rows.map(row => (
        <View key={row.key} style={[detailStyles.row, { borderColor: theme.colors.outlineVariant }]}>
          <Text style={[detailStyles.label, { color: theme.colors.onSurfaceVariant }, chatFontFamily]}>{row.label}</Text>
          <Text style={[detailStyles.value, { color: theme.colors.onSurface }, chatFontFamily]} numberOfLines={2}>{row.value}</Text>
        </View>
      ))}
    </View>
  );
}

// ==================== Stats Renderer ====================

function StatsRenderer({ stats }: { stats: Record<string, unknown> }) {
  const theme = useTheme<AppTheme>();
  const numericEntries = Object.entries(stats)
    .filter(([key, v]) => (v === null || typeof v === 'number' || typeof v === 'string') && formatColumnName(key) !== '')
    .slice(0, 8);

  return (
    <View style={statsStyles.container}>
      {numericEntries.map(([key, value]) => (
        <View key={key} style={[statsStyles.card, { backgroundColor: theme.colors.primaryContainer, borderColor: theme.colors.outlineVariant }]}>
          <Text style={[statsStyles.value, { color: theme.colors.primary }, chatFontFamily]}>{formatValue(value, key)}</Text>
          <Text style={[statsStyles.label, { color: theme.colors.onSurfaceVariant }, chatFontFamily]}>{formatColumnName(key)}</Text>
        </View>
      ))}
    </View>
  );
}

// ==================== Helpers ====================

// ==================== Styles ====================

const listStyles = StyleSheet.create({
  container: { marginTop: 8, borderRadius: 8, overflow: 'hidden', borderWidth: 1 },
  headerCell: { fontSize: 13, fontWeight: '600', paddingHorizontal: 4 },
  body: { maxHeight: 240 },
  dataRow: { paddingVertical: 8, paddingHorizontal: 8, borderTopWidth: 0.5 },
  dataField: { flexDirection: 'row', alignItems: 'flex-start', paddingVertical: 3 },
  dataCell: { flex: 1, fontSize: 13, paddingHorizontal: 4 },
  footer: { paddingVertical: 8, paddingHorizontal: 8 },
  footerText: { fontSize: 13, textAlign: 'center' },
});

const detailStyles = StyleSheet.create({
  container: { marginTop: 8, borderRadius: 8, padding: 10, borderWidth: 1 },
  row: { flexDirection: 'row', paddingVertical: 4, borderBottomWidth: 0.5 },
  label: { width: 90, fontSize: 13, fontWeight: '500' },
  value: { flex: 1, fontSize: 13 },
});

const statsStyles = StyleSheet.create({
  container: { marginTop: 8, flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  card: { borderRadius: 8, padding: 10, minWidth: 80, alignItems: 'center', borderWidth: 1 },
  value: { fontSize: 16, fontWeight: '700' },
  label: { fontSize: 13, marginTop: 2 },
});
