/**
 * VisionVerifyScreen — 拍照核验
 *
 * 两个模板:
 *  · EAR_TAG  标识牌识别 —— 读出耳标/脚环号并反查源头个体档案。
 *    ⚠️ 标识牌上有条码/二维码时**直接扫码**, 不过视觉模型: 扫码是确定的,
 *       VL 识别是概率的 —— 有确定读数就别用概率读数。
 *  · MEAT_CUT 肉品分割块初评 —— 结果永远带「AI 初评 · 需人工复核」。
 *
 * ⛔ 本屏不做任何兜底: 识别不出来就说识别不出来(422 带模型原文),
 *    服务不可用就说服务不可用。⛔ 不显示一个空的结果卡片假装成功。
 */
import React, { useCallback, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  Divider,
  Text,
  useTheme,
} from 'react-native-paper';
import * as ImagePicker from 'expo-image-picker';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';

import type { AppTheme } from '../../theme';
import { appAlert, AppDialogHost } from '../../components/ui/AppDialog';
import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
import { DnaStatusChip } from '../traceability/components/dnaStatus';
import {
  sourceIndividualApiClient,
  type SourceIndividual,
  type VisionTemplateCode,
  type VisionVerifyResult,
} from '../../services/api/sourceIndividualApiClient';
import type { ProcessingStackParamList } from '../../types/navigation';

type Nav = NativeStackNavigationProp<ProcessingStackParamList, 'VisionVerify'>;

/**
 * 屏上「识别不出来」这一态是**一等结果**, 不是异常。
 * ⛔ 不要把它折叠进 `result === null` —— 那样「还没拍」和「拍了但没认出来」
 *    在 UI 上长得一样, 而用户的下一步完全不同。
 */
type Unparseable = { reason: string; rawText?: string | null };

const TEMPLATES: Array<{ code: VisionTemplateCode; labelKey: string }> = [
  { code: 'EAR_TAG', labelKey: 'sourceIndividual.vision.template.earTag' },
  { code: 'MEAT_CUT', labelKey: 'sourceIndividual.vision.template.meatCut' },
];

