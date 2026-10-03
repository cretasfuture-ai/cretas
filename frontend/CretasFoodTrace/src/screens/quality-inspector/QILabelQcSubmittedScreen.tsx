import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { RouteProp, useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ActivityIndicator, Button } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { labelQcApi } from '../../services/api/labelQcApi';
import { useAuthStore } from '../../store/authStore';
import { getFactoryId, getUserRole } from '../../types/auth';
import { LabelQcTaskDetail, LabelQcTaskStatus } from '../../types/labelQc';
import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type RouteProps = RouteProp<QualityInspectorStackParamList, 'QILabelQcSubmitted'>;

export interface QILabelQcSubmittedScreenProps {
  managerMode?: boolean;
}

const STATUS_COPY: Record<LabelQcTaskStatus, { title: string; body: string; color: string }> = {
  DRAFT: { title: '草稿待上传', body: '照片尚未全部提交，请返回后重试。', color: QI_COLORS.warning },
  UPLOADING: { title: '照片上传中', body: '请保持网络连接，上传完成后会自动进入初筛。', color: QI_COLORS.secondary },
  QUEUED: { title: '等待 AI 初筛', body: '任务已安全保存，稍后会自动处理。', color: QI_COLORS.secondary },
  ANALYZING: { title: 'AI 正在初筛', body: '正在查找疑似缺白标或缺彩标区域。', color: QI_COLORS.secondary },
  NEEDS_REVIEW: { title: '等待人工审核', body: 'AI 初筛已完成，最终结果由质量审核员确认。', color: QI_COLORS.warning },
  AUTO_PASSED: { title: '本批未发现风险', body: 'AI 初筛没有发现需要人工处理的疑点，本批已自动完成。', color: QI_COLORS.success },
  REVIEWED: { title: '人工审核已完成', body: '审核结果已经保存，可在手机端查看最终标注。', color: QI_COLORS.success },
  ANALYSIS_FAILED: { title: '等待人工处理', body: '照片已经保存，质量审核员会在审核工作台继续处理。', color: QI_COLORS.danger },
};

