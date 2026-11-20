/**
 * 质检员 App · 不良品处置 (列表)
 *
 * 纪律排查 B14 补 App 端: 网页 /quality/disposition 按质检模块权限放行, 但质检员不登网页。
 * 两个页签:
 *   - 待处置: 不合格 / 有条件放行的质检记录 → 点开去选处置方式 (QIDispositionExecute)
 *   - 等待审批: 已提交、在等审批的处置; 「批准 / 驳回」只给有 approval_quality_disposition 的人
 *     (与后端 @RequirePermission("approval_quality_disposition:approve") 同一个码; 质检员默认没有 ⇒ 不显示)
 * ⛔ 不写岗位名单 —— 见 utils/qualityClosurePermissions.ts。
 */
import React, { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button, TouchableRipple } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { appAlert, appPrompt } from '../../components/ui/AppDialog';
import {
  qualityDispositionAPI,
  type DispositionCandidate,
  type PendingDisposition,
} from '../../services/api/qualityDispositionApiClient';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import {
  canApproveQualityDisposition,
  canWriteQualityClosure,
} from '../../utils/qualityClosurePermissions';
import {
  ACTION_TEXT,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  actionLabel,
  approverText,
  resolvePendingBatch,
  resultText,
  shared,
} from './qualityClosureShared';

type Nav = NativeStackNavigationProp<QualityInspectorStackParamList>;
type Tab = 'candidates' | 'pending';

