/**
 * BlendRatioCard — 缺口 06「汇合配比校验」的 App 报工卡片。
 *
 * ## UX Flow Analysis（ux-flow 门控产出，不可删除）
 *
 * ### 用户画像
 * 操作员 (operator) — 文化程度不高, 只会照着屏幕填数 — 在绞肉/搅拌这类多种主料混合的工序,
 * 一边称料一边在手机上选批次填用量, 然后点「提交领料」。
 *
 * ### 用户旅程
 * | 步骤 | 用户看到 | 用户操作 | 期望结果 |
 * |------|---------|---------|---------|
 * | 1 | 领料批次列表 | 选牛肉/猪肉/脂肪各一批并填用量 | 下方出现「配比核对」, 每种料一行: 配方 % / 实际 % / 偏差 |
 * | 2 | 偏差都在 ±2 个百分点内 (绿) | 直接点提交 | 提交成功 |
 * | 3 | 某种料偏差超出 (红, 写明「多了/少了几个百分点」) | 先回去核对用量; 确实如此就点一个原因按钮 | 原因按钮高亮, 提交按钮可点 |
 * | 4 | 选了「其他」 | 在出现的输入框写一句原因 | 写了才可提交 |
 *
 * ### 摩擦点清单
 * | # | 摩擦点描述 | 严重程度 | 来源规则 |
 * |---|-----------|---------|---------|
 * | F1 | 填完提交才被后端 409 告知配比不对 | HIGH | fool-proof Rule 1 |
 * | F2 | 原因让操作员自由打字, 写不出来也没统计价值 | HIGH | fool-proof Rule 3 |
 * | F3 | 百分比/「百分点」概念对操作员抽象 | MED | 内联 UX 规则: 信息密度 |
 * | F4 | 原因按钮太小误触 | MED | 内联 UX 规则: 触摸目标 ≥44pt |
 * | F5 | 被拦后不知道下一步做什么 | MED | fool-proof Rule 5 |
 *
 * ### 每个摩擦点的设计回应
 * - F1 → 边填边算, 卡片就在批次列表下方, 超差行标红; 没选原因时提交按钮置灰 + 卡片里一句话说明。
 * - F2 → 5 个固定原因按钮 (原料规格差异 / 称重误差 / 按主管口头调整 / 原料不足 / 其他), 只有「其他」才出输入框。
 * - F3 → 每行写「配方 30% → 实际 41.7%」, 偏差直接写成「多 11.7 个百分点 / 少 5 个百分点」, 不出现 ± 符号。
 * - F4 → 原因按钮用 TouchableRipple (点击有 ripple 反馈), minHeight 44、间距 8, 选中高亮。
 * - F5 → 超差时卡片标题下一句「先核对用量; 确实这样投就选个原因」, 告诉他两条路。
 */
import React from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';
import { TouchableRipple } from 'react-native-paper';
import {
  BLEND_REASONS,
  BLEND_REASON_OTHER,
  formatPercent,
  type BlendEvaluation,
} from '../../utils/blendRatio';

interface Props {
  evaluation: BlendEvaluation;
  reason: string;
  note: string;
  onReasonChange: (reason: string) => void;
  onNoteChange: (note: string) => void;
  disabled?: boolean;
}

function deviationText(pp: number): string {
  const v = Number(Math.abs(pp).toFixed(1));
  if (v === 0) return '一致';
  return `${pp > 0 ? '多' : '少'} ${v} 个百分点`;
}