export default function QILabelQcSubmittedScreen({ managerMode = false }: QILabelQcSubmittedScreenProps) {
  const route = useRoute<RouteProps>();
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const user = useAuthStore((state) => state.user);
  const factoryId = getFactoryId(user) || undefined;
  const actorKey = `${user?.id ?? 'none'}:${factoryId ?? 'none'}:${getUserRole(user)}`;
  const actorKeyRef = useRef(actorKey);
  const requestSequenceRef = useRef(0);
  const [detail, setDetail] = useState<LabelQcTaskDetail | null>(null);
  const [detailActorKey, setDetailActorKey] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);

  if (actorKeyRef.current !== actorKey) {
    actorKeyRef.current = actorKey;
    requestSequenceRef.current += 1;
  }

  useEffect(() => {
    setDetail(null);
    setDetailActorKey(null);
    setRefreshError(null);
  }, [actorKey]);

  const visibleDetail = detailActorKey === actorKey ? detail : null;
  const visibleDetailStatus = visibleDetail?.task.status;

  const load = useCallback(async () => {
    const requestActorKey = actorKey;
    const requestSequence = ++requestSequenceRef.current;
    if (!factoryId) return;
    if (actorKeyRef.current !== requestActorKey || requestSequence !== requestSequenceRef.current) return;
    try {
      setRefreshError(null);
      const nextDetail = await labelQcApi.getTask(route.params.taskId, factoryId);
      if (actorKeyRef.current !== requestActorKey || requestSequence !== requestSequenceRef.current) return;
      setDetail(nextDetail);
      setDetailActorKey(requestActorKey);
    } catch {
      if (actorKeyRef.current !== requestActorKey || requestSequence !== requestSequenceRef.current) return;
      setRefreshError('状态暂时无法刷新，请稍后重试。');
    }
  }, [actorKey, factoryId, route.params.taskId]);

  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  }, [load]);

  useEffect(() => {
    const initialTimer = setTimeout(() => {
      void loadRef.current();
    }, 0);
    return () => clearTimeout(initialTimer);
  }, [actorKey, route.params.taskId]);

  useEffect(() => {
    const timer = setInterval(() => {
      if (visibleDetailStatus && ['QUEUED', 'ANALYZING'].includes(visibleDetailStatus)) {
        void loadRef.current();
      }
    }, 5000);
    return () => {
      clearInterval(timer);
    };
  }, [actorKey, route.params.taskId, visibleDetailStatus]);
  const status = visibleDetailStatus ?? 'QUEUED';
  const copy = STATUS_COPY[status];
  const context = useMemo(() => {
    if (!visibleDetail) return [];
    const task = visibleDetail.task;
    return [
      ['SKU', `${task.skuCode} · ${task.skuName}`],
      ['批次号', task.batchNumber || '未填写'],
      ['生产日期', task.productionDate],
      ['任务编号', task.id],
    ];
  }, [visibleDetail]);

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 24 }]}
      testID="qi-label-qc-submitted-screen"
    >
      <View style={[styles.statusIcon, { backgroundColor: `${copy.color}1A` }]}>
        {['QUEUED', 'ANALYZING'].includes(status) ? (
          <ActivityIndicator size={44} color={copy.color} />
        ) : (
          <Ionicons
            name={['AUTO_PASSED', 'REVIEWED'].includes(status) ? 'checkmark-circle' : 'person-circle-outline'}
            size={54}
            color={copy.color}
          />
        )}
      </View>
      <Text style={styles.title}>{copy.title}</Text>
      <Text style={styles.body}>{copy.body}</Text>

      <View style={styles.guardrail}>
        <Ionicons name="information-circle-outline" size={22} color="#174A7E" />
        <Text style={styles.guardrailText}>
          只有发现疑点或图片无法判断时才会进入人工审核；AI 自动完成不等同于人工训练真值。
        </Text>
      </View>

      <View style={styles.contextCard}>
        {context.map(([label, value]) => (
          <View key={label} style={styles.contextRow}>
            <Text style={styles.contextLabel}>{label}</Text>
            <Text style={styles.contextValue} selectable={label === '任务编号'}>
              {value}
            </Text>
          </View>
        ))}
        {visibleDetail && (
          <>
            <View style={styles.divider} />
            <View style={styles.contextRow}>
              <Text style={styles.contextLabel}>照片</Text>
              <Text style={styles.contextValue}>{visibleDetail.task.photoCount} 张</Text>
            </View>
            <View style={styles.contextRow}>
              <Text style={styles.contextLabel}>AI 疑似区域</Text>
              <Text style={[styles.contextValue, visibleDetail.task.aiCandidateCount > 0 && styles.warningValue]}>
                {visibleDetail.task.aiCandidateCount} 处（待人工确认）
              </Text>
            </View>
          </>
        )}
      </View>

      {refreshError && (
        <View style={styles.errorCard}>
          <Text style={styles.errorText}>{refreshError}</Text>
          <Button
            mode="text"
            onPress={load}
            testID="qi-label-qc-submitted-refresh-button"
          >
            重新刷新
          </Button>
        </View>
      )}

      {managerMode ? (
        <Button
          mode="contained"
          buttonColor={QI_COLORS.primary}
          contentStyle={styles.buttonContent}
          labelStyle={styles.buttonLabel}
          onPress={() => navigation.replace('QILabelQcQueue')}
          testID="qi-label-qc-submitted-queue-button"
        >
          返回拍检队列
        </Button>
      ) : (
        <>
          <Button
            mode="contained"
            buttonColor={QI_COLORS.primary}
            contentStyle={styles.buttonContent}
            labelStyle={styles.buttonLabel}
            onPress={() => navigation.replace('QILabelQcCreate')}
            testID="qi-label-qc-submitted-new-task-button"
          >
            再拍一批
          </Button>
          <Button
            mode="outlined"
            textColor={QI_COLORS.primary}
            contentStyle={styles.buttonContent}
            style={styles.secondaryButton}
            onPress={() => navigation.navigate('QIHomeTab')}
            testID="qi-label-qc-submitted-home-button"
          >
            返回质检首页
          </Button>
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: QI_COLORS.background },
  content: {
    width: '100%',
    maxWidth: 620,
    alignSelf: 'center',
    padding: 20,
    alignItems: 'stretch',
  },
  statusIcon: {
    width: 86,
    height: 86,
    borderRadius: 43,
    alignSelf: 'center',
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 20,
  },
  title: { marginTop: 18, fontSize: 24, fontWeight: '800', color: QI_COLORS.text, textAlign: 'center' },
  body: { marginTop: 8, fontSize: 15, lineHeight: 22, color: QI_COLORS.textSecondary, textAlign: 'center' },
  guardrail: {
    flexDirection: 'row',
    backgroundColor: '#EAF3FF',
    borderRadius: 12,
    padding: 14,
    marginTop: 24,
  },
  guardrailText: { flex: 1, marginLeft: 10, fontSize: 13, lineHeight: 20, color: '#365D80' },
  contextCard: { backgroundColor: QI_COLORS.card, borderRadius: 14, padding: 16, marginVertical: 18 },
  contextRow: { flexDirection: 'row', alignItems: 'flex-start', paddingVertical: 8 },
  contextLabel: { width: 78, fontSize: 13, color: QI_COLORS.textSecondary },
  contextValue: { flex: 1, fontSize: 14, fontWeight: '600', color: QI_COLORS.text, textAlign: 'right' },
  warningValue: { color: '#A86100' },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: QI_COLORS.border, marginVertical: 4 },
  errorCard: { backgroundColor: '#FFF0F0', borderRadius: 12, padding: 12, marginBottom: 16 },
  errorText: { fontSize: 13, color: '#7D3030', lineHeight: 19 },
  buttonContent: { minHeight: 50 },
  buttonLabel: { fontSize: 16, fontWeight: '700' },
  secondaryButton: { marginTop: 12, borderColor: QI_COLORS.primary },
});
