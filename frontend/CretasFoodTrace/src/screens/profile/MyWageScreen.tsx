/**
 * 「我的工资」— 车间工人只读屏
 *
 * 路由: ProfileStack → MyWage (个人中心「我的」tab 里)
 * 后端: GET /api/mobile/{factoryId}/wage/me/monthly —— self-scoped, 不接受任何 workerId 入参
 *
 * <h2>面向低技术素养用户的取舍 (fool-proof)</h2>
 * - 数字大: 头条金额 40px, 分项 18px, 全部 `tabular-nums` 对齐
 * - 术语少: 不出现「计件规则」「效率评分」「阶梯」这类词, 说「一件多少钱」
 * - 触摸目标 ≥44pt
 * - 标题带月份, 工人永远知道在看哪个月 (Rule 2)
 *
 * <h2>⛔ 三条不许破的线</h2>
 * 1. <b>拿不到就显示「—」并说明原因</b>, ⛔ 不许 `?? 0`。所有数值都过
 *    `utils/myWageFormat` 的函数 —— 那里 null 一律返回 `—`。
 *    把「我还没查到」显示成「0 元」会让工人以为白干了一个月。
 * 2. <b>「已发放」「已审核没发」「待审核」三档分开显示, ⛔ 不合成一个数。</b>
 *    后端连总计字段都没给, 就是为了让这里合不起来。
 * 3. <b>不做个人排行 / 不给人打分。</b> owner 裁定质量责任归工厂不归个人,
 *    奖励产出会诱导少报不良品。屏上没有排名、没有 A/B/C/D 评级、
 *    没有「你比同事快多少」。⚠️ 现成的排行组件就在
 *    `src/components/smartbi/MobileRankingList.tsx`, 排行接口在
 *    `wageApiClient.getTopEarners` / `getEfficiencyRanking` —— 一个都不许用。
 *
 * @since 2026-08-25
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Divider,
  Icon,
  IconButton,
  Text,
  TouchableRipple,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { NeoCard, ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import type { ProfileStackParamList } from '../../types/navigation';
import {
  myWageApiClient,
  type MyWageMonthly,
  type MyWagePayroll,
  type MyWagePieceRateInfo,
  type MyWageRecord,
} from '../../services/api/myWageApiClient';
import {
  EMPTY,
  breakdownGap,
  currentMonth,
  explainUnavailable,
  formatCount,
  formatDayLabel,
  formatHours,
  formatMinutesAsHours,
  formatMoney,
  formatMonthLabel,
  formatRate,
  payrollBreakdown,
  payrollBreakdownSum,
  payrollTone,
  shiftMonth,
} from '../../utils/myWageFormat';

type NavProp = NativeStackNavigationProp<ProfileStackParamList, 'MyWage'>;

const TONE_COLOR: Record<string, string> = {
  paid: '#137A4B',
  approved: '#8A6100',
  pending: '#8A6100',
  unknown: theme.colors.onSurfaceVariant,
};

// ──────────────────────────────────────────────────────────────────────────────
// 小块
// ──────────────────────────────────────────────────────────────────────────────

/** 「拿不到」时统一长这样: 一个大「—」+ 一句人话。⛔ 不显示 0。 */
function NotAvailable({ reasonText, testID }: { reasonText: string; testID?: string }) {
  return (
    <View style={styles.naBlock} testID={testID}>
      <Text style={styles.naDash}>{EMPTY}</Text>
      {reasonText.length > 0 && <Text style={styles.naReason}>{reasonText}</Text>}
    </View>
  );
}

function KeyValueRow({
  label,
  value,
  strong,
  danger,
}: {
  label: string;
  value: string;
  strong?: boolean;
  danger?: boolean;
}) {
  return (
    <View style={styles.kvRow}>
      <Text style={styles.kvLabel}>{label}</Text>
      <Text
        style={[
          styles.kvValue,
          strong ? styles.kvValueStrong : null,
          danger ? styles.kvValueDanger : null,
        ]}
      >
        {value}
      </Text>
    </View>
  );
}

