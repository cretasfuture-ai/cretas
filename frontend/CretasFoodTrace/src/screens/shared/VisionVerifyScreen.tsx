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
  Button,
  Card,
  Chip,
  Divider,
  Text,
} from 'react-native-paper';
import * as ImagePicker from 'expo-image-picker';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';

import { appAlert, AppDialogHost } from '../../components/ui/AppDialog';
import { BarcodeScannerModal } from '../../components/processing/BarcodeScannerModal';
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
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
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
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
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

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <AppDialogHost />

      <Text variant="titleMedium" style={styles.sectionTitle}>
        {t('sourceIndividual.vision.title')}
      </Text>

      <View style={styles.chipRow}>
        {TEMPLATES.map((tpl) => (
          <Chip
            key={tpl.code}
            testID={`vision-template-${tpl.code}`}
            selected={templateCode === tpl.code}
            onPress={() => {
              setTemplateCode(tpl.code);
              reset();
            }}
            style={styles.chip}
          >
            {t(tpl.labelKey)}
          </Chip>
        ))}
      </View>

      <View style={styles.actionRow}>
        {templateCode === 'EAR_TAG' && (
          <Button
            testID="vision-scan-button"
            mode="contained-tonal"
            icon="barcode-scan"
            disabled={busy}
            onPress={() => setScannerVisible(true)}
            style={styles.actionButton}
          >
            {t('sourceIndividual.vision.scan')}
          </Button>
        )}
        <Button
          testID="vision-camera-button"
          mode="contained"
          icon="camera"
          disabled={busy}
          onPress={takePhoto}
          style={styles.actionButton}
        >
          {t('sourceIndividual.vision.takePhoto')}
        </Button>
        <Button
          testID="vision-gallery-button"
          mode="outlined"
          icon="image"
          disabled={busy}
          onPress={pickFromGallery}
          style={styles.actionButton}
        >
          {t('sourceIndividual.vision.gallery')}
        </Button>
      </View>

      {templateCode === 'EAR_TAG' && (
        <Text variant="bodySmall" style={styles.hint}>
          {t('sourceIndividual.vision.scanPreferredHint')}
        </Text>
      )}

      {busy && (
        <View style={styles.busyBox} testID="vision-busy">
          <ActivityIndicator />
          <Text style={styles.busyText}>{t('sourceIndividual.vision.working')}</Text>
        </View>
      )}

      {/* ── 识别不出来 —— 一等结果, 带模型原文 ───────────────────────────── */}
      {unparseable && (
        <Card style={styles.card} testID="vision-unparseable-card">
          <Card.Title title={t('sourceIndividual.vision.unreadableTitle')} />
          <Card.Content>
            <Text variant="bodyMedium">{unparseable.reason}</Text>
            {!!unparseable.rawText && (
              <>
                <Divider style={styles.divider} />
                <Text variant="labelSmall">{t('sourceIndividual.vision.modelRawText')}</Text>
                <Text variant="bodySmall" style={styles.rawText}>
                  {unparseable.rawText}
                </Text>
              </>
            )}
          </Card.Content>
          <Card.Actions>
            <Button testID="vision-retry-button" onPress={takePhoto}>
              {t('sourceIndividual.vision.retake')}
            </Button>
          </Card.Actions>
        </Card>
      )}

      {/* ── EAR_TAG 结果 ─────────────────────────────────────────────────── */}
      {tagOutcome && (
        <Card style={styles.card} testID="vision-ear-tag-card">
          <Card.Title
            title={t('sourceIndividual.vision.earTagResultTitle')}
            subtitle={
              tagOutcome.viaScan
                ? t('sourceIndividual.vision.viaScan')
                : t('sourceIndividual.vision.viaModel')
            }
          />
          <Card.Content>
            {tagOutcome.tagNumber ? (
              <Text variant="headlineSmall" testID="vision-tag-number">
                {tagOutcome.tagNumber}
              </Text>
            ) : (
              // 模型明确说读不出号 —— 与「查了没有」分开说。
              <Text variant="bodyMedium" testID="vision-tag-unreadable">
                {t('sourceIndividual.vision.tagUnreadable')}
              </Text>
            )}

            {!tagOutcome.viaScan && typeof result?.confidence === 'number' && (
              <Text variant="bodySmall" style={styles.meta}>
                {t('sourceIndividual.vision.confidence', {
                  value: Math.round(result.confidence * 100),
                })}
              </Text>
            )}

            {tagOutcome.matchCount === null && tagOutcome.tagNumber === null && (
              <Text variant="bodySmall" style={styles.meta}>
                {t('sourceIndividual.vision.notQueried')}
              </Text>
            )}

            {tagOutcome.matchCount === 0 && (
              <Text variant="bodyMedium" style={styles.meta} testID="vision-no-match">
                {t('sourceIndividual.vision.noMatch')}
              </Text>
            )}

            {!!tagOutcome.matched && (
              <View style={styles.matchedBox} testID="vision-matched">
                <Divider style={styles.divider} />
                <Field label={t('sourceIndividual.fields.individualCode')} value={tagOutcome.matched.individualCode} />
                <Field label={t('sourceIndividual.fields.species')} value={tagOutcome.matched.species} />
                <Field label={t('sourceIndividual.fields.breed')} value={tagOutcome.matched.breed} />
                <Field label={t('sourceIndividual.fields.originFarm')} value={tagOutcome.matched.originFarm} />
                <Field
                  label={t('sourceIndividual.fields.dnaStatus')}
                  value={tagOutcome.matched.dnaVerificationStatusDisplayName}
                />
              </View>
            )}

            {typeof tagOutcome.matchCount === 'number' && tagOutcome.matchCount > 1 && (
              <Text variant="bodySmall" style={styles.warn} testID="vision-multi-match">
                {t('sourceIndividual.vision.multipleMatches', { count: tagOutcome.matchCount })}
              </Text>
            )}
          </Card.Content>
          <Card.Actions>
            {tagOutcome.matched ? (
              <Button
                testID="vision-open-profile"
                onPress={() =>
                  navigation.navigate('SourceIndividualForm', {
                    individualId: tagOutcome.matched?.id,
                  })
                }
              >
                {t('sourceIndividual.vision.openProfile')}
              </Button>
            ) : (
              tagOutcome.tagNumber && (
                <Button
                  testID="vision-register-new"
                  onPress={() =>
                    navigation.navigate('SourceIndividualForm', {
                      tagNumber: tagOutcome.tagNumber ?? undefined,
                    })
                  }
                >
                  {t('sourceIndividual.vision.registerNew')}
                </Button>
              )
            )}
          </Card.Actions>
        </Card>
      )}

      {/* ── MEAT_CUT 结果 ────────────────────────────────────────────────── */}
      {result && result.templateCode === 'MEAT_CUT' && (
        <Card style={styles.card} testID="vision-meat-cut-card">
          <Card.Title
            title={t('sourceIndividual.vision.meatCutResultTitle')}
            subtitle={t('sourceIndividual.vision.aiPreliminary')}
          />
          <Card.Content>
            {/* ⚠️ 这一行不是装饰: 它是这个结论的限定, ⛔ 任何情况下都不许省掉。 */}
            <Chip icon="alert-circle-outline" style={styles.stamp} testID="vision-assessment-level">
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
              <Text variant="bodySmall" style={styles.meta}>
                {t('sourceIndividual.vision.confidence', {
                  value: Math.round(result.confidence * 100),
                })}
              </Text>
            )}
            {!!asText(result.fields?.notes) && (
              <Text variant="bodySmall" style={styles.meta}>
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
  );
}

/**
 * ⛔ 没有值就显示「—」, 不显示 0、不显示「未知」。
 * 这里是屏上唯一一处「值缺席」的渲染, 保证整屏口径一致。
 */
function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <View style={styles.fieldRow}>
      <Text variant="labelMedium" style={styles.fieldLabel}>
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

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  content: { padding: 16, paddingBottom: 48 },
  sectionTitle: { marginBottom: 12 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 16 },
  chip: { marginRight: 8 },
  actionRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  actionButton: { marginRight: 8, marginBottom: 8 },
  hint: { color: '#718096', marginBottom: 8 },
  busyBox: { flexDirection: 'row', alignItems: 'center', marginVertical: 16 },
  busyText: { marginLeft: 8 },
  card: { marginTop: 16 },
  divider: { marginVertical: 8 },
  rawText: { color: '#4a5568' },
  meta: { color: '#718096', marginTop: 6 },
  warn: { color: '#c05621', marginTop: 8 },
  matchedBox: { marginTop: 4 },
  fieldRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 4 },
  fieldLabel: { color: '#718096' },
  stamp: { alignSelf: 'flex-start', marginBottom: 12, backgroundColor: '#fefcbf' },
});
