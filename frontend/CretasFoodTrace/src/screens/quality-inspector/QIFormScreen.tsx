/**
 * 质检表单页面
 * Quality Inspector - Inspection Form Screen
 */

import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  Alert,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import {
  QI_COLORS,
  QualityInspectorStackParamList,
  QIBatch,
  ScoreItem,
  QualityGrade,
  GRADE_COLORS,
  GRADE_LABELS,
  calculateGrade,
  calculateTotalScore,
} from '../../types/qualityInspector';
import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
import { useAuthStore } from '../../store/authStore';
import { attachmentApi } from '../../services/api/attachmentApi';
import {
  checkQualityInspectionSubmission,
  deriveQualityInspectionCounts,
  deriveQualityInspectionResult,
  getQualityInspectionSubmitErrorMessage,
} from './qualityInspectionSubmission.logic';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type RouteProps = RouteProp<QualityInspectorStackParamList, 'QIForm'>;

interface CheckItem {
  id: string;
  name: string;
  maxScore: number;
  options: string[];
  color: string;
}

/**
 * 表单里的评分草稿。
 *
 * 与 `ScoreItem` 的区别只有一个, 但很关键: `score` 可以是 `null` = **还没评**。
 * `ScoreItem.score` 是 `number`, 用它当草稿类型就必须先塞一个数字进去,
 * 而任何一个塞进去的数字都会被下游当成检验员的判断读。
 */
interface DraftScoreItem {
  score: number | null;
  notes: string[];
}