export default function VisionVerifyScreen() {
  const { t } = useTranslation('processing');
  const navigation = useNavigation<Nav>();
  const theme = useTheme<AppTheme>();

  const [templateCode, setTemplateCode] = useState<VisionTemplateCode>('EAR_TAG');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<VisionVerifyResult | null>(null);
  const [unparseable, setUnparseable] = useState<Unparseable | null>(null);
  const [scannerVisible, setScannerVisible] = useState(false);
  /** 扫码路径的结果 —— 它没有经过视觉模型, 所以单独一个状态, ⛔ 不复用 result。 */
  const [scanLookup, setScanLookup] = useState<
    { tagNumber: string; matchCount: number; matched: SourceIndividual | null } | null
  >(null);

  const reset = useCallback(() => {
    setResult(null);
    setUnparseable(null);
    setScanLookup(null);
  }, []);

  const runVerify = useCallback(
    async (base64: string) => {
      setBusy(true);
      reset();
      try {
        const outcome = await sourceIndividualApiClient.verify({
          templateCode,
          imageBase64: base64,
          contentType: 'image/jpeg',
        });
        if (outcome.ok) {
          setResult(outcome.result);
        } else {
          setUnparseable({ reason: outcome.reason, rawText: outcome.rawText });
        }
      } catch (error) {
        // 非 422 的错误一律弹出来 —— 拦截器已经把后端文案抬到 message 上。
        appAlert(
          t('sourceIndividual.vision.failedTitle'),
          error instanceof Error ? error.message : t('sourceIndividual.vision.failedUnknown')
        );
      } finally {
        setBusy(false);
      }
    },
    [templateCode, reset, t]
  );

  const takePhoto = useCallback(async () => {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) {
      appAlert(t('sourceIndividual.vision.permissionTitle'), t('sourceIndividual.vision.cameraDenied'));
      return;
    }
    const picked = await ImagePicker.launchCameraAsync({
      mediaTypes: ['images'],
      quality: 0.8,
      base64: true,
    });
    const base64 = picked.canceled ? undefined : picked.assets?.[0]?.base64;
    if (base64) {
      await runVerify(base64);
    }
  }, [runVerify, t]);

  const pickFromGallery = useCallback(async () => {
    const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      appAlert(t('sourceIndividual.vision.permissionTitle'), t('sourceIndividual.vision.galleryDenied'));
      return;
    }
    const picked = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 0.8,
      base64: true,
    });
    const base64 = picked.canceled ? undefined : picked.assets?.[0]?.base64;
    if (base64) {
      await runVerify(base64);
    }
  }, [runVerify, t]);

  /** 扫到的码**直接查库**, 不经过视觉模型。 */
  const handleScan = useCallback(
    async (data: string) => {
      setScannerVisible(false);
      setBusy(true);
      reset();
      try {
        const lookup = await sourceIndividualApiClient.findByTag(data);
        setScanLookup({
          tagNumber: lookup.tagNumber,
          matchCount: lookup.matchCount,
          matched: lookup.matched,
        });
      } catch (error) {
        appAlert(
          t('sourceIndividual.vision.failedTitle'),
          error instanceof Error ? error.message : t('sourceIndividual.vision.failedUnknown')
        );
      } finally {
        setBusy(false);
      }
    },
    [reset, t]
  );

  /** 当前屏上「耳标号 + 命中情况」的唯一来源 —— 扫码与拍照两条路在这里汇合。 */
  const tagOutcome = useMemo(() => {
    if (scanLookup) {
      return {
        tagNumber: scanLookup.tagNumber,
        matchCount: scanLookup.matchCount,
        matched: scanLookup.matched,
        viaScan: true,
      };
    }
    if (result && result.templateCode === 'EAR_TAG') {
      return {
        tagNumber: result.normalizedTagNumber ?? null,
        // ⚠️ null/undefined 与 0 是两件事: 前者「模型没读出号」, 后者「查了没有」。
        matchCount: result.matchCount ?? null,
        matched: result.matched ?? null,
        viaScan: false,
      };
    }
    return null;
  }, [scanLookup, result]);

  /**
   * 耳标卡的三种「长相」—— 车间光线下不该靠读字分辨成功还是失败。
   *  · 没读出编号(只可能来自拍照) ⇒ errorContainer, 卡上带一个主色「重拍」
   *  · 唯一命中一份档案          ⇒ workflow.successBg
   *  · 读出了编号但 0 / >1 命中   ⇒ 默认 surface —— 识别成功、档案没定, 不给绿也不给红
   * ⛔ 只改容器色与文字色, 文案不动。
   */
  const tagRead = !!tagOutcome?.tagNumber;
  const earTagTone = useMemo(() => {
    if (!tagOutcome) return null;
    if (!tagRead) {
      return { bg: theme.colors.errorContainer, fg: theme.colors.onErrorContainer };
    }
    if (tagOutcome.matched) {
      return { bg: theme.custom.workflow.successBg, fg: theme.custom.workflow.successText };
    }
    return { bg: theme.colors.surface, fg: theme.colors.onSurfaceVariant };
  }, [tagOutcome, tagRead, theme]);

  const { spacing, borderRadius } = theme.custom;
  const cardStyle = { marginTop: spacing.l, borderRadius: borderRadius.m };
  const dividerStyle = { marginVertical: spacing.s };
  const mutedStyle = { color: theme.colors.onSurfaceVariant };
  const errorCardStyle = [cardStyle, { backgroundColor: theme.colors.errorContainer }];
  const onErrorStyle = { color: theme.colors.onErrorContainer };

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.background }]}>
      {/* 两个导航栈都是 headerShown: false —— 屏幕自己出标题栏, 与 SourceIndividualDetailScreen 同一范本。
          Appbar.Header 自己吃状态栏高度, 手写标题压进系统时钟那一版(2026-09-15 真机)就是缺这一层。 */}
      <Appbar.Header>
        <Appbar.BackAction
          testID="vision-back"
          accessibilityLabel={t('common:buttons.back')}
          onPress={() => navigation.goBack()}
        />
        <Appbar.Content testID="vision-title" title={t('sourceIndividual.vision.title')} />
      </Appbar.Header>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={{ padding: spacing.l, paddingBottom: spacing.section }}
      >
        <AppDialogHost />

        <View style={[styles.chipRow, { gap: spacing.s, marginBottom: spacing.l }]}>
          {TEMPLATES.map((tpl) => (
            <Chip
              key={tpl.code}
              testID={`vision-template-${tpl.code}`}
              accessibilityLabel={t(tpl.labelKey)}
              selected={templateCode === tpl.code}
              showSelectedOverlay
              onPress={() => {
                setTemplateCode(tpl.code);
                reset();
              }}
            >
              {t(tpl.labelKey)}
            </Chip>
          ))}
        </View>

        {/* 主动作一个、主色一个: 标识牌模板下扫码是确定读数 ⇒ 扫码 contained, 拍照退到 tonal;
            肉品模板没有扫码这条路, 拍照就是主动作。视觉层级与下面那句提示保持同一方向。 */}
        <View style={[styles.actionRow, { gap: spacing.s }]}>
          {templateCode === 'EAR_TAG' && (
            <Button
              testID="vision-scan-button"
              accessibilityLabel={t('sourceIndividual.vision.scan')}
              mode="contained"
              icon="barcode-scan"
              disabled={busy}
              onPress={() => setScannerVisible(true)}
            >
              {t('sourceIndividual.vision.scan')}
            </Button>
          )}
          <Button
            testID="vision-camera-button"
            accessibilityLabel={t('sourceIndividual.vision.takePhoto')}
            mode={templateCode === 'EAR_TAG' ? 'contained-tonal' : 'contained'}
            icon="camera"
            disabled={busy}
            onPress={takePhoto}
          >
            {t('sourceIndividual.vision.takePhoto')}
          </Button>
          <Button
            testID="vision-gallery-button"
            accessibilityLabel={t('sourceIndividual.vision.gallery')}
            mode="outlined"
            icon="image"
            disabled={busy}
            onPress={pickFromGallery}
          >
            {t('sourceIndividual.vision.gallery')}
          </Button>
        </View>

        {templateCode === 'EAR_TAG' && (
          // 这句直接躺在 background 上(不在卡里): onSurfaceVariant 对 background 只有 4.43:1,
          // 过不了 4.5 ⇒ 用 onSurface(13.5:1)。
          <Text
            variant="bodyMedium"
            style={{ color: theme.colors.onSurface, marginTop: spacing.m, marginBottom: spacing.s }}
          >
            {t('sourceIndividual.vision.scanPreferredHint')}
          </Text>
        )}

        {busy && (
          <View style={[styles.busyBox, { gap: spacing.m, paddingVertical: spacing.xl }]} testID="vision-busy">
            <ActivityIndicator size="large" />
            <Text variant="bodyLarge">{t('sourceIndividual.vision.working')}</Text>
          </View>
        )}

        {/* ── 识别不出来 —— 一等结果, 带模型原文 ───────────────────────────── */}
        {unparseable && (
          <Card style={errorCardStyle} testID="vision-unparseable-card">
            <Card.Title title={t('sourceIndividual.vision.unreadableTitle')} subtitleStyle={onErrorStyle} />
            <Card.Content>
              <Text variant="bodyMedium">{unparseable.reason}</Text>
              {!!unparseable.rawText && (
                <>
                  <Divider style={dividerStyle} />
                  <Text variant="labelMedium" style={onErrorStyle}>
                    {t('sourceIndividual.vision.modelRawText')}
                  </Text>
                  <Text variant="bodyMedium" style={onErrorStyle}>
                    {unparseable.rawText}
                  </Text>
                </>
              )}
            </Card.Content>
            <Card.Actions>
              <Button
                testID="vision-retry-button"
                accessibilityLabel={t('sourceIndividual.vision.retake')}
                mode="contained"
                icon="camera-retake"
                onPress={takePhoto}
              >
                {t('sourceIndividual.vision.retake')}
              </Button>
            </Card.Actions>
          </Card>
        )}

        {/* ── EAR_TAG 结果 ─────────────────────────────────────────────────── */}
        {tagOutcome && earTagTone && (
          <Card style={[cardStyle, { backgroundColor: earTagTone.bg }]} testID="vision-ear-tag-card">
            <Card.Title
              title={t('sourceIndividual.vision.earTagResultTitle')}
              subtitle={
                tagOutcome.viaScan
                  ? t('sourceIndividual.vision.viaScan')
                  : t('sourceIndividual.vision.viaModel')
              }
              subtitleStyle={{ color: earTagTone.fg }}
            />
            <Card.Content>
              {tagRead ? (
                <Text variant="headlineSmall" testID="vision-tag-number">
                  {tagOutcome.tagNumber}
                </Text>
              ) : (
                // 模型明确说读不出号 —— 与「查了没有」分开说。
                <Text variant="bodyLarge" testID="vision-tag-unreadable">
                  {t('sourceIndividual.vision.tagUnreadable')}
                </Text>
              )}

              {/* 把握程度只跟着「读出来的那个编号」走: 没读出编号时 0% 不是读数, 是没有读数。 */}
              {tagRead && !tagOutcome.viaScan && typeof result?.confidence === 'number' && (
                <Text variant="bodyMedium" style={{ color: earTagTone.fg, marginTop: spacing.s }} testID="vision-confidence">
                  {t('sourceIndividual.vision.confidence', {
                    value: Math.round(result.confidence * 100),
                  })}
                </Text>
              )}

              {tagOutcome.matchCount === null && !tagRead && (
                <Text variant="bodyMedium" style={{ color: earTagTone.fg, marginTop: spacing.s }}>
                  {t('sourceIndividual.vision.notQueried')}
                </Text>
              )}

              {tagOutcome.matchCount === 0 && (
                <Text variant="bodyMedium" style={{ color: earTagTone.fg, marginTop: spacing.s }} testID="vision-no-match">
                  {t('sourceIndividual.vision.noMatch')}
                </Text>
              )}

              {!!tagOutcome.matched && (
                <View style={{ marginTop: spacing.xs }} testID="vision-matched">
                  <Divider style={dividerStyle} />
                  <Field label={t('sourceIndividual.fields.individualCode')} value={tagOutcome.matched.individualCode} labelColor={earTagTone.fg} />
                  <Field label={t('sourceIndividual.fields.species')} value={tagOutcome.matched.species} labelColor={earTagTone.fg} />
                  <Field label={t('sourceIndividual.fields.breed')} value={tagOutcome.matched.breed} labelColor={earTagTone.fg} />
                  <Field label={t('sourceIndividual.fields.originFarm')} value={tagOutcome.matched.originFarm} labelColor={earTagTone.fg} />
                  {/* DNA 状态走四处共用的色板 (traceability/components/dnaStatus) ——
                      时间线节点 / 档案头部 / 后代行 / 这里, 同一状态同一颜色。
                      后端没给状态码时退回原来的文本 Field (显示 displayName 或「—」)。 */}
                  {tagOutcome.matched.dnaVerificationStatus ? (
                    <View style={[styles.fieldRow, { paddingVertical: spacing.xs }]}>
                      <Text variant="labelMedium" style={{ color: earTagTone.fg }}>
                        {t('sourceIndividual.fields.dnaStatus')}
                      </Text>
                      <DnaStatusChip status={tagOutcome.matched.dnaVerificationStatus} testID="vision-dna-status" />
                    </View>
                  ) : (
                    <Field
                      label={t('sourceIndividual.fields.dnaStatus')}
                      value={tagOutcome.matched.dnaVerificationStatusDisplayName}
                      labelColor={earTagTone.fg}
                    />
                  )}
                </View>
              )}

              {typeof tagOutcome.matchCount === 'number' && tagOutcome.matchCount > 1 && (
                <Text
                  variant="bodyMedium"
                  style={{ color: theme.custom.workflow.pendingText, marginTop: spacing.s }}
                  testID="vision-multi-match"
                >
                  {t('sourceIndividual.vision.multipleMatches', { count: tagOutcome.matchCount })}
                </Text>
              )}
            </Card.Content>
            <Card.Actions>
              {tagOutcome.matched ? (
                <Button
                  testID="vision-open-profile"
                  accessibilityLabel={t('sourceIndividual.vision.openProfile')}
                  // 「查看档案」进只读详情 (系谱 + 去向); 编辑从详情屏右上角进。
                  onPress={() => {
                    // 这个按钮只在 matched 非空时渲染; 闭包里 TS 不保留属性收窄, 所以再取一次。
                    const matchedId = tagOutcome.matched?.id;
                    if (!matchedId) {
                      return;
                    }
                    navigation.navigate('SourceIndividualDetail', { individualId: matchedId });
                  }}
                >
                  {t('sourceIndividual.vision.openProfile')}
                </Button>
              ) : tagRead ? (
                <Button
                  testID="vision-register-new"
                  accessibilityLabel={t('sourceIndividual.vision.registerNew')}
                  onPress={() =>
                    navigation.navigate('SourceIndividualForm', {
                      tagNumber: tagOutcome.tagNumber ?? undefined,
                    })
                  }
                >
                  {t('sourceIndividual.vision.registerNew')}
                </Button>
              ) : (
                // 没读出编号 ⇒ 用户的下一步只有一个: 重拍。与上面 unparseable 卡走同一条 takePhoto。
                <Button
                  testID="vision-ear-tag-retake-button"
                  accessibilityLabel={t('sourceIndividual.vision.retake')}
                  mode="contained"
                  icon="camera-retake"
                  onPress={takePhoto}
                >
                  {t('sourceIndividual.vision.retake')}
                </Button>
              )}
            </Card.Actions>
          </Card>
        )}

        {/* ── MEAT_CUT 结果 ────────────────────────────────────────────────── */}
        {result && result.templateCode === 'MEAT_CUT' && (
          <Card style={cardStyle} testID="vision-meat-cut-card">
            <Card.Title
              title={t('sourceIndividual.vision.meatCutResultTitle')}
              subtitle={t('sourceIndividual.vision.aiPreliminary')}
            />
            <Card.Content>
              {/* ⚠️ 这一行不是装饰: 它是这个结论的限定, ⛔ 任何情况下都不许省掉。
                  「待定 / 需复核」这一类走 workflow.pending 那一对(与质检待检验同色)。 */}
              <Chip
                icon="alert-circle-outline"
                style={{
                  alignSelf: 'flex-start',
                  marginBottom: spacing.m,
                  backgroundColor: theme.custom.workflow.pendingBg,
                }}
                textStyle={{ color: theme.custom.workflow.pendingText }}
                testID="vision-assessment-level"
              >
                {t('sourceIndividual.vision.aiPreliminary')}
              </Chip>
              <Field label={t('sourceIndividual.vision.cut')} value={asText(result.fields?.cut)} />
              <Field
                label={t('sourceIndividual.vision.marbling')}
                value={asText(result.fields?.marblingScore)}
              />
              <Field
                label={t('sourceIndividual.vision.foreignObject')}
                value={asFlag(result.fields?.foreignObjectSuspected, t)}
              />
              <Field
                label={t('sourceIndividual.vision.contamination')}
                value={asFlag(result.fields?.contaminationSuspected, t)}
              />
              {typeof result.confidence === 'number' && (
                <Text variant="bodyMedium" style={[mutedStyle, { marginTop: spacing.s }]} testID="vision-confidence">
                  {t('sourceIndividual.vision.confidence', {
                    value: Math.round(result.confidence * 100),
                  })}
                </Text>
              )}
              {!!asText(result.fields?.notes) && (
                <Text variant="bodyMedium" style={[mutedStyle, { marginTop: spacing.s }]}>
                  {asText(result.fields?.notes)}
                </Text>
              )}
            </Card.Content>
          </Card>
        )}

        <BarcodeScannerModal
          visible={scannerVisible}
          onClose={() => setScannerVisible(false)}
          onScan={handleScan}
          title={t('sourceIndividual.vision.scannerTitle')}
        />
      </ScrollView>
    </View>
  );
}

