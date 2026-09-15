/**
 * SourceIndividualFormScreen — 源头个体建档 / 编辑
 *
 * 两种进入方式:
 *  · 带 `tagNumber` —— 从拍照核验「登记新个体」过来, 耳标号预填。
 *  · 带 `individualId` —— 打开一份已有档案(查看 + 编辑)。
 *  · 两个都不带 —— 空白建档, 也合法(现场先建档后挂标)。
 *
 * ⚠️ 三种进入方式都能走 ⇒ 路由参数**整体可选**, 且用 `route.params ?? {}` 取值 ——
 *    本仓 40 个屏踩过「不带参数打开当场 destructure 崩」, 这一屏不加入那个队列。
 *
 * ⛔ 只有档案编号必填。其余留空就是留空 —— 逼现场把 species/breed 填满只会得到
 *    随手编的值, 那比空值更糟(空值至少是诚实的)。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Divider, HelperText, Text, TextInput } from 'react-native-paper';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';

import { appAlert, AppDialogHost } from '../../components/ui/AppDialog';
import {
  sourceIndividualApiClient,
  type DnaVerificationStatus,
  type SourceIndividual,
} from '../../services/api/sourceIndividualApiClient';
import type { ProcessingStackParamList } from '../../types/navigation';
import { dnaStatusText } from '../traceability/components/dnaStatus';

type Nav = NativeStackNavigationProp<ProcessingStackParamList, 'SourceIndividualForm'>;
type Route = RouteProp<ProcessingStackParamList, 'SourceIndividualForm'>;

const DNA_STATUSES: DnaVerificationStatus[] = ['UNSAMPLED', 'SAMPLED', 'MATCHED', 'MISMATCH'];

type FormState = {
  individualCode: string;
  tagNumber: string;
  species: string;
  breed: string;
  sex: string;
  birthDate: string;
  originFarm: string;
  externalGeneBankId: string;
  dnaSampleCode: string;
  dnaVerificationStatus: DnaVerificationStatus;
  notes: string;
};

/** 已选中的父本/母本 —— 存 id 与一个给人看的标签, ⛔ 不只存 id(屏上就只剩一串 UUID)。 */
type ParentPick = { id: string; label: string } | null;

type ParentRole = 'sire' | 'dam';

const EMPTY: FormState = {
  individualCode: '',
  tagNumber: '',
  species: '',
  breed: '',
  sex: '',
  birthDate: '',
  originFarm: '',
  externalGeneBankId: '',
  dnaSampleCode: '',
  dnaVerificationStatus: 'UNSAMPLED',
  notes: '',
};