export default function BlendRatioCard({ evaluation, reason, note, onReasonChange, onNoteChange, disabled }: Props) {
  if (evaluation.status === 'NOT_APPLICABLE') return null;
  if (evaluation.status === 'SKIPPED') {
    return (
      <View style={styles.card} testID="blend-ratio-card">
        <Text style={styles.title}>配比核对</Text>
        <Text style={styles.muted} testID="blend-ratio-skipped">{evaluation.reason}</Text>
      </View>
    );
  }
  const reasonArea = (hint: string) => (
    <View testID="blend-ratio-reason-area">
      <Text style={styles.overHint}>{hint}</Text>
      <View style={styles.reasons}>
        {BLEND_REASONS.map((r) => {
          const selected = reason === r;
          return (
            <TouchableRipple
              key={r}
              testID={`blend-ratio-reason-${r}`}
              accessibilityRole="button"
              accessibilityState={{ selected, disabled: !!disabled }}
              disabled={disabled}
              onPress={() => onReasonChange(r)}
              borderless={false}
              style={[styles.reasonBtn, selected && styles.reasonBtnSelected]}
            >
              <Text style={[styles.reasonText, selected && styles.reasonTextSelected]}>{r}</Text>
            </TouchableRipple>
          );
        })}
      </View>
      {reason === BLEND_REASON_OTHER ? (
        <TextInput
          testID="blend-ratio-note"
          value={note}
          onChangeText={onNoteChange}
          editable={!disabled}
          placeholder="请写一句具体原因"
          maxLength={200}
          style={styles.note}
        />
      ) : null}
    </View>
  );
  if (evaluation.status === 'SECRET') {
    // 工艺保密: 不显示配方份额; 后端照常按真实份额校验, 超差会要求原因 —— 原因要一直能选, 否则卡死
    return (
      <View style={styles.card} testID="blend-ratio-card">
        <Text style={styles.title}>配比核对</Text>
        <Text style={styles.muted} testID="blend-ratio-secret">该产品工艺已保密，本页不显示配方份额、不能预先核对。</Text>
        {reasonArea('提交时如提示「配比偏差」，请选一个原因：')}
      </View>
    );
  }
  const over = evaluation.status === 'OVER';
  return (
    <View style={[styles.card, over && styles.cardOver]} testID="blend-ratio-card">
      <Text style={styles.title}>配比核对</Text>
      <Text style={styles.muted}>按工艺配方，每种主料允许差 {evaluation.tolerancePp} 个百分点</Text>
      {evaluation.lines.map((line) => (
        <View key={line.id} style={styles.row} testID={`blend-ratio-line-${line.id}`}>
          <Text style={[styles.name, line.over && styles.overText]} numberOfLines={1}>{line.name}</Text>
          <Text style={[styles.value, line.over && styles.overText]}>
            配方 {formatPercent(line.recipePercent)} → 实际 {formatPercent(line.actualPercent)}
          </Text>
          <Text style={[styles.dev, line.over ? styles.overText : styles.okText]} testID={`blend-ratio-dev-${line.id}`}>
            {deviationText(line.deviationPp)}
          </Text>
        </View>
      ))}
      {over ? reasonArea('配比超出允许范围。先核对用量；确实这样投料，请选一个原因：') : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { marginTop: 12, padding: 12, borderRadius: 8, borderWidth: 1, borderColor: '#e4e7ed', backgroundColor: '#fff' },
  cardOver: { borderColor: '#fab6b6', backgroundColor: '#fef0f0' },
  title: { fontSize: 16, fontWeight: '700', color: '#303133' },
  muted: { fontSize: 13, color: '#909399', marginTop: 2, marginBottom: 6 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 6, gap: 8 },
  name: { width: 72, fontSize: 15, color: '#303133' },
  value: { flex: 1, fontSize: 15, color: '#303133' },
  dev: { fontSize: 15, fontWeight: '600' },
  okText: { color: '#67c23a' },
  overText: { color: '#f56c6c', fontWeight: '700' },
  overHint: { marginTop: 8, fontSize: 14, color: '#f56c6c' },
  reasons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  reasonBtn: {
    minHeight: 44, minWidth: 44, paddingHorizontal: 14, justifyContent: 'center',
    borderRadius: 22, borderWidth: 1, borderColor: '#dcdfe6', backgroundColor: '#fff',
  },
  reasonBtnSelected: { borderColor: '#409eff', backgroundColor: '#ecf5ff' },
  reasonText: { fontSize: 15, color: '#606266' },
  reasonTextSelected: { color: '#409eff', fontWeight: '700' },
  note: {
    marginTop: 8, minHeight: 44, borderWidth: 1, borderColor: '#dcdfe6', borderRadius: 6,
    paddingHorizontal: 10, fontSize: 16, backgroundColor: '#fff',
  },
});