export default function QIDispositionScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canWrite = canWriteQualityClosure(user);
  const canApprove = canApproveQualityDisposition(user);

  const [tab, setTab] = useState<Tab>('candidates');
  const [candidates, setCandidates] = useState<DispositionCandidate[]>([]);
  const [pending, setPending] = useState<PendingDisposition[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [approvingId, setApprovingId] = useState<string | null>(null);

  const load = useCallback(async (mode: 'initial' | 'refresh' = 'initial') => {
    if (!factoryId) {
      setError('读不到当前工厂，请退出后重新登录');
      setLoading(false);
      return;
    }
    if (mode === 'refresh') setRefreshing(true); else setLoading(true);
    try {
      const [c, p] = await Promise.all([
        qualityDispositionAPI.listDispositionCandidates(factoryId),
        qualityDispositionAPI.getPendingDispositions(factoryId),
      ]);
      setCandidates(c);
      setPending(p);
      setError('');
    } catch (e) {
      setError(getErrorMsg(e) || '处置列表读取失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const submitApproval = async (row: PendingDisposition, approved: boolean, comment?: string, selfReason?: string) => {
    if (!factoryId || !user) return;
    setApprovingId(row.id);
    try {
      const result = await qualityDispositionAPI.approveDisposition(factoryId, row.id, {
        approved,
        approverId: user.id,
        approverName: user.fullName || user.username,
        comment: comment?.trim() || undefined,
        selfApprovalReason: selfReason?.trim() || undefined,
      });
      appAlert(approved ? '已批准' : '已驳回', result.message);
      await load('refresh');
    } catch (e) {
      appAlert('审批没有成功', `${getErrorMsg(e)}\n\n请确认后重试；内容没有被修改。`);
    } finally {
      setApprovingId(null);
    }
  };

  const onApprove = (row: PendingDisposition) => {
    const title = `批准${actionLabel(row.decisionMade)} — ${resolvePendingBatch(row, candidates).title}`;
    if (user && row.executorId === user.id) {
      // 本人审批 (纪律排查 B3): 申请人就是审批人时必须写原因, 由后端按工厂配置决定允不允许
      appPrompt(title, '这条处置是你自己提交的，批准需要写明原因。', (reason) => {
        void submitApproval(row, true, undefined, reason);
      }, { placeholder: '为什么由本人批准', testID: 'qi-disposition-self-approve-reason' });
      return;
    }
    appAlert(title, '批准后系统会立即执行这个处置。', [
      { text: '取消', style: 'cancel' },
      { text: '批准', onPress: () => { void submitApproval(row, true); } },
    ]);
  };

  const onReject = (row: PendingDisposition) => {
    appPrompt(
      `驳回${actionLabel(row.decisionMade)} — ${resolvePendingBatch(row, candidates).title}`,
      '请写明驳回原因，申请人会看到。',
      (comment) => { void submitApproval(row, false, comment); },
      { placeholder: '驳回原因', testID: 'qi-disposition-reject-reason' },
    );
  };

  const renderCandidates = () => {
    if (candidates.length === 0) {
      return (
        <EmptyBlock
          text="没有待处置的批次"
          hint="质检不合格或有条件放行的批次会出现在这里。"
          actionText="返回首页"
          onAction={() => navigation.navigate('QIHome')}
        />
      );
    }
    return candidates.map((row) => {
      const failed = row.result === 'FAIL' || row.result === 'FAILED';
      return (
        <TouchableRipple
          key={row.id}
          style={shared.card}
          onPress={() => navigation.navigate('QIDispositionExecute', { candidate: row })}
          accessibilityRole="button"
          accessibilityLabel={`处置 ${row.batchNumber ?? ''} ${row.productName ?? ''}`}
          testID={`qi-disposition-candidate-${row.id}`}
        >
          <View>
            <View style={shared.titleRow}>
              <Text style={[shared.cardTitle, shared.titleInRow]} numberOfLines={2}>{row.batchNumber || `批次 #${row.productionBatchId}`}</Text>
              <View style={[shared.tag, shared.tagInRow, { backgroundColor: failed ? '#FDECEC' : '#FFF6E5' }]}>
                <Text style={[shared.tagText, { color: failed ? QI_COLORS.danger : '#B7791F' }]}>{resultText(row.result)}</Text>
              </View>
            </View>
            <Text style={shared.cardSub}>{row.productName || '未登记品名'}</Text>
            <Text style={shared.cardSub}>
              抽检 {Number(row.sampleSize ?? 0)} · 合格 {Number(row.passCount ?? 0)} · 不合格 {Number(row.failCount ?? 0)}
              {row.inspectionDate ? ` · ${String(row.inspectionDate).slice(0, 10)}` : ''}
            </Text>
            <View style={[shared.row, { marginTop: 10 }]}>
              <Text style={[styles.go, { color: canWrite ? QI_COLORS.primary : QI_COLORS.textSecondary }]}>
                {canWrite ? '去处置' : '查看'}
              </Text>
              <Ionicons name="chevron-forward" size={18} color={canWrite ? QI_COLORS.primary : QI_COLORS.textSecondary} />
            </View>
          </View>
        </TouchableRipple>
      );
    });
  };

  const renderPending = () => {
    if (pending.length === 0) {
      return <EmptyBlock text="没有等待审批的处置" hint="需要审批的处置提交后会在这里显示进度。" />;
    }
    return (
      <>
        {!canApprove && (
          <Text style={[shared.note, { marginBottom: 10 }]} testID="qi-disposition-approve-hint">
            这些处置由有审批权限的人处理，你可以在这里看进度。
          </Text>
        )}
        {pending.map((row) => {
          const action = ACTION_TEXT[row.decisionMade];
          const batch = resolvePendingBatch(row, candidates);
          return (
            <View key={row.id} style={shared.card} testID={`qi-disposition-pending-${row.id}`}>
              <View style={shared.titleRow}>
                <Text style={[shared.cardTitle, shared.titleInRow]} numberOfLines={2}>{batch.title}</Text>
                <View style={[shared.tag, shared.tagInRow, { backgroundColor: '#F1F5F9' }]}>
                  <Text style={[shared.tagText, { color: action?.color ?? QI_COLORS.text }]}>{actionLabel(row.decisionMade)}</Text>
                </View>
              </View>
              {batch.productName ? <Text style={shared.cardSub}>{batch.productName}</Text> : null}
              {!batch.matched ? <Text style={shared.note}>批次号没查到，这里显示的是系统编号</Text> : null}
              {row.reason ? <Text style={shared.cardSub}>原因：{row.reason}</Text> : null}
              <Text style={shared.cardSub}>
                申请人：{row.executorName || (row.executorId != null ? `编号 #${row.executorId}` : '-')} · 等待{approverText(row.approvalLevel)}审批
              </Text>
              {canApprove && (
                <View style={[shared.row, { marginTop: 12, gap: 12 }]}>
                  <Button
                    mode="contained"
                    buttonColor={QI_COLORS.primary}
                    style={styles.flexButton}
                    contentStyle={shared.primaryButtonContent}
                    loading={approvingId === row.id}
                    disabled={approvingId != null}
                    onPress={() => onApprove(row)}
                    testID={`qi-disposition-approve-${row.id}`}
                  >
                    批准
                  </Button>
                  <Button
                    mode="outlined"
                    textColor={QI_COLORS.danger}
                    style={styles.flexButton}
                    contentStyle={shared.primaryButtonContent}
                    disabled={approvingId != null}
                    onPress={() => onReject(row)}
                    testID={`qi-disposition-reject-${row.id}`}
                  >
                    驳回
                  </Button>
                </View>
              )}
            </View>
          );
        })}
      </>
    );
  };

  return (
    <View style={shared.screen} testID="qi-disposition-screen">
      <View style={styles.tabs}>
        {([
          ['candidates', `待处置 (${candidates.length})`],
          ['pending', `等待审批 (${pending.length})`],
        ] as const).map(([key, label]) => (
          <TouchableRipple
            key={key}
            style={[styles.tab, tab === key && styles.tabActive]}
            onPress={() => setTab(key)}
            accessibilityRole="tab"
            accessibilityState={{ selected: tab === key }}
            testID={`qi-disposition-tab-${key}`}
          >
            <Text style={[styles.tabText, tab === key && styles.tabTextActive]}>{label}</Text>
          </TouchableRipple>
        ))}
      </View>
      <ScrollView
        contentContainerStyle={shared.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { void load('refresh'); }} />}
      >
        {loading ? <LoadingBlock /> : error ? <ErrorBlock message={error} onRetry={() => { void load(); }} />
          : tab === 'candidates' ? renderCandidates() : renderPending()}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  tabs: {
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderBottomWidth: 1,
    borderBottomColor: QI_COLORS.border,
  },
  tab: { flex: 1, minHeight: 52, alignItems: 'center', justifyContent: 'center', borderBottomWidth: 3, borderBottomColor: 'transparent' },
  tabActive: { borderBottomColor: QI_COLORS.primary },
  tabText: { fontSize: 16, fontWeight: '600', color: QI_COLORS.textSecondary },
  tabTextActive: { color: QI_COLORS.primary, fontWeight: '800' },
  go: { fontSize: 15, fontWeight: '700' },
  flexButton: { flex: 1, borderRadius: 12 },
});