/** 一张工资单。分项 + 合计 + 状态; 分项对不上合计时明说。 */
function PayrollCard({ payroll }: { payroll: MyWagePayroll }) {
  const tone = payrollTone(payroll.status);
  const gap = breakdownGap(payroll);
  const rows = payrollBreakdown(payroll);
  // ⚠️ 和 gap 同源 —— 屏里另算一遍会漂, 提示语里的数就跟差额对不上了。
  const breakdownSum = payrollBreakdownSum(payroll);

  return (
    <View style={styles.payrollBlock} testID={`my-wage-payroll-${payroll.id ?? 'x'}`}>
      <View style={styles.payrollHead}>
        <Text style={styles.payrollPeriod}>
          {payroll.periodStart ? formatDayLabel(payroll.periodStart) : EMPTY}
          {' - '}
          {payroll.periodEnd ? formatDayLabel(payroll.periodEnd) : EMPTY}
        </Text>
        <View style={[styles.statusPill, { borderColor: TONE_COLOR[tone] ?? theme.colors.outline }]}>
          <Icon
            source={payroll.paid ? 'check-circle' : 'clock-outline'}
            size={16}
            color={TONE_COLOR[tone] ?? theme.colors.onSurfaceVariant}
          />
          <Text style={[styles.statusText, { color: TONE_COLOR[tone] ?? theme.colors.onSurfaceVariant }]}>
            {payroll.statusLabel ?? EMPTY}
          </Text>
        </View>
      </View>

      <Text style={styles.payrollTotal}>{formatMoney(payroll.totalWage)}</Text>

      {rows.length === 0 ? (
        <Text style={styles.hintSmall}>这张单没有分项明细。</Text>
      ) : (
        rows.map((r) => (
          <KeyValueRow
            key={r.label}
            label={r.label}
            value={`${r.negative ? '-' : ''}${formatMoney(r.value)}`}
            danger={r.negative}
          />
        ))
      )}

      <KeyValueRow label="做了多少件" value={formatCount(payroll.totalPieceCount)} />
      <KeyValueRow label="干了多少小时" value={formatHours(payroll.totalWorkHours)} />

      {/* 🔴 分项加起来对不上合计时明说 —— 这正是工人拿去跟财务核对的那句话。
          ⛔ 不悄悄改合计: 屏上的数必须和财务发的数一致, 差在哪由人来问。 */}
      {gap !== null && Math.abs(gap) >= 0.01 && (
        <View style={styles.warnRow}>
          <Icon source="alert-outline" size={18} color="#8A6100" />
          <Text style={styles.warnText}>
            上面几项加起来是 {formatMoney(breakdownSum)}, 和合计{' '}
            {formatMoney(payroll.totalWage)} 差 {formatMoney(Math.abs(gap))}。
            可以拿这句话去问班组长或厂里。
          </Text>
        </View>
      )}
    </View>
  );
}

/** 一道工序的单价档位。说「一件多少钱」, ⛔ 不说「阶梯计件规则」。 */
function PieceRateBlock({ info }: { info: MyWagePieceRateInfo }) {
  const stage = info.processStageType ?? '未标工序';
  if (info.unavailableReason) {
    return (
      <View style={styles.rateBlock}>
        <Text style={styles.rateStage}>{stage}</Text>
        <Text style={styles.rateLine}>{explainUnavailable(info.unavailableReason)}</Text>
      </View>
    );
  }
  return (
    <View style={styles.rateBlock}>
      <Text style={styles.rateStage}>{stage}</Text>
      <Text style={styles.rateLine}>
        {info.tier1Threshold && info.tier1Threshold > 0
          ? `前 ${info.tier1Threshold} 件, 一件 ${formatRate(info.tier1Rate)}`
          : `一件 ${formatRate(info.tier1Rate)}`}
      </Text>
      {info.tier2Rate !== null && info.tier2Threshold !== null && (
        <Text style={styles.rateLine}>
          做到 {info.tier2Threshold} 件以内, 超出的部分一件 {formatRate(info.tier2Rate)}
        </Text>
      )}
      {info.tier3Rate !== null && (
        <Text style={styles.rateLine}>再往上, 一件 {formatRate(info.tier3Rate)}</Text>
      )}
      <Text style={styles.rateFoot}>这个月这道工序做了 {info.pieceCountInMonth} 件</Text>
    </View>
  );
}

