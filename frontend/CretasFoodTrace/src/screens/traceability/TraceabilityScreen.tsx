/**
 * TraceabilityScreen — 溯源查询入口 (「找一批」)
 *
 * 2026-09-15 评审 (P1): 首页瓷砖画的是 `qrcode-scan`, 点进来却是一个 TextInput, 旁边的
 * `barcode-scan` 图标没有 onPress —— 承诺扫码、交付键盘, 老板要手打 `SL-DEMO-BATCH-V1`。
 * 查到之后又先显示一张摘要卡 (「6 原材料批次」统计块), 还要再点「查看完整溯源链路」才到详情;
 * 摘要和详情横幅「本批来自 6 头牛」说的是同一件事, 多一跳。
 *
 * 重做后本屏只做一件事: 拿到一个码, **直接**进 `TraceabilityDetail`。
 *  · 扫码 (主动作, 复用 `BarcodeScannerModal`) 或手输, 两条路汇合到 `goToDetail()`
 *  · 摘要卡 / 第三份质量状态 switch / `'-'` 占位 / 硬编码色 全部删掉 —— 详情屏才是唯一的展示处
 *  · 「最近查过」: AsyncStorage 存最近 5 个码, 没有就整段不渲染 (⛔ 不渲染一个空标题)
 *
 * 参数形状按 `TraceabilityDetailScreen` 从 `route.params` 读的来:
 *   `{ batchNumber: string; traceCode?: string }` —— 它拿 batchNumber 打 `/traceability/full/{batchNumber}`,
 *   traceCode 只用来在顶卡把「扫入码」和「系统码」并排摆出来 (两者不同时)。
 *
 * ⚠️ 标签二维码里印的**不是**批次号而是溯源码 (`LabelServiceImpl.scanLabel` 的注释: 打印机印进
 *    二维码的是 traceCode, 形如 `TRACE-SL-DEMO-BATCH-V1-SEED01`)。`parseTraceInput` 把内嵌格式
 *    `TRACE-{batchNumber}-{suffix}` 拆出批次号 (与后端 `extractBatchNumberFromTraceCode` 同一条规则);
 *    编码规则格式 (`TR-{工厂}-{日期}-{序号}`) **不内嵌批次号**, 本屏不做反查 —— 原样当批次号传过去,
 *    详情屏会明说「未找到该批次」, ⛔ 不在入口屏猜一个。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Appbar, Button, Divider, List, Text, TextInput, useTheme } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import AsyncStorage from '@react-native-async-storage/async-storage';

import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
import type { AppTheme } from '../../theme';
import type { ProcessingStackParamList } from '../../types/navigation';

type Nav = NativeStackNavigationProp<ProcessingStackParamList, 'Traceability'>;

export const TRACE_ENTRY_TEST_IDS = {
  screen: 'trace-entry',
  scan: 'trace-entry-scan',
  input: 'trace-entry-input',
  go: 'trace-entry-go',
  recentSection: 'trace-entry-recent',
  recentClear: 'trace-entry-recent-clear',
  recentRow: (code: string) => `trace-entry-recent-${code}`,
} as const;

/** AsyncStorage 键: 最近查过的码 (JSON string[], 最新在前, 最多 RECENT_LIMIT 个)。 */
export const RECENT_STORAGE_KEY = 'traceability.recentCodes';
export const RECENT_LIMIT = 5;

export interface TraceDetailParams {
  batchNumber: string;
  traceCode?: string;
}

/**
 * 把扫到 / 手输的一个串变成详情屏的参数。
 *  · `TRACE-{batchNumber}-{suffix}` (内嵌格式) ⇒ 拆出 batchNumber, 原串作 traceCode
 *  · 其它 ⇒ 整个串就是 batchNumber
 * 空串 / 纯空白 ⇒ null (调用方不导航)。
 */
export function parseTraceInput(raw: string): TraceDetailParams | null {
  const code = raw.trim();
  if (!code) {
    return null;
  }
  if (code.startsWith('TRACE-')) {
    const remaining = code.slice('TRACE-'.length);
    const lastDash = remaining.lastIndexOf('-');
    if (lastDash > 0) {
      return { batchNumber: remaining.slice(0, lastDash), traceCode: code };
    }
  }
  return { batchNumber: code };
}

/** 新码放最前、去重、截到 RECENT_LIMIT。纯函数, 便于对照。 */
export function pushRecent(existing: readonly string[], code: string): string[] {
  return [code, ...existing.filter((c) => c !== code)].slice(0, RECENT_LIMIT);
}

async function readRecent(): Promise<string[]> {
  const raw = await AsyncStorage.getItem(RECENT_STORAGE_KEY);
  if (!raw) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((c): c is string => typeof c === 'string') : [];
  } catch {
    return [];
  }
}

