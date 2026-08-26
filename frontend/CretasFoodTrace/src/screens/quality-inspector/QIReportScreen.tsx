/**
 * 质检报告导出 —— 明示不可用
 * Quality Inspector - Report Export (not available yet)
 *
 * APP-CONTRACT-006 (2026-08-25): 这个屏原来是一个**从来没有成功过**的表单。
 * 三层缺陷互相掩护:
 *
 *   1. 表层  成功文案写「报告生成成功, 已保存到文件」, 而「查看报告」按钮的实现是
 *            `console.log('Open report:', result)`。后端返回的 {url, filename}
 *            没有任何消费者 —— 不下载、不打开、不分享。
 *            另外 Alert 标题用的是 `clock.clockSuccess` / `clock.clockFailed` /
 *            `clock.alert`, 而 quality.json 里有**两个** "clock" 块 (206 行 / 486 行),
 *            JSON 重复键后者胜出, 这三个键在生效的那一块里根本不存在 ⇒ i18next
 *            回退成打印键名本身, 用户看到的标题字面就是 `clock.clockSuccess`。
 *
 *   2. 中层  它调的 `POST /api/mobile/{factoryId}/reports/quality/generate`
 *            后端全仓零命中。ReportController 的类映射是 `.../reports`, 其下唯一的
 *            @PostMapping 是 `/custom` ⇒ 点「生成报告」必然 404。
 *
 *   3. 底层  ReportController 确实有 GET /export/pdf 和 /export/excel, 但
 *            ReportExportServiceImpl 里那两个流式方法是**桩**: 返回 HTTP 200 +
 *            `filename=report.pdf`, 内容是 "PDF export not yet implemented"。
 *            把这个屏指过去 = 用户下载到一个打不开的 PDF, 而屏幕报成功。
 *            ⛔ 404 是诚实的失败; 200 + 假 PDF 是无声的失败。所以没有那样接。
 *
 * 为什么不直接把后端桩换成真实现 (同文件 :104 的 exportReportToPDF 是真的, iText):
 *   - `generateQualityPdfContent` 完全忽略 parameters, 用的是 countByFactoryId
 *     (**全时段**计数) ⇒「日报」里会是开厂至今的总数, 换一种假数据而已;
 *   - `exportReportToPDF` 的签名里**没有** maskPrice, 而 default/dashboard 分支
 *     会打印「总价值 … 元」⇒ 接上等于给仓管角色新开一个金额泄漏口;
 *     BinaryExportRbacTest 正钉着「桩返回 placeholder, 今天不漏」这个前提。
 *   - 屏幕原有的 6 个内容选项 (等级分布/分类评分/问题分析/趋势/批次明细) 没有
 *     任何一个后端分支实现 ⇒ 勾选了也不会出现在报告里。
 *
 * ⇒ 在后端真能出文件之前, 这个屏**不提供表单**, 只如实说明状态, 并把用户指向
 *   两个有真实数据的屏。表单本身也是一种承诺: 让人选格式、选内容、看预览,
 *   等于告诉他这些选择会体现在产物里。
 */

import React from 'react';
import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;

export default function QIReportScreen() {
  const { t } = useTranslation('quality');
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();

  return (
    <View style={styles.container}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 24 }]}
      >
        {/* 状态说明 —— 不提供任何"生成"入口 */}
        <View style={styles.noticeCard}>
          <Ionicons name="construct-outline" size={44} color={QI_COLORS.warning} />
          <Text style={styles.noticeTitle}>{t('report.unavailableTitle')}</Text>
          <Text style={styles.noticeBody}>{t('report.unavailableBody')}</Text>
        </View>

        {/* 指向真实有数据的地方 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('report.alternativeTitle')}</Text>

          <TouchableOpacity
            style={styles.linkCard}
            onPress={() => navigation.navigate('QIAnalysis')}
          >
            <Ionicons name="stats-chart" size={22} color={QI_COLORS.primary} />
            <View style={styles.linkInfo}>
              <Text style={styles.linkLabel}>{t('report.alternativeAnalysis')}</Text>
              <Text style={styles.linkDesc}>{t('report.alternativeAnalysisDesc')}</Text>
            </View>
            <Ionicons name="chevron-forward" size={18} color={QI_COLORS.disabled} />
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.linkCard}
            onPress={() => navigation.navigate('QITrend')}
          >
            <Ionicons name="trending-up" size={22} color={QI_COLORS.primary} />
            <View style={styles.linkInfo}>
              <Text style={styles.linkLabel}>{t('report.alternativeTrend')}</Text>
              <Text style={styles.linkDesc}>{t('report.alternativeTrendDesc')}</Text>
            </View>
            <Ionicons name="chevron-forward" size={18} color={QI_COLORS.disabled} />
          </TouchableOpacity>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  scrollView: {
    flex: 1,
  },
  content: {
    padding: 16,
  },

  // 状态说明卡
  noticeCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 24,
    alignItems: 'center',
    marginBottom: 24,
  },
  noticeTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginTop: 12,
    textAlign: 'center',
  },
  noticeBody: {
    fontSize: 14,
    lineHeight: 21,
    color: QI_COLORS.textSecondary,
    marginTop: 8,
    textAlign: 'center',
  },

  // 替代入口
  section: {
    marginBottom: 24,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginBottom: 12,
  },
  linkCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    gap: 12,
  },
  linkInfo: {
    flex: 1,
  },
  linkLabel: {
    fontSize: 15,
    fontWeight: '500',
    color: QI_COLORS.text,
  },
  linkDesc: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginTop: 2,
  },
});
