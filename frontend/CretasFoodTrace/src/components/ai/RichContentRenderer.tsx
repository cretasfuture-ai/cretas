/**
 * Rich Content Renderer
 *
 * Renders structured AI tool results as tables, detail cards, or confirmation previews
 * instead of raw text. Detects content type from the resultData shape.
 */

import React from 'react';
import { View, Text, StyleSheet, ScrollView } from 'react-native';
import {
  buildDetailRows,
  formatColumnName,
  formatDisplayValue as formatValue,
  getListCellValue,
  getListDisplayKeys,
} from './richListPresentation';

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
  if (!items || items.length === 0) return null;

  // Extract column keys from first item
  const firstItem = items[0] as Record<string, unknown>;
  const displayKeys = getListDisplayKeys(firstItem);

  return (
    <View style={listStyles.container}>
      {/* Header row */}
      <View style={listStyles.headerRow}>
        {displayKeys.map(key => (
          <Text key={key} style={listStyles.headerCell} numberOfLines={1}>
            {formatColumnName(key)}
          </Text>
        ))}
      </View>

      {/* Data rows */}
      <ScrollView style={listStyles.body} nestedScrollEnabled>
        {items.slice(0, 10).map((item, idx) => {
          const row = item as Record<string, unknown>;
          return (
            <View key={idx} style={[listStyles.dataRow, idx % 2 === 0 && listStyles.evenRow]}>
              {displayKeys.map(key => (
                <Text key={key} style={listStyles.dataCell} numberOfLines={2}>
                  {formatValue(getListCellValue(row, key))}
                </Text>
              ))}
            </View>
          );
        })}
      </ScrollView>

      {/* Pagination footer */}
      {pagination.totalElements !== undefined && (
        <View style={listStyles.footer}>
          <Text style={listStyles.footerText}>
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
  // 只给一线看得懂的行: 中文标签、中文状态、数量带单位, 内部编号不显示 (见 buildDetailRows)
  const rows = buildDetailRows(detail);
  if (rows.length === 0) return null;

  return (
    <View style={detailStyles.container} testID="ai-detail-card">
      {rows.map(row => (
        <View key={row.key} style={detailStyles.row}>
          <Text style={detailStyles.label}>{row.label}</Text>
          <Text style={detailStyles.value} numberOfLines={2}>{row.value}</Text>
        </View>
      ))}
    </View>
  );
}

// ==================== Stats Renderer ====================

function StatsRenderer({ stats }: { stats: Record<string, unknown> }) {
  const numericEntries = Object.entries(stats)
    .filter(([, v]) => typeof v === 'number' || typeof v === 'string')
    .slice(0, 8);

  return (
    <View style={statsStyles.container}>
      {numericEntries.map(([key, value]) => (
        <View key={key} style={statsStyles.card}>
          <Text style={statsStyles.value}>{formatValue(value)}</Text>
          <Text style={statsStyles.label}>{formatColumnName(key)}</Text>
        </View>
      ))}
    </View>
  );
}

// ==================== Helpers ====================

// ==================== Styles ====================

const listStyles = StyleSheet.create({
  container: { marginTop: 8, borderRadius: 8, overflow: 'hidden', borderWidth: 1, borderColor: '#e2e8f0' },
  headerRow: { flexDirection: 'row', backgroundColor: '#f1f5f9', paddingVertical: 8, paddingHorizontal: 4 },
  headerCell: { flex: 1, fontSize: 11, fontWeight: '600', color: '#475569', paddingHorizontal: 4 },
  body: { maxHeight: 240 },
  dataRow: { flexDirection: 'row', paddingVertical: 6, paddingHorizontal: 4, borderTopWidth: 0.5, borderColor: '#e2e8f0' },
  evenRow: { backgroundColor: '#f8fafc' },
  dataCell: { flex: 1, fontSize: 12, color: '#334155', paddingHorizontal: 4 },
  footer: { backgroundColor: '#f1f5f9', paddingVertical: 6, paddingHorizontal: 8 },
  footerText: { fontSize: 11, color: '#64748b', textAlign: 'center' },
});

const detailStyles = StyleSheet.create({
  container: { marginTop: 8, backgroundColor: '#f8fafc', borderRadius: 8, padding: 10, borderWidth: 1, borderColor: '#e2e8f0' },
  row: { flexDirection: 'row', paddingVertical: 4, borderBottomWidth: 0.5, borderColor: '#e2e8f0' },
  label: { width: 90, fontSize: 12, color: '#64748b', fontWeight: '500' },
  value: { flex: 1, fontSize: 12, color: '#1e293b' },
});

const statsStyles = StyleSheet.create({
  container: { marginTop: 8, flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  card: { backgroundColor: '#f0f4ff', borderRadius: 8, padding: 10, minWidth: 80, alignItems: 'center', borderWidth: 1, borderColor: '#dbeafe' },
  value: { fontSize: 16, fontWeight: '700', color: '#3b52cc' },
  label: { fontSize: 10, color: '#64748b', marginTop: 2 },
});