export default function TraceabilityScreen() {
  const theme = useTheme<AppTheme>();
  const navigation = useNavigation<Nav>();
  const { t } = useTranslation('processing');
  const { spacing, borderRadius } = theme.custom;

  const [input, setInput] = useState('');
  const [scannerVisible, setScannerVisible] = useState(false);
  const [recent, setRecent] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    readRecent().then((codes) => {
      if (!cancelled) setRecent(codes);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  /** 扫码与手输在这里汇合。没码不导航 (阴性对照就钉在这一句)。 */
  const goToDetail = useCallback(
    (raw: string) => {
      const params = parseTraceInput(raw);
      if (!params) return;
      const next = pushRecent(recent, raw.trim());
      setRecent(next);
      // 写失败不阻塞导航 —— 「最近查过」是便利, 不是溯源本身。
      AsyncStorage.setItem(RECENT_STORAGE_KEY, JSON.stringify(next)).catch(() => undefined);
      navigation.navigate('TraceabilityDetail', params);
    },
    [navigation, recent]
  );

  const handleScan = useCallback(
    (data: string) => {
      setScannerVisible(false);
      goToDetail(data);
    },
    [goToDetail]
  );

  const clearRecent = useCallback(() => {
    setRecent([]);
    AsyncStorage.removeItem(RECENT_STORAGE_KEY).catch(() => undefined);
  }, []);

  const canGo = parseTraceInput(input) !== null;

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.background }]} testID={TRACE_ENTRY_TEST_IDS.screen}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('traceability.entry.title')} />
      </Appbar.Header>

      <ScrollView
        contentContainerStyle={{ padding: spacing.l, paddingBottom: spacing.xxl, gap: spacing.l }}
        keyboardShouldPersistTaps="handled"
      >
        {/* 主动作: 扫码。一个屏一个主色按钮。 */}
        <View style={{ gap: spacing.s }}>
          <Button
            mode="contained"
            icon="qrcode-scan"
            onPress={() => setScannerVisible(true)}
            contentStyle={styles.scanButtonContent}
            style={{ borderRadius: borderRadius.m }}
            accessibilityLabel={t('traceability.entry.scan')}
            testID={TRACE_ENTRY_TEST_IDS.scan}
          >
            {t('traceability.entry.scan')}
          </Button>
          <Text variant="bodySmall" style={[styles.centerText, { color: theme.colors.onSurfaceVariant }]}>
            {t('traceability.entry.scanHint')}
          </Text>
        </View>

        <View style={[styles.dividerRow, { gap: spacing.m }]}>
          <Divider style={styles.dividerLine} />
          <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }}>
            {t('traceability.entry.orManual')}
          </Text>
          <Divider style={styles.dividerLine} />
        </View>

        {/* 手输: 批次号或溯源码, 回车 / 按钮都直接进详情。 */}
        <View style={{ gap: spacing.s }}>
          <TextInput
            mode="outlined"
            label={t('traceability.entry.inputLabel')}
            placeholder={t('traceability.entry.inputPlaceholder')}
            value={input}
            onChangeText={setInput}
            onSubmitEditing={() => goToDetail(input)}
            autoCapitalize="characters"
            autoCorrect={false}
            returnKeyType="search"
            accessibilityLabel={t('traceability.entry.inputLabel')}
            testID={TRACE_ENTRY_TEST_IDS.input}
          />
          <Button
            mode="contained-tonal"
            icon="arrow-right"
            onPress={() => goToDetail(input)}
            disabled={!canGo}
            contentStyle={styles.goButtonContent}
            style={{ borderRadius: borderRadius.m }}
            accessibilityLabel={t('traceability.entry.go')}
            testID={TRACE_ENTRY_TEST_IDS.go}
          >
            {t('traceability.entry.go')}
          </Button>
        </View>

        {/* 最近查过: 一个都没有就整段不渲染。 */}
        {recent.length > 0 && (
          <View
            style={{ backgroundColor: theme.colors.surface, borderRadius: borderRadius.m, overflow: 'hidden' }}
            testID={TRACE_ENTRY_TEST_IDS.recentSection}
          >
            <View style={[styles.recentHeader, { paddingLeft: spacing.l, paddingRight: spacing.xs }]}>
              <Text variant="titleSmall" style={[styles.recentTitle, { color: theme.colors.onSurfaceVariant }]}>
                {t('traceability.entry.recentTitle')}
              </Text>
              <Button
                mode="text"
                compact
                onPress={clearRecent}
                accessibilityLabel={t('traceability.entry.recentClear')}
                testID={TRACE_ENTRY_TEST_IDS.recentClear}
              >
                {t('traceability.entry.recentClear')}
              </Button>
            </View>
            {recent.map((code, index) => (
              <React.Fragment key={code}>
                {index > 0 && <Divider />}
                <List.Item
                  title={code}
                  titleStyle={[styles.mono, { color: theme.colors.onSurface }]}
                  onPress={() => goToDetail(code)}
                  left={(props) => <List.Icon {...props} icon="history" color={theme.colors.onSurfaceVariant} />}
                  right={(props) => <List.Icon {...props} icon="chevron-right" />}
                  style={[styles.recentRow, { paddingHorizontal: spacing.s }]}
                  accessibilityRole="button"
                  accessibilityLabel={code}
                  testID={TRACE_ENTRY_TEST_IDS.recentRow(code)}
                />
              </React.Fragment>
            ))}
          </View>
        )}
      </ScrollView>

      <BarcodeScannerModal
        visible={scannerVisible}
        onClose={() => setScannerVisible(false)}
        onScan={handleScan}
        title={t('traceability.entry.scannerTitle')}
      />
    </View>
  );
}

// ⚠️ 只放布局: 颜色 / 字号 / 间距全部走 useTheme() 与 Paper 的 variant,
//    `gates/demoScreensUseThemeTokens` 守着本文件里不出现色值与 fontSize<14。
const styles = StyleSheet.create({
  container: { flex: 1 },
  scanButtonContent: { minHeight: 64 },
  goButtonContent: { minHeight: 48 },
  centerText: { textAlign: 'center' },
  dividerRow: { flexDirection: 'row', alignItems: 'center' },
  dividerLine: { flex: 1 },
  recentHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 48 },
  recentTitle: { flex: 1 },
  recentRow: { minHeight: 48 },
  mono: { fontFamily: 'monospace' },
});