/**
 * ⛔ 没有值就显示「—」, 不显示 0、不显示「未知」。
 * 这里是屏上唯一一处「值缺席」的渲染, 保证整屏口径一致。
 * `labelColor` 让标签色跟着所在卡片的容器色走(绿卡 / 红卡上 onSurfaceVariant 过不了 4.5:1)。
 */
function Field({ label, value, labelColor }: { label: string; value?: string | null; labelColor?: string }) {
  const theme = useTheme<AppTheme>();
  return (
    <View style={[styles.fieldRow, { paddingVertical: theme.custom.spacing.xs }]}>
      <Text variant="labelMedium" style={{ color: labelColor ?? theme.colors.onSurfaceVariant }}>
        {label}
      </Text>
      <Text variant="bodyMedium">{value == null || value === '' ? '—' : value}</Text>
    </View>
  );
}

function asText(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return null;
}

/** true/false 之外的一切(含缺席)都返回 null —— ⛔ 不把「没说」读成「否」。 */
function asFlag(value: unknown, t: (k: string) => string): string | null {
  if (value === true) return t('sourceIndividual.vision.suspected');
  if (value === false) return t('sourceIndividual.vision.notSuspected');
  return null;
}

// ⚠️ 这里只放布局: 颜色 / 字号 / 间距全部走 useTheme() 与 Paper 的 variant,
//    `gates/demoScreensUseThemeTokens` 守着本文件里不再出现色值与 fontSize<14。
const styles = StyleSheet.create({
  container: { flex: 1 },
  scroll: { flex: 1 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap' },
  actionRow: { flexDirection: 'row', flexWrap: 'wrap' },
  busyBox: { alignItems: 'center', justifyContent: 'center' },
  fieldRow: { flexDirection: 'row', justifyContent: 'space-between' },
});