/** 一条报工记录 (天 × 工序)。⛔ 没有金额列 —— 见 myWageApiClient 里的说明。 */
function RecordRow({ record }: { record: MyWageRecord }) {
  const defect = record.defectCount;
  return (
    <View style={styles.recordRow} testID={`my-wage-record-${record.id ?? 'x'}`}>
      <View style={styles.recordLeft}>
        <Text style={styles.recordDate}>{formatDayLabel(record.workDate)}</Text>
        <Text style={styles.recordStage} numberOfLines={2}>
          {record.processStageType ?? '未标工序'}
          {record.workstationName ? ` · ${record.workstationName}` : ''}
        </Text>
      </View>
      <View style={styles.recordRight}>
        <Text style={styles.recordPieces}>{formatCount(record.totalPieceCount)}</Text>
        <Text style={styles.recordSub}>{formatMinutesAsHours(record.effectiveWorkMinutes)}</Text>
        {defect !== null && defect > 0 && (
          <Text style={styles.recordDefect}>不合格 {defect} 件</Text>
        )}
      </View>
    </View>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// 屏
// ──────────────────────────────────────────────────────────────────────────────

export default function MyWageScreen() {
  const navigation = useNavigation<NavProp>();

  const thisMonth = useMemo(() => currentMonth(), []);
  const lastMonth = useMemo(() => shiftMonth(thisMonth, -1), [thisMonth]);

  const [month, setMonth] = useState(thisMonth);
  const [data, setData] = useState<MyWageMonthly | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const load = useCallback(
    async (targetMonth: string, isRefresh = false) => {
      if (isRefresh) {
        setRefreshing(true);
      } else {
        setLoading(true);
      }
      setError(null);
      try {
        const resp = await myWageApiClient.getMonthly(targetMonth);
        if (!mountedRef.current) return;
        if (resp.success) {
          setData(resp.data);
        } else {
          // ⛔ 失败时把 data 清空, 不留上一个月的数字在屏上 —— 那会让工人
          //    以为看到的是这个月的钱。
          setData(null);
          setError(resp.message || '没查到工资, 请稍后再试');
        }
      } catch (err: unknown) {
        if (!mountedRef.current) return;
        setData(null);
        setError(err instanceof Error ? err.message : '网络不通, 请稍后再试');
      } finally {
        if (mountedRef.current) {
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [],
  );

  useEffect(() => {
    void load(month, false);
  }, [load, month]);

  const totals = data?.payrollTotals ?? null;

  return (
    <ScreenWrapper testID="my-wage-screen" edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content
          title="我的工资"
          subtitle={formatMonthLabel(month)}
          titleStyle={styles.appbarTitle}
        />
        <Appbar.Action
          icon="refresh"
          accessibilityLabel="刷新"
          onPress={() => void load(month, true)}
        />
      </Appbar.Header>

      {/* 月份切换 —— 只给本月/上月两个大按钮, ⛔ 不放日期选择器 (选择器对这批用户太难) */}
      <View style={styles.monthBar}>
        {[
          { key: lastMonth, label: '上个月' },
          { key: thisMonth, label: '这个月' },
        ].map((tab) => (
          <TouchableRipple
            key={tab.key}
            testID={`my-wage-month-${tab.key === thisMonth ? 'current' : 'previous'}`}
            style={[styles.monthTab, month === tab.key && styles.monthTabActive]}
            onPress={() => setMonth(tab.key)}
          >
            <Text style={[styles.monthTabText, month === tab.key && styles.monthTabTextActive]}>
              {tab.label}
            </Text>
          </TouchableRipple>
        ))}
      </View>

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.hint}>正在查你的工资...</Text>
        </View>
      ) : error ? (
        <View style={styles.center} testID="my-wage-error">
          <IconButton icon="alert-circle-outline" size={52} iconColor={theme.colors.error} />
          <Text style={styles.hint}>{error}</Text>
          <Text style={styles.hintSmall}>下拉可以再试一次。</Text>
        </View>
      ) : !data ? (
        <View style={styles.center}>
          <Text style={styles.hint}>没查到工资。</Text>
        </View>
      ) : (
        <ScrollView
          contentContainerStyle={styles.scroll}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={() => void load(month, true)} />
          }
        >
          {/* ── ① 工资单: 发到手的钱, 按状态分三档 ⛔ 不合并 ── */}
          <NeoCard variant="elevated" style={styles.card}>
            <Text style={styles.cardTitle}>{formatMonthLabel(data.month)}的工资</Text>

            {data.payrollUnavailableReason || data.payrolls.length === 0 ? (
              <NotAvailable
                testID="my-wage-no-payroll"
                reasonText={explainUnavailable(data.payrollUnavailableReason)}
              />
            ) : (
              <>
                {/* 🔴 三档各自成行。⛔ 没有「合计」—— 已发放和待审核不是一种钱。 */}
                <View style={styles.toneRow}>
                  <Text style={[styles.toneLabel, { color: TONE_COLOR.paid }]}>已经发到手</Text>
                  <Text style={[styles.toneAmount, { color: TONE_COLOR.paid }]}>
                    {formatMoney(totals?.paidTotal)}
                  </Text>
                </View>
                <Text style={styles.toneSub}>{totals?.paidCount ?? 0} 张单</Text>

                <Divider style={styles.divider} />

                <View style={styles.toneRow}>
                  <Text style={[styles.toneLabel, { color: TONE_COLOR.approved }]}>
                    审核过了, 还没发
                  </Text>
                  <Text style={[styles.toneAmount, { color: TONE_COLOR.approved }]}>
                    {formatMoney(totals?.approvedTotal)}
                  </Text>
                </View>
                <Text style={styles.toneSub}>{totals?.approvedCount ?? 0} 张单</Text>

                <Divider style={styles.divider} />

                <View style={styles.toneRow}>
                  <Text style={[styles.toneLabel, { color: TONE_COLOR.pending }]}>还在等审核</Text>
                  <Text style={[styles.toneAmount, { color: TONE_COLOR.pending }]}>
                    {formatMoney(totals?.pendingTotal)}
                  </Text>
                </View>
                <Text style={styles.toneSub}>{totals?.pendingCount ?? 0} 张单</Text>

                <Text style={styles.noMergeNote}>
                  这三个数不能加在一起 —— 只有「已经发到手」是确定的钱。
                </Text>

                {data.payrolls.map((p) => (
                  <View key={p.id ?? Math.random()}>
                    <Divider style={styles.dividerThick} />
                    <PayrollCard payroll={p} />
                  </View>
                ))}
              </>
            )}
          </NeoCard>

          {/* ── ② 系统试算: 没经过审批, 必须和上面那块分开 ── */}
          <NeoCard variant="outlined" style={styles.card}>
            <Text style={styles.cardTitle}>系统自己算的 (还没人审)</Text>
            <Text style={styles.cardSub}>
              这是机器按你的报工算出来的参考数, 不是发到手的钱, 和工资单可能不一样。
            </Text>
            {data.calculation === null ? (
              <NotAvailable
                testID="my-wage-no-calculation"
                reasonText={explainUnavailable(data.calculationUnavailableReason)}
              />
            ) : (
              <>
                <Text style={styles.bigAmount}>{formatMoney(data.calculation.totalAmount)}</Text>
                <KeyValueRow label="按小时的部分" value={formatMoney(data.calculation.hourlyAmount)} />
                <KeyValueRow label="加班的部分" value={formatMoney(data.calculation.overtimeAmount)} />
                <KeyValueRow label="按件的部分" value={formatMoney(data.calculation.pieceRateAmount)} />
                <KeyValueRow label="总工时" value={formatHours(data.calculation.totalHours)} />
                <KeyValueRow label="加班工时" value={formatHours(data.calculation.overtimeHours)} />
                {/* 后端 notes 里写着「缺 XXX 规则」这类话 —— 有值就说明这个数是不全的, 必须显示 */}
                {data.calculation.notes ? (
                  <View style={styles.warnRow}>
                    <Icon source="alert-outline" size={18} color="#8A6100" />
                    <Text style={styles.warnText}>
                      这个数算得不全: {data.calculation.notes}
                    </Text>
                  </View>
                ) : null}
              </>
            )}
          </NeoCard>

          {/* ── ③ 我干了多少活 ── */}
          <NeoCard variant="elevated" style={styles.card}>
            <Text style={styles.cardTitle}>这个月干了多少</Text>
            {data.work.recordCount === 0 ? (
              <View style={styles.naBlock} testID="my-wage-no-records">
                <Text style={styles.naDash}>{EMPTY}</Text>
                {/* ⛔ 空态明说「还没有报工」, 不显示 0 元 / 0 件 */}
                <Text style={styles.naReason}>
                  {formatMonthLabel(data.month)}还没有你的报工记录。如果你确实干了活,
                  可以找班组长确认报工有没有提交上来。
                </Text>
              </View>
            ) : (
              <>
                <View style={styles.statRow}>
                  <View style={styles.statBox}>
                    <Text style={styles.statValue}>{data.work.totalPieceCount}</Text>
                    <Text style={styles.statLabel}>件</Text>
                  </View>
                  <View style={styles.statBox}>
                    <Text style={styles.statValue}>
                      {(data.work.totalWorkMinutes / 60).toFixed(1)}
                    </Text>
                    <Text style={styles.statLabel}>小时</Text>
                  </View>
                  <View style={styles.statBox}>
                    <Text style={styles.statValue}>{data.work.workedDays}</Text>
                    <Text style={styles.statLabel}>天</Text>
                  </View>
                </View>

                {data.work.defectCount > 0 && (
                  <Text style={styles.hintSmall}>
                    其中不合格 {data.work.defectCount} 件。
                  </Text>
                )}

                {/* ⚠️ 有报工没记件数 —— 说出来, 否则上面那个「件」数是偏低的 */}
                {data.work.rowsMissingPieceCount > 0 && (
                  <View style={styles.warnRow}>
                    <Icon source="alert-outline" size={18} color="#8A6100" />
                    <Text style={styles.warnText}>
                      有 {data.work.rowsMissingPieceCount} 条报工没记件数, 上面的件数是偏少的。
                    </Text>
                  </View>
                )}

                <Divider style={styles.divider} />
                <Text style={styles.sectionTitle}>一天一条</Text>
                <Text style={styles.cardSub}>
                  同一天同一道工序报了几次会并成一条。这里不写钱 ——
                  按件的单价是分档的, 一条一条的钱加起来跟月底的数对不上。
                </Text>
                {data.records.map((r, idx) => (
                  <View key={r.id ?? `r-${idx}`}>
                    {idx > 0 && <Divider style={styles.divider} />}
                    <RecordRow record={r} />
                  </View>
                ))}
              </>
            )}
          </NeoCard>

          {/* ── ④ 一件多少钱 ── */}
          <NeoCard variant="outlined" style={styles.card}>
            <Text style={styles.cardTitle}>一件多少钱</Text>
            <Text style={styles.cardSub}>你的工资算法: {data.wageModeLabel}</Text>
            {data.mixedFormulaHint ? (
              <Text style={styles.cardSub}>{data.mixedFormulaHint}</Text>
            ) : null}

            {data.pieceRates.length === 0 ? (
              <Text style={styles.hintSmall}>这个月没有按件的活。</Text>
            ) : (
              data.pieceRates.map((info, idx) => (
                <PieceRateBlock key={info.processStageType ?? `pr-${idx}`} info={info} />
              ))
            )}

            {data.hourlyRate !== null ? (
              <View style={styles.rateBlock}>
                <Text style={styles.rateStage}>按小时</Text>
                <Text style={styles.rateLine}>
                  一小时 {formatMoney(data.hourlyRate.baseHourlyRate)}
                  {data.hourlyRate.overtimeMultiplier !== null
                    ? `, 加班按 ${data.hourlyRate.overtimeMultiplier} 倍`
                    : ''}
                </Text>
              </View>
            ) : (
              data.hourlyRateUnavailableReason !== 'NOT_APPLICABLE_FOR_MODE' && (
                <View style={styles.rateBlock}>
                  <Text style={styles.rateStage}>按小时</Text>
                  <Text style={styles.rateLine}>
                    {explainUnavailable(data.hourlyRateUnavailableReason)}
                  </Text>
                </View>
              )
            )}
          </NeoCard>

          <Text style={styles.footNote}>
            这里只看得到你自己的工资。数字不对可以找班组长, 把上面的日期和件数报给他。
          </Text>
        </ScrollView>
      )}
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  appbarTitle: { fontWeight: '700', fontSize: 20 },
  monthBar: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingVertical: 10,
    gap: 12,
    backgroundColor: theme.colors.surface,
  },
  monthTab: {
    flex: 1,
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: theme.colors.outline,
  },
  monthTabActive: {
    backgroundColor: theme.colors.primaryContainer,
    borderColor: theme.colors.primary,
  },
  monthTabText: { fontSize: 17, fontWeight: '600', color: theme.colors.onSurfaceVariant },
  monthTabTextActive: { color: theme.colors.primary },

  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  hint: { marginTop: 12, textAlign: 'center', fontSize: 17, lineHeight: 26, color: theme.colors.onSurfaceVariant },
  hintSmall: { marginTop: 6, fontSize: 14, lineHeight: 21, color: theme.colors.onSurfaceVariant },

  scroll: { padding: 16, paddingBottom: 40, gap: 16 },
  card: { gap: 6 },
  cardTitle: { fontSize: 19, fontWeight: '700', color: theme.colors.onSurface },
  cardSub: { fontSize: 14, lineHeight: 21, color: theme.colors.onSurfaceVariant },
  sectionTitle: { fontSize: 17, fontWeight: '700', color: theme.colors.onSurface, marginTop: 4 },

  // 「拿不到」块 —— ⛔ 这里永远是「—」, 不是 0
  naBlock: { paddingVertical: 12, gap: 6 },
  naDash: { fontSize: 40, fontWeight: '700', color: theme.colors.onSurfaceVariant },
  naReason: { fontSize: 15, lineHeight: 23, color: theme.colors.onSurfaceVariant },

  toneRow: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between', gap: 8, marginTop: 8 },
  toneLabel: { fontSize: 16, fontWeight: '600', flexShrink: 1 },
  toneAmount: { fontSize: 30, fontWeight: '700', fontVariant: ['tabular-nums'] },
  toneSub: { fontSize: 13, color: theme.colors.onSurfaceVariant },
  noMergeNote: {
    marginTop: 12,
    fontSize: 14,
    lineHeight: 21,
    color: theme.colors.onSurfaceVariant,
    fontStyle: 'italic',
  },

  bigAmount: { fontSize: 36, fontWeight: '700', color: theme.colors.onSurface, fontVariant: ['tabular-nums'], marginVertical: 8 },

  divider: { marginVertical: 8 },
  dividerThick: { marginVertical: 14, height: 1 },

  kvRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 36, gap: 12 },
  kvLabel: { fontSize: 16, color: theme.colors.onSurfaceVariant, flexShrink: 1 },
  kvValue: { fontSize: 18, color: theme.colors.onSurface, fontVariant: ['tabular-nums'] },
  kvValueStrong: { fontWeight: '700' },
  kvValueDanger: { color: theme.colors.error },

  payrollBlock: { gap: 4 },
  payrollHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  payrollPeriod: { fontSize: 15, color: theme.colors.onSurfaceVariant, flexShrink: 1 },
  statusPill: { flexDirection: 'row', alignItems: 'center', gap: 4, borderWidth: 1, borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  statusText: { fontSize: 14, fontWeight: '700' },
  payrollTotal: { fontSize: 34, fontWeight: '700', color: theme.colors.onSurface, fontVariant: ['tabular-nums'], marginVertical: 6 },

  warnRow: { flexDirection: 'row', gap: 8, marginTop: 10, padding: 10, borderRadius: 8, backgroundColor: '#FFF8E6' },
  warnText: { flex: 1, fontSize: 14, lineHeight: 21, color: '#6B4E00' },

  statRow: { flexDirection: 'row', justifyContent: 'space-around', marginVertical: 10 },
  statBox: { alignItems: 'center', minWidth: 72 },
  statValue: { fontSize: 30, fontWeight: '700', color: theme.colors.onSurface, fontVariant: ['tabular-nums'] },
  statLabel: { fontSize: 14, color: theme.colors.onSurfaceVariant, marginTop: 2 },

  recordRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 12, minHeight: 48, gap: 12 },
  recordLeft: { flex: 1, gap: 2 },
  recordDate: { fontSize: 17, fontWeight: '700', color: theme.colors.onSurface },
  recordStage: { fontSize: 14, color: theme.colors.onSurfaceVariant },
  recordRight: { alignItems: 'flex-end', gap: 2 },
  recordPieces: { fontSize: 20, fontWeight: '700', color: theme.colors.onSurface, fontVariant: ['tabular-nums'] },
  recordSub: { fontSize: 14, color: theme.colors.onSurfaceVariant, fontVariant: ['tabular-nums'] },
  recordDefect: { fontSize: 13, color: theme.colors.error },

  rateBlock: { marginTop: 12, gap: 3 },
  rateStage: { fontSize: 17, fontWeight: '700', color: theme.colors.onSurface },
  rateLine: { fontSize: 15, lineHeight: 23, color: theme.colors.onSurface },
  rateFoot: { fontSize: 14, color: theme.colors.onSurfaceVariant },

  footNote: { fontSize: 14, lineHeight: 21, color: theme.colors.onSurfaceVariant, textAlign: 'center', paddingHorizontal: 8 },
});