export default function SourceIndividualFormScreen() {
  const { t } = useTranslation('processing');
  const navigation = useNavigation<Nav>();
  const route = useRoute<Route>();
  // ⚠️ 不 destructure route.params 本体 —— 它可能是 undefined。
  const { tagNumber: prefillTag, individualId } = route.params ?? {};

  const [form, setForm] = useState<FormState>({ ...EMPTY, tagNumber: prefillTag ?? '' });
  const [loading, setLoading] = useState(Boolean(individualId));
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // 系谱选择: 父本 / 母本。按 individualCode / tagNumber 搜索既有个体后挑一个。
  const [sire, setSire] = useState<ParentPick>(null);
  const [dam, setDam] = useState<ParentPick>(null);
  const [pickerRole, setPickerRole] = useState<ParentRole | null>(null);
  const [pickerKeyword, setPickerKeyword] = useState('');
  const [pickerResults, setPickerResults] = useState<SourceIndividual[]>([]);
  const [pickerSearching, setPickerSearching] = useState(false);
  /** 搜过了但一条没有 —— 与「还没搜过」是两件事, ⛔ 不能都用空列表表示。 */
  const [pickerSearched, setPickerSearched] = useState(false);

  const isEdit = Boolean(individualId);

  useEffect(() => {
    if (!individualId) {
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const existing: SourceIndividual = await sourceIndividualApiClient.getById(individualId);
        if (cancelled) return;
        setForm({
          individualCode: existing.individualCode ?? '',
          tagNumber: existing.tagNumber ?? '',
          species: existing.species ?? '',
          breed: existing.breed ?? '',
          sex: existing.sex ?? '',
          birthDate: existing.birthDate ?? '',
          originFarm: existing.originFarm ?? '',
          externalGeneBankId: existing.externalGeneBankId ?? '',
          dnaSampleCode: existing.dnaSampleCode ?? '',
          dnaVerificationStatus: existing.dnaVerificationStatus ?? 'UNSAMPLED',
          notes: existing.notes ?? '',
        });
        // ⚠️ 已有的系谱只拿得到 id。这里**不再去查一遍**父本档案换个好看的名字 ——
        //    多一次网络往返, 而它失败时又得决定「显示什么」。直接把 id 显示出来,
        //    诚实但不好看; 要换名字请换成后端一次带出来(那是接口的事, 不是这一屏的事)。
        setSire(existing.sireIndividualId ? { id: existing.sireIndividualId, label: existing.sireIndividualId } : null);
        setDam(existing.damIndividualId ? { id: existing.damIndividualId, label: existing.damIndividualId } : null);
      } catch (error) {
        if (cancelled) return;
        // ⛔ 加载失败就把错误说出来, 不留一张空表单让人以为「这份档案是空的」。
        setLoadError(error instanceof Error ? error.message : t('sourceIndividual.form.loadFailed'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [individualId, t]);

  const set = useCallback(
    <K extends keyof FormState>(key: K, value: FormState[K]) =>
      setForm((prev) => ({ ...prev, [key]: value })),
    []
  );

  const save = useCallback(async () => {
    if (!form.individualCode.trim()) {
      appAlert(t('sourceIndividual.form.invalidTitle'), t('sourceIndividual.form.codeRequired'));
      return;
    }
    setSaving(true);
    try {
      const payload = {
        tagNumber: blankToUndefined(form.tagNumber),
        species: blankToUndefined(form.species),
        breed: blankToUndefined(form.breed),
        sex: blankToUndefined(form.sex),
        birthDate: blankToUndefined(form.birthDate),
        originFarm: blankToUndefined(form.originFarm),
        externalGeneBankId: blankToUndefined(form.externalGeneBankId),
        dnaSampleCode: blankToUndefined(form.dnaSampleCode),
        dnaVerificationStatus: form.dnaVerificationStatus,
        // 没选就是 undefined; 选了就把 id 原样发出去。
        // ⛔ 后端查不到会回 422 —— 那个错误要让用户看见, 前端不许吞掉当「没填」。
        sireIndividualId: sire?.id,
        damIndividualId: dam?.id,
        notes: blankToUndefined(form.notes),
      };
      if (individualId) {
        await sourceIndividualApiClient.update(individualId, payload);
      } else {
        await sourceIndividualApiClient.create({
          individualCode: form.individualCode.trim(),
          ...payload,
        });
      }
      appAlert(t('sourceIndividual.form.savedTitle'), t('sourceIndividual.form.savedBody'), [
        { text: t('sourceIndividual.form.ok'), onPress: () => navigation.goBack() },
      ]);
    } catch (error) {
      appAlert(
        t('sourceIndividual.form.saveFailedTitle'),
        error instanceof Error ? error.message : t('sourceIndividual.form.saveFailedUnknown')
      );
    } finally {
      setSaving(false);
    }
  }, [form, sire, dam, individualId, navigation, t]);

  const openPicker = useCallback((role: ParentRole) => {
    setPickerRole(role);
    setPickerKeyword('');
    setPickerResults([]);
    setPickerSearched(false);
  }, []);

  const searchParents = useCallback(async () => {
    setPickerSearching(true);
    try {
      const page = await sourceIndividualApiClient.list({ size: 20, keyword: pickerKeyword.trim() });
      // ⛔ 不许把自己列进候选 —— 后端会因「指向自己」422, 让它出现在列表里只是在
      //    邀请用户撞一次错误。
      setPickerResults(page.content.filter((row) => row.id !== individualId));
      setPickerSearched(true);
    } catch (error) {
      appAlert(
        t('sourceIndividual.form.pedigreeSearchFailed'),
        error instanceof Error ? error.message : t('sourceIndividual.form.saveFailedUnknown')
      );
    } finally {
      setPickerSearching(false);
    }
  }, [pickerKeyword, individualId, t]);

  const choose = useCallback(
    (row: SourceIndividual) => {
      const pick: ParentPick = {
        id: row.id,
        label: row.tagNumber ? `${row.individualCode} · ${row.tagNumber}` : row.individualCode,
      };
      if (pickerRole === 'sire') setSire(pick);
      if (pickerRole === 'dam') setDam(pick);
      setPickerRole(null);
    },
    [pickerRole]
  );

  if (loading) {
    return (
      <View style={styles.center} testID="source-individual-form-loading">
        <ActivityIndicator />
      </View>
    );
  }

  if (loadError) {
    return (
      <View style={styles.center} testID="source-individual-form-error">
        <AppDialogHost />
        <Text variant="bodyMedium">{loadError}</Text>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <AppDialogHost />

      <Text variant="titleMedium" style={styles.title}>
        {isEdit ? t('sourceIndividual.form.editTitle') : t('sourceIndividual.form.createTitle')}
      </Text>

      <TextInput
        testID="si-individual-code"
        label={t('sourceIndividual.fields.individualCode')}
        value={form.individualCode}
        onChangeText={(v) => set('individualCode', v)}
        // 编号一旦建档就不许改 —— 下游引的是 id, 改编号会让已打印的标签对不上。
        disabled={isEdit}
        mode="outlined"
        style={styles.input}
      />
      {isEdit && (
        <HelperText type="info" visible>
          {t('sourceIndividual.form.codeImmutable')}
        </HelperText>
      )}

      <TextInput
        testID="si-tag-number"
        label={t('sourceIndividual.fields.tagNumber')}
        value={form.tagNumber}
        onChangeText={(v) => set('tagNumber', v)}
        mode="outlined"
        style={styles.input}
      />
      <HelperText type="info" visible>
        {t('sourceIndividual.form.tagNotUnique')}
      </HelperText>

      <TextInput
        testID="si-species"
        label={t('sourceIndividual.fields.species')}
        value={form.species}
        onChangeText={(v) => set('species', v)}
        mode="outlined"
        style={styles.input}
      />
      <TextInput
        testID="si-breed"
        label={t('sourceIndividual.fields.breed')}
        value={form.breed}
        onChangeText={(v) => set('breed', v)}
        mode="outlined"
        style={styles.input}
      />
      <TextInput
        testID="si-sex"
        label={t('sourceIndividual.fields.sex')}
        value={form.sex}
        onChangeText={(v) => set('sex', v)}
        mode="outlined"
        style={styles.input}
      />
      <TextInput
        testID="si-birth-date"
        label={t('sourceIndividual.fields.birthDate')}
        placeholder="YYYY-MM-DD"
        value={form.birthDate}
        onChangeText={(v) => set('birthDate', v)}
        mode="outlined"
        style={styles.input}
      />
      <TextInput
        testID="si-origin-farm"
        label={t('sourceIndividual.fields.originFarm')}
        value={form.originFarm}
        onChangeText={(v) => set('originFarm', v)}
        mode="outlined"
        style={styles.input}
      />

      <Divider style={styles.divider} />
      <Text variant="labelLarge">{t('sourceIndividual.form.pedigreeSection')}</Text>
      <HelperText type="info" visible>
        {t('sourceIndividual.form.pedigreeHint')}
      </HelperText>

      <ParentRow
        testID="si-sire-row"
        label={t('sourceIndividual.fields.sire')}
        pick={sire}
        emptyText={t('sourceIndividual.form.pedigreeNotSet')}
        chooseText={t('sourceIndividual.form.pedigreeChoose')}
        clearText={t('sourceIndividual.form.pedigreeClear')}
        onChoose={() => openPicker('sire')}
        onClear={() => setSire(null)}
      />
      <ParentRow
        testID="si-dam-row"
        label={t('sourceIndividual.fields.dam')}
        pick={dam}
        emptyText={t('sourceIndividual.form.pedigreeNotSet')}
        chooseText={t('sourceIndividual.form.pedigreeChoose')}
        clearText={t('sourceIndividual.form.pedigreeClear')}
        onChoose={() => openPicker('dam')}
        onClear={() => setDam(null)}
      />

      {pickerRole !== null && (
        <View style={styles.picker} testID="si-parent-picker">
          <TextInput
            testID="si-parent-search-input"
            label={t('sourceIndividual.form.pedigreeSearchLabel')}
            value={pickerKeyword}
            onChangeText={setPickerKeyword}
            mode="outlined"
            style={styles.input}
            onSubmitEditing={searchParents}
          />
          <View style={styles.pickerActions}>
            <Button
              testID="si-parent-search-button"
              mode="contained-tonal"
              loading={pickerSearching}
              disabled={pickerSearching}
              onPress={searchParents}
            >
              {t('sourceIndividual.form.pedigreeSearch')}
            </Button>
            <Button testID="si-parent-cancel-button" onPress={() => setPickerRole(null)}>
              {t('sourceIndividual.form.pedigreeCancel')}
            </Button>
          </View>

          {/* 「搜过了没有」与「还没搜」分开说 —— ⛔ 不用同一个空列表表示两件事。 */}
          {pickerSearched && pickerResults.length === 0 && (
            <Text variant="bodySmall" style={styles.pickerEmpty} testID="si-parent-no-result">
              {t('sourceIndividual.form.pedigreeNoResult')}
            </Text>
          )}
          {pickerResults.map((row) => (
            <Button
              key={row.id}
              testID={`si-parent-option-${row.id}`}
              mode="text"
              onPress={() => choose(row)}
              style={styles.pickerOption}
            >
              {row.tagNumber ? `${row.individualCode} · ${row.tagNumber}` : row.individualCode}
            </Button>
          ))}
        </View>
      )}

      <Divider style={styles.divider} />
      <Text variant="labelLarge">{t('sourceIndividual.form.dnaSection')}</Text>
      <HelperText type="info" visible>
        {t('sourceIndividual.form.dnaExternalOnly')}
      </HelperText>

      <TextInput
        testID="si-gene-bank-id"
        label={t('sourceIndividual.fields.externalGeneBankId')}
        value={form.externalGeneBankId}
        onChangeText={(v) => set('externalGeneBankId', v)}
        mode="outlined"
        style={styles.input}
      />
      <TextInput
        testID="si-dna-sample-code"
        label={t('sourceIndividual.fields.dnaSampleCode')}
        value={form.dnaSampleCode}
        onChangeText={(v) => set('dnaSampleCode', v)}
        mode="outlined"
        style={styles.input}
      />

      <View style={styles.statusRow}>
        {DNA_STATUSES.map((status) => (
          <Button
            key={status}
            testID={`si-dna-status-${status}`}
            mode={form.dnaVerificationStatus === status ? 'contained' : 'outlined'}
            onPress={() => set('dnaVerificationStatus', status)}
            style={styles.statusButton}
            compact
          >
            {/* 与档案 / 时间线 / 拍照核验同一份文案 (dnaStatus.tsx → processing.json), ⛔ 不自己拼键 */}
            {dnaStatusText(t, status)}
          </Button>
        ))}
      </View>

      <TextInput
        testID="si-notes"
        label={t('sourceIndividual.fields.notes')}
        value={form.notes}
        onChangeText={(v) => set('notes', v)}
        mode="outlined"
        multiline
        numberOfLines={3}
        style={styles.input}
      />

      <Button
        testID="si-save-button"
        mode="contained"
        loading={saving}
        disabled={saving}
        onPress={save}
        style={styles.saveButton}
      >
        {t('sourceIndividual.form.save')}
      </Button>
    </ScrollView>
  );
}

/**
 * 系谱的一行: 显示当前选的父本/母本, 或者「未设置」。
 * ⛔ 未设置时显示的是明确的「未设置」文案, 不是空白 —— 空白分不清「没填」和「没渲染出来」。
 */
function ParentRow({
  testID,
  label,
  pick,
  emptyText,
  chooseText,
  clearText,
  onChoose,
  onClear,
}: {
  testID: string;
  label: string;
  pick: ParentPick;
  emptyText: string;
  chooseText: string;
  clearText: string;
  onChoose: () => void;
  onClear: () => void;
}) {
  return (
    <View style={styles.parentRow} testID={testID}>
      <View style={styles.parentText}>
        <Text variant="labelMedium" style={styles.parentLabel}>
          {label}
        </Text>
        <Text variant="bodyMedium" testID={`${testID}-value`}>
          {pick ? pick.label : emptyText}
        </Text>
      </View>
      <Button compact onPress={onChoose} testID={`${testID}-choose`}>
        {chooseText}
      </Button>
      {!!pick && (
        <Button compact onPress={onClear} testID={`${testID}-clear`}>
          {clearText}
        </Button>
      )}
    </View>
  );
}

/** 空串 ⇒ undefined, 让后端把它当成「没填」而不是「填了个空」。 */
function blankToUndefined(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  content: { padding: 16, paddingBottom: 48 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  title: { marginBottom: 16 },
  input: { marginTop: 8, backgroundColor: '#fff' },
  divider: { marginVertical: 16 },
  statusRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 12 },
  statusButton: { marginRight: 8, marginBottom: 8 },
  saveButton: { marginTop: 24 },
  parentRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8 },
  parentText: { flex: 1 },
  parentLabel: { color: '#718096' },
  picker: { marginTop: 12, padding: 12, backgroundColor: '#fff', borderRadius: 8 },
  pickerActions: { flexDirection: 'row', alignItems: 'center', marginTop: 8 },
  pickerOption: { alignSelf: 'flex-start' },
  pickerEmpty: { color: '#718096', marginTop: 8 },
});