export default function QIFormScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteProps>();
  const insets = useSafeAreaInsets();
  const { t } = useTranslation('quality');
  const { user } = useAuthStore();
  const { batchId, batchNumber } = route.params;

  const CHECK_ITEMS: CheckItem[] = [
    {
      id: 'appearance',
      name: t('form.checkItems.appearance'),
      maxScore: 20,
      options: [t('form.checkOptions.normalColor'), t('form.checkOptions.completeShape')],
      color: '#4CAF50',
    },
    {
      id: 'smell',
      name: t('form.checkItems.smell'),
      maxScore: 20,
      options: [t('form.checkOptions.normal'), t('form.checkOptions.slightOdor')],
      color: '#2196F3',
    },
    {
      id: 'specification',
      name: t('form.checkItems.specification'),
      maxScore: 20,
      options: [t('form.checkOptions.standardSize'), t('form.checkOptions.sizeSmall'), t('form.checkOptions.sizeLarge')],
      color: '#9C27B0',
    },
    {
      id: 'weight',
      name: t('form.checkItems.weight'),
      maxScore: 20,
      options: [t('form.checkOptions.standardWeight'), t('form.checkOptions.weightLight'), t('form.checkOptions.weightHeavy')],
      color: '#FF9800',
    },
    {
      id: 'packaging',
      name: t('form.checkItems.packaging'),
      maxScore: 20,
      options: [t('form.checkOptions.packagingComplete'), t('form.checkOptions.labelClear')],
      color: '#F44336',
    },
  ];

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [batch, setBatch] = useState<QIBatch | null>(null);
  const [sampleSize, setSampleSize] = useState('10');
  const [remarks, setRemarks] = useState('');
  const [submitError, setSubmitError] = useState<string | null>(null);

  /**
   * 评分数据 —— **空开局**。
   *
   * 此前这里预填了 18/20/16/19/20 = 93 分, 连备注都预填好了
   * ("色泽正常"/"形态完整"/"正常"/"符合标准尺寸"/"符合标准"/"包装完整"/"标签清晰"),
   * 而唯一的提交闸是 `if (!batch) return`。也就是说: 打开屏幕 → 直接点提交,
   * 就落库一份 93 分 A 级的合格报告, **没有任何一项是人做出的判断**。
   * 在食品溯源系统里这是责任问题, 不是体验问题。
   *
   * 现在: 未评分的项**不在这个 Map 里**(或 score 为 null), 与"评了 0 分"严格区分 ——
   * `?? 0` 式的兜底会把"我还没看"翻译成"我看了, 是 0 分", 那是两件完全不同的事。
   */
  const [scores, setScores] = useState<Record<string, DraftScoreItem>>({});

  // 照片
  const [photos, setPhotos] = useState<string[]>([]);

  // 从 QICamera 回传的照片。此前 setPhotos **一次都没被调用过** ——
  // photos 恒为空数组, 所以「照片证据」这件事在质检里从来没成立过。
  const capturedPhotos = (route.params as { capturedPhotos?: string[] } | undefined)?.capturedPhotos;
  useEffect(() => {
    if (capturedPhotos && capturedPhotos.length) {
      setPhotos((prev) => Array.from(new Set([...prev, ...capturedPhotos])));
    }
  }, [capturedPhotos]);

  useEffect(() => {
    loadBatchDetail();
  }, [batchId]);

  const loadBatchDetail = async () => {
    try {
      setLoading(true);
      const data = await qualityInspectorApi.getBatchDetail(batchId);
      setBatch(data);
    } catch (error) {
      console.error('加载批次失败:', error);
      Alert.alert(t('form.loadBatchFailed'), t('form.cannotLoadBatch'));
    } finally {
      setLoading(false);
    }
  };

  /**
   * 写入某项分数。空输入 (`null`) = 清回"未评分", **不是 0 分**。
   * 上限用 `item.maxScore` 而不是写死的 20 —— 写死的常量在改配置时不会跟着变。
   */
  const updateScore = (itemId: string, score: number | null) => {
    const maxScore = CHECK_ITEMS.find((i) => i.id === itemId)?.maxScore ?? 20;
    setScores(prev => {
      const current = prev[itemId] ?? { score: null, notes: [] };
      return {
        ...prev,
        [itemId]: {
          ...current,
          score: score === null ? null : Math.min(maxScore, Math.max(0, score)),
        },
      };
    });
  };

  const toggleNote = (itemId: string, note: string) => {
    setScores(prev => {
      // ⚠️ 勾一个备注**不算**评分 —— 这里保持 score: null, 否则勾选会把
      //    "未评分"悄悄变成"0 分", 提交闸就放行了。
      const current = prev[itemId] ?? { score: null, notes: [] };
      const notes = current.notes.includes(note)
        ? current.notes.filter(n => n !== note)
        : [...current.notes, note];
      return {
        ...prev,
        [itemId]: { ...current, notes },
      };
    });
  };

  const sampleSizeNum = parseInt(sampleSize, 10);
  // 判定走 qualityInspectionSubmission.logic 里那个纯函数 —— 屏幕和单测
  // 跑的是**同一个**判定, 不是两份各自实现的 if (那样会漂)。
  const { unscoredItemIds, sampleSizeValid } = checkQualityInspectionSubmission(
    CHECK_ITEMS.map((item) => item.id),
    scores,
    sampleSizeNum,
  );
  /** 还没评分的检验项。非空 = 不能提交, 汇总区也不能显示总分。 */
  const unscoredItems = CHECK_ITEMS.filter((item) => unscoredItemIds.includes(item.id));
  const allScored = unscoredItemIds.length === 0;

  /**
   * 汇总量。**只有全部评完才存在** —— 否则是 null。
   *
   * 以前这里无条件算: 未评项按 0 分计入总分, 于是"评了 1 项"和"5 项全 0 分"
   * 在屏幕上是同一个数, 而合格率/等级照样一本正经地显示出来。
   */
  const totalScore = allScored
    ? calculateTotalScore({
        appearance: scores.appearance as ScoreItem,
        smell: scores.smell as ScoreItem,
        specification: scores.specification as ScoreItem,
        weight: scores.weight as ScoreItem,
        packaging: scores.packaging as ScoreItem,
      })
    : null;

  const grade = totalScore === null ? null : calculateGrade(totalScore);
  const derived =
    totalScore === null || !sampleSizeValid
      ? null
      : deriveQualityInspectionCounts(sampleSizeNum, totalScore);
  const inspectionResult = derived ? deriveQualityInspectionResult(derived.passRate) : null;
  const passed = inspectionResult === 'PASS';
  const canSubmit = allScored && sampleSizeValid && !!batch;

  const handleVoicePress = () => {
    navigation.navigate('QIVoice', { batchId, batchNumber });
  };

  const handleCameraPress = (itemId: string) => {
    navigation.navigate('QICamera', { batchId, batchNumber });
  };

  /**
   * 提交前校验。
   *
   * 此前唯一的闸是 `if (!batch) return` —— 零业务必填, 而且 `return` 是**静默**的:
   * 点了提交什么都没发生, 检验员不知道为什么。现在逐条说清缺什么。
   */
  const validateBeforeSubmit = (): string | null => {
    if (!batch) return t('form.cannotLoadBatch');
    if (!sampleSizeValid) return t('form.invalidSampleSize');
    if (unscoredItems.length > 0) {
      return t('form.unscoredItems', {
        count: unscoredItems.length,
        items: unscoredItems.map((i) => i.name).join('、'),
      });
    }
    return null;
  };

  const handleSubmit = async () => {
    const validationError = validateBeforeSubmit();
    if (validationError) {
      setSubmitError(validationError);
      Alert.alert(t('form.cannotSubmit'), validationError);
      return;
    }
    // validate 已经保证了这几个非空; 断言只是让类型收窄, 不是兜底。
    if (!batch || totalScore === null || grade === null || !derived || !inspectionResult) return;

    try {
      setSubmitting(true);
      setSubmitError(null);

      const scoreOf = (id: string): ScoreItem => ({
        score: scores[id]?.score as number,
        notes: scores[id]?.notes ?? [],
      });
      // Backend expects passCount/failCount — derive from HACCP score
      const formData = {
        batchId: batch.id,
        sampleSize: sampleSizeNum,
        passCount: derived.passCount,
        failCount: derived.failCount,
        result: inspectionResult,
        notes: remarks || '',
        appearance: scoreOf('appearance'),
        smell: scoreOf('smell'),
        specification: scoreOf('specification'),
        weight: scoreOf('weight'),
        packaging: scoreOf('packaging'),
        totalScore,
        grade,
        passed,
        photos,
        inspectorId: user?.id ?? 0,
        inspectedAt: new Date().toISOString(),
        customFields: {
          haccpScores: {
            appearance: scoreOf('appearance'),
            smell: scoreOf('smell'),
            specification: scoreOf('specification'),
            weight: scoreOf('weight'),
            packaging: scoreOf('packaging'),
          },
          totalScore,
          grade,
        },
      };

      const record = await qualityInspectorApi.submitInspection(batch.id, formData);

      // ⚠️ 后端返的是信封 `{ inspection, passRate }`, 不是记录本身 ——
      //    直接取 `record.id` 恒为 undefined, QIResult 会拿到一个空 id。
      const envelope = record as unknown as { id?: string; inspection?: { id?: string | number } };
      const inspectionId = String(envelope.inspection?.id ?? envelope.id ?? '');

      // 照片存成 QUALITY_CHECK 附件。`Attachment.EntityType.QUALITY_CHECK` 早就存在,
      // 但在此之前全仓没有任何地方把它当附件归属用过 —— 机制建好了没接上。
      if (inspectionId && photos.length) {
        await Promise.all(
          photos.map((uri, i) =>
            attachmentApi
              .uploadAndRegister(
                { uri, name: `qc-${inspectionId}-${i + 1}.jpg`, type: 'image/jpeg', size: 0 },
                'QUALITY_CHECK',
                inspectionId,
                { businessTag: 'QC_PHOTO' },
              )
              .catch((e: unknown) => {
                // 单张失败不该把整次质检提交判为失败 —— 记录已经落库了。
                console.warn(`[QI] 质检照片上传失败(${i + 1}/${photos.length}):`, e);
              }),
          ),
        );
      }

      navigation.replace('QIResult', {
        recordId: inspectionId,
        passed,
      });
    } catch (error) {
      console.error('提交失败:', error);
      const message = getQualityInspectionSubmitErrorMessage(
        error,
        t('form.checkNetworkAndRetry'),
      );
      setSubmitError(message);
      Alert.alert(t('form.submitFailed'), message);
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={QI_COLORS.primary} />
        <Text style={styles.loadingText}>{t('form.loadingBatch')}</Text>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      {/* 语音助手悬浮按钮 */}
      <TouchableOpacity style={styles.voiceFab} onPress={handleVoicePress}>
        <Ionicons name="mic" size={20} color="#fff" />
        <Text style={styles.voiceFabText}>{t('form.aiVoice')}</Text>
      </TouchableOpacity>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 100 }]}
        keyboardShouldPersistTaps="handled"
      >
        {/* 批次信息 */}
        <View style={styles.batchCard}>
          <Text style={styles.batchNumber}>{batchNumber}</Text>
          <Text style={styles.batchInfo}>
            {batch?.productName} | {t('home.source')}: {batch?.sourceProcess || '未知'} | {t('home.quantity')}: {batch?.quantity}{batch?.unit}
          </Text>
        </View>

        {/* 抽样设置 */}
        <View style={styles.sampleSection}>
          <View style={styles.sampleHeader}>
            <Ionicons name="book" size={18} color={QI_COLORS.primary} />
            <Text style={styles.sampleTitle}>{t('form.sampling')}</Text>
          </View>
          <View style={styles.sampleInputRow}>
            <Text style={styles.sampleLabel}>{t('form.sampleSize')}:</Text>
            <TextInput
              style={styles.sampleInput}
              value={sampleSize}
              onChangeText={setSampleSize}
              keyboardType="number-pad"
              maxLength={4}
            />
            <Text style={styles.sampleUnit}>{t('form.pieces')}</Text>
          </View>
        </View>

        {/* HACCP检查清单 */}
        <View style={styles.checkSection}>
          <View style={styles.checkHeader}>
            <Ionicons name="checkmark-circle" size={18} color={QI_COLORS.text} />
            <Text style={styles.checkTitle}>{t('form.haccpChecklist')}</Text>
          </View>

          {CHECK_ITEMS.map((item, index) => (
            <View key={item.id} style={styles.checkCard}>
              <View style={styles.checkCardHeader}>
                <View style={styles.checkCardTitle}>
                  <View style={[styles.checkNumber, { backgroundColor: item.color }]}>
                    <Text style={styles.checkNumberText}>{index + 1}</Text>
                  </View>
                  <Text style={styles.checkName}>{item.name} (0-{item.maxScore}分)</Text>
                </View>
                <Text style={styles.checkMax}>{t('form.fullScore', { score: item.maxScore })}</Text>
              </View>

              {/* 选项 */}
              <View style={styles.checkOptions}>
                {item.options.map((option) => (
                  <TouchableOpacity
                    key={option}
                    style={[
                      styles.checkOption,
                      scores[item.id]?.notes.includes(option) && styles.checkOptionSelected,
                    ]}
                    onPress={() => toggleNote(item.id, option)}
                  >
                    <Ionicons
                      name={scores[item.id]?.notes.includes(option) ? 'checkbox' : 'square-outline'}
                      size={18}
                      color={scores[item.id]?.notes.includes(option) ? QI_COLORS.primary : QI_COLORS.textSecondary}
                    />
                    <Text
                      style={[
                        styles.checkOptionText,
                        scores[item.id]?.notes.includes(option) && styles.checkOptionTextSelected,
                      ]}
                    >
                      {option}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>

              {/* 评分。未评分 = 空输入框 + 占位提示, ⛔ 不预填任何数字 */}
              <View style={styles.scoreRow}>
                <Text style={styles.scoreLabel}>{t('form.score')}:</Text>
                <TextInput
                  style={[
                    styles.scoreInput,
                    scores[item.id]?.score == null && styles.scoreInputUnscored,
                  ]}
                  value={scores[item.id]?.score == null ? '' : String(scores[item.id]?.score)}
                  onChangeText={(text) => {
                    const trimmed = text.trim();
                    // 清空 = 回到"未评分", 而不是 0 分
                    if (trimmed === '') {
                      updateScore(item.id, null);
                      return;
                    }
                    const parsed = parseInt(trimmed, 10);
                    updateScore(item.id, Number.isFinite(parsed) ? parsed : null);
                  }}
                  placeholder={t('form.notScored')}
                  placeholderTextColor={QI_COLORS.danger}
                  keyboardType="number-pad"
                  maxLength={3}
                />
                <Text style={styles.scoreMax}>/ {item.maxScore}</Text>
                {(item.id === 'appearance' || item.id === 'packaging') && (
                  <TouchableOpacity
                    style={styles.cameraBtn}
                    onPress={() => handleCameraPress(item.id)}
                  >
                    <Ionicons name="camera" size={14} color={QI_COLORS.primary} />
                    <Text style={styles.cameraBtnText}>{t('form.takePhoto')}</Text>
                  </TouchableOpacity>
                )}
              </View>
            </View>
          ))}
        </View>

        {/* 检验汇总 */}
        <View style={styles.summaryCard}>
          <View style={styles.summaryHeader}>
            <Ionicons name="bar-chart" size={18} color={QI_COLORS.primary} />
            <Text style={styles.summaryTitle}>{t('form.inspectionSummary')}</Text>
          </View>
          {/* 还没评完就别报一个"总分/合格率/等级" —— 那些数会被当成结论读 */}
          {!allScored && (
            <Text style={styles.summaryPending} accessibilityRole="alert">
              {t('form.unscoredItems', {
                count: unscoredItems.length,
                items: unscoredItems.map((i) => i.name).join('、'),
              })}
            </Text>
          )}
          <View style={styles.summaryGrid}>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryLabel}>{t('form.totalScore')}</Text>
              <Text style={styles.summaryValue}>
                {totalScore === null ? '—' : `${totalScore}/100`}
              </Text>
            </View>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryLabel}>{t('form.passedCount')}</Text>
              <Text style={[styles.summaryValue, { color: QI_COLORS.success }]}>
                {derived ? `${derived.passCount}/${sampleSizeNum}${t('form.sampleUnit')}` : '—'}
              </Text>
            </View>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryLabel}>{t('form.passRate')}</Text>
              <Text style={[styles.summaryValue, { color: QI_COLORS.success }]}>
                {derived ? `${Math.round(derived.passRate)}%` : '—'}
              </Text>
            </View>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryLabel}>{t('form.suggestedGrade')}</Text>
              {grade === null ? (
                <Text style={styles.summaryValue}>—</Text>
              ) : (
                <View style={[styles.gradeBadge, { backgroundColor: GRADE_COLORS[grade] }]}>
                  <Text style={styles.gradeBadgeText}>{grade}</Text>
                </View>
              )}
            </View>
          </View>
        </View>

        {/* 检验备注 */}
        <View style={styles.remarksSection}>
          <Text style={styles.remarksLabel}>{t('form.inspectionRemarks')}</Text>
          <TextInput
            style={styles.remarksInput}
            value={remarks}
            onChangeText={setRemarks}
            placeholder={t('form.enterRemarks')}
            placeholderTextColor={QI_COLORS.disabled}
            multiline
            numberOfLines={3}
          />
        </View>
      </ScrollView>

      {/* 底部操作栏 */}
      <View style={[styles.bottomBar, { paddingBottom: insets.bottom + 16 }]}>
        {submitError ? (
          <View style={styles.submitErrorBanner} accessibilityRole="alert">
            <Ionicons name="alert-circle" size={18} color={QI_COLORS.danger} />
            <Text style={styles.submitErrorText}>提交失败：{submitError}</Text>
          </View>
        ) : null}
        {/*
          ⚠️ 按钮**不 disable**: disabled 的按钮点下去没有反馈, 检验员不知道
          缺什么。保持可点, 点了由 validateBeforeSubmit 明确说出缺哪一项。
        */}
        <TouchableOpacity
          style={[styles.submitBtn, (submitting || !canSubmit) && styles.submitBtnDisabled]}
          onPress={handleSubmit}
          disabled={submitting}
        >
          {submitting ? (
            <ActivityIndicator size="small" color="#fff" />
          ) : (
            <>
              <Text style={styles.submitBtnText}>{t('form.submitInspectionResult')}</Text>
              <Ionicons name="arrow-forward" size={18} color="#fff" />
            </>
          )}
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
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

  // 语音悬浮按钮
  voiceFab: {
    position: 'absolute',
    top: 12,
    right: 16,
    backgroundColor: QI_COLORS.primary,
    borderRadius: 20,
    paddingHorizontal: 14,
    paddingVertical: 8,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    zIndex: 100,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 4,
  },
  voiceFabText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '500',
  },

  // 批次信息
  batchCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderLeftWidth: 4,
    borderLeftColor: QI_COLORS.primary,
  },
  batchNumber: {
    fontSize: 18,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginBottom: 6,
  },
  batchInfo: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },

  // 抽样设置
  sampleSection: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  sampleHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 12,
  },
  sampleTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  sampleInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  sampleLabel: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
    marginRight: 12,
  },
  sampleInput: {
    width: 60,
    height: 40,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    fontSize: 16,
    textAlign: 'center',
    color: QI_COLORS.text,
    backgroundColor: QI_COLORS.background,
  },
  sampleUnit: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
    marginLeft: 8,
  },

  // 检查清单
  checkSection: {
    marginBottom: 16,
  },
  checkHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 12,
  },
  checkTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  checkCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  checkCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  checkCardTitle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  checkNumber: {
    width: 24,
    height: 24,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
  },
  checkNumberText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '600',
  },
  checkName: {
    fontSize: 15,
    fontWeight: '500',
    color: QI_COLORS.text,
  },
  checkMax: {
    fontSize: 12,
    color: QI_COLORS.textSecondary,
  },
  checkOptions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 12,
  },
  checkOption: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: QI_COLORS.background,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
    gap: 6,
  },
  checkOptionSelected: {
    backgroundColor: '#E8F5E9',
  },
  checkOptionText: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
  },
  checkOptionTextSelected: {
    color: QI_COLORS.primary,
  },
  scoreRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: QI_COLORS.border,
  },
  scoreLabel: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
    marginRight: 8,
  },
  scoreInput: {
    width: 50,
    height: 36,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    borderRadius: 6,
    paddingHorizontal: 8,
    fontSize: 16,
    textAlign: 'center',
    color: QI_COLORS.text,
    backgroundColor: QI_COLORS.background,
  },
  /** 未评分: 描红边, 让"还没做判断"在一屏里看得见 */
  scoreInputUnscored: {
    width: 72,
    borderColor: QI_COLORS.danger,
    fontSize: 12,
  },
  scoreMax: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
    marginLeft: 4,
    flex: 1,
  },
  cameraBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#E8F5E9',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 6,
    gap: 4,
  },
  cameraBtnText: {
    fontSize: 13,
    color: QI_COLORS.primary,
  },

  // 汇总
  summaryCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  summaryHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 16,
  },
  summaryTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  summaryGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  summaryItem: {
    width: '50%',
    marginBottom: 16,
  },
  summaryPending: {
    fontSize: 13,
    lineHeight: 19,
    color: QI_COLORS.danger,
    marginBottom: 10,
  },
  summaryLabel: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginBottom: 4,
  },
  summaryValue: {
    fontSize: 18,
    fontWeight: '600',
    color: QI_COLORS.text,
  },
  gradeBadge: {
    width: 32,
    height: 32,
    borderRadius: 6,
    justifyContent: 'center',
    alignItems: 'center',
  },
  gradeBadgeText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '700',
  },

  // 备注
  remarksSection: {
    marginBottom: 16,
  },
  remarksLabel: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
    marginBottom: 8,
  },
  remarksInput: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    padding: 16,
    fontSize: 14,
    color: QI_COLORS.text,
    minHeight: 80,
    textAlignVertical: 'top',
  },

  // 底部栏
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: QI_COLORS.card,
    borderTopWidth: 1,
    borderTopColor: QI_COLORS.border,
    paddingHorizontal: 16,
    paddingTop: 16,
  },
  submitBtn: {
    backgroundColor: QI_COLORS.primary,
    borderRadius: 12,
    paddingVertical: 16,
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 8,
  },
  submitErrorBanner: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    borderWidth: 1,
    borderColor: QI_COLORS.danger,
    backgroundColor: '#FFF1F0',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginBottom: 10,
  },
  submitErrorText: {
    flex: 1,
    color: QI_COLORS.danger,
    fontSize: 14,
    lineHeight: 20,
  },
  submitBtnDisabled: {
    opacity: 0.6,
  },
  submitBtnText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
