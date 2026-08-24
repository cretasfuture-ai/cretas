/**
 * 质检结果页面
 * Quality Inspector - Inspection Result Screen
 */

import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';
import { QualityRecord } from '../../types/qualityInspector';
import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
import { DispositionSuggestion } from '../../components/quality';
import { qualityRecordDetailRoute } from '../../navigation/qualityInspectorRoutes';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type RouteProps = RouteProp<QualityInspectorStackParamList, 'QIResult'>;

export default function QIResultScreen() {
  const { t } = useTranslation('quality');
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteProps>();
  const insets = useSafeAreaInsets();
  const { recordId, passed } = route.params;
  const [record, setRecord] = useState<QualityRecord | null>(null);

  useEffect(() => {
    qualityInspectorApi.getRecordDetail(recordId)
      .then(setRecord)
      .catch((error) => console.error('Load inspection result detail:', error));
  }, [recordId]);

  const isConditional = record?.result === 'CONDITIONAL';
  const isFullyPassed = record ? record.passed : passed;
  const canDispose = record?.result === 'FAIL' || record?.result === 'CONDITIONAL';
  const resultIcon = isConditional ? 'warning' : isFullyPassed ? 'checkmark-circle' : 'close-circle';
  const resultTitle = isConditional
    ? '有条件合格，待处置'
    : isFullyPassed
      ? t('result.inspectionPassed')
      : t('result.inspectionFailed');
  const resultDescription = isConditional
    ? '该批次存在不合格样本，完成处置审批前不得进入包装或出货流程'
    : isFullyPassed
      ? t('result.passedDescription')
      : t('result.failedDescription');
  const resultLabel = isConditional
    ? '有条件合格'
    : isFullyPassed
      ? t('result.qualified')
      : t('result.unqualified');

  const handleViewDetail = () => {
    navigation.navigate('QIRecordsTab', qualityRecordDetailRoute(recordId));
  };

  const handleContinue = () => {
    navigation.navigate('QIInspectTab', { screen: 'QIInspectList' });
  };

  const handleGoHome = () => {
    navigation.navigate('QIHomeTab', { screen: 'QIHome' });
  };

  return (
    <View style={styles.container}>
      <ScrollView
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 100 }]}
      >
        {/* 结果图标 */}
        <View style={[
          styles.resultIcon,
          isConditional
            ? styles.resultIconConditional
            : isFullyPassed
              ? styles.resultIconPass
              : styles.resultIconFail,
        ]}>
          <Ionicons
            name={resultIcon}
            size={80}
            color="#fff"
          />
        </View>

        {/* 结果标题 */}
        <Text style={[
          styles.resultTitle,
          isConditional
            ? styles.titleConditional
            : isFullyPassed
              ? styles.titlePass
              : styles.titleFail,
        ]}>
          {resultTitle}
        </Text>

        <Text style={styles.resultSubtitle}>
          {resultDescription}
        </Text>

        {/* 结果详情卡片 */}
        <View style={styles.detailCard}>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>{t('result.recordNumber')}</Text>
            <Text style={styles.detailValue}>{recordId}</Text>
          </View>
          <View style={styles.divider} />
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>{t('result.inspectionTime')}</Text>
            <Text style={styles.detailValue}>{new Date().toLocaleString('zh-CN')}</Text>
          </View>
          <View style={styles.divider} />
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>{t('result.inspectionResult')}</Text>
            <View style={[
              styles.statusBadge,
              isConditional
                ? styles.badgeConditional
                : isFullyPassed
                  ? styles.badgePass
                  : styles.badgeFail,
            ]}>
              <Text style={styles.statusText}>{resultLabel}</Text>
            </View>
          </View>
        </View>

        {/* 下一步操作提示 */}
        {isFullyPassed ? (
          <View style={styles.nextStepCard}>
            <Ionicons name="arrow-forward-circle" size={24} color={QI_COLORS.secondary} />
            <View style={styles.nextStepText}>
              <Text style={styles.nextStepTitle}>{t('result.nextStep')}</Text>
              <Text style={styles.nextStepDesc}>{t('result.productWillEnterPackaging')}</Text>
            </View>
          </View>
        ) : (
          <View style={[styles.nextStepCard, styles.nextStepCardWarning]}>
            <Ionicons name="warning" size={24} color={QI_COLORS.warning} />
            <View style={styles.nextStepText}>
              <Text style={styles.nextStepTitle}>{t('result.needsHandling')}</Text>
              <Text style={styles.nextStepDesc}>
                {isConditional
                  ? '请提交隔离、返工、报废或有条件放行处置，并等待审批'
                  : t('result.pleaseIsolateBatch')}
              </Text>
            </View>
          </View>
        )}

        {canDispose && record && Number.isFinite(Number(record.batchId)) && (
          <View style={styles.dispositionContainer}>
            <DispositionSuggestion
              batchId={Number(record.batchId)}
              inspectionId={recordId}
              qualityScore={record.scoreDetailsRecorded ? record.totalScore : record.passRate}
            />
          </View>
        )}

        {/* 查看详情按钮 */}
        <TouchableOpacity style={styles.viewDetailBtn} onPress={handleViewDetail}>
          <Ionicons name="document-text-outline" size={20} color={QI_COLORS.primary} />
          <Text style={styles.viewDetailText}>{t('result.viewInspectionDetail')}</Text>
        </TouchableOpacity>
      </ScrollView>

      {/* 底部操作 */}
      <View style={[styles.bottomBar, { paddingBottom: insets.bottom + 16 }]}>
        <TouchableOpacity style={styles.homeBtn} onPress={handleGoHome}>
          <Ionicons name="home-outline" size={20} color={QI_COLORS.text} />
          <Text style={styles.homeBtnText}>{t('result.returnHome')}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.continueBtn} onPress={handleContinue}>
          <Text style={styles.continueBtnText}>{t('result.continueInspection')}</Text>
          <Ionicons name="arrow-forward" size={18} color="#fff" />
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  content: {
    padding: 24,
    alignItems: 'center',
  },

  // 结果图标
  resultIcon: {
    width: 120,
    height: 120,
    borderRadius: 60,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 20,
    marginBottom: 24,
  },
  resultIconPass: {
    backgroundColor: QI_COLORS.success,
  },
  resultIconFail: {
    backgroundColor: QI_COLORS.danger,
  },
  resultIconConditional: {
    backgroundColor: QI_COLORS.warning,
  },

  // 结果标题
  resultTitle: {
    fontSize: 28,
    fontWeight: '700',
    marginBottom: 12,
  },
  titlePass: {
    color: QI_COLORS.success,
  },
  titleFail: {
    color: QI_COLORS.danger,
  },
  titleConditional: {
    color: '#B26A00',
  },
  resultSubtitle: {
    fontSize: 15,
    color: QI_COLORS.textSecondary,
    textAlign: 'center',
    lineHeight: 22,
    marginBottom: 32,
    paddingHorizontal: 20,
  },

  // 详情卡片
  detailCard: {
    width: '100%',
    backgroundColor: QI_COLORS.card,
    borderRadius: 16,
    padding: 20,
    marginBottom: 20,
  },
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 12,
  },
  detailLabel: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },
  detailValue: {
    fontSize: 14,
    color: QI_COLORS.text,
    fontWeight: '500',
  },
  divider: {
    height: 1,
    backgroundColor: QI_COLORS.border,
  },
  statusBadge: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
  },
  badgePass: {
    backgroundColor: '#E8F5E9',
  },
  badgeFail: {
    backgroundColor: '#FFEBEE',
  },
  badgeConditional: {
    backgroundColor: '#FFF3CD',
  },
  statusText: {
    fontSize: 14,
    fontWeight: '600',
  },

  // 下一步
  nextStepCard: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#E3F2FD',
    borderRadius: 12,
    padding: 16,
    gap: 12,
    marginBottom: 24,
  },
  nextStepCardWarning: {
    backgroundColor: '#FFF8E1',
  },
  nextStepText: {
    flex: 1,
  },
  nextStepTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginBottom: 2,
  },
  nextStepDesc: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
  },

  // 查看详情
  viewDetailBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingVertical: 12,
  },
  dispositionContainer: {
    width: '100%',
  },
  viewDetailText: {
    fontSize: 15,
    color: QI_COLORS.primary,
  },

  // 底部栏
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderTopWidth: 1,
    borderTopColor: QI_COLORS.border,
    paddingHorizontal: 16,
    paddingTop: 16,
    gap: 12,
  },
  homeBtn: {
    flex: 1,
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: QI_COLORS.background,
    borderRadius: 12,
    paddingVertical: 14,
    gap: 6,
  },
  homeBtnText: {
    fontSize: 15,
    color: QI_COLORS.text,
    fontWeight: '500',
  },
  continueBtn: {
    flex: 1,
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: QI_COLORS.primary,
    borderRadius: 12,
    paddingVertical: 14,
    gap: 6,
  },
  continueBtnText: {
    fontSize: 15,
    color: '#fff',
    fontWeight: '600',
  },
});
