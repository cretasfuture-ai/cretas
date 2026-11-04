/**
 * ReworkRatioReasonModal — 缺口 03「回料比例上限」: 领用回料半成品报工被后端拦下 (占比超过产品上限) 时,
 * 让操作员选一个原因再继续提交。
 *
 * ## UX Flow Analysis（ux-flow 门控产出，不可删除）
 *
 * ### 用户画像
 * 操作员 (operator) — 文化程度不高, 只会照着屏幕点 — 在本道把库存里的回料半成品领来混进主料,
 * 选好半成品批次、填完投入量, 点「提交投入」。
 *
 * ### 用户旅程
 * | 步骤 | 用户看到 | 用户操作 | 期望结果 |
 * |------|---------|---------|---------|
 * | 1 | 投入页 | 选回料批次、填投入量、点提交 | 占比没超: 直接成功, 不弹任何东西 |
 * | 2 | 占比超了: 弹窗第一句写「回料占比 X%，超过该产品上限 Y%」 | 先回去核对用量; 确实这样投就点一个原因按钮 | 原因按钮高亮, 「继续提交」可点 |
 * | 3 | 选了「其他」 | 在出现的输入框写一句原因 | 写了才可提交 |
 * | 4 | 不想继续 | 点「返回核对」 | 弹窗关闭, 已填的批次和数量原样保留 |
 *
 * ### 摩擦点清单
 * | # | 摩擦点描述 | 严重程度 | 来源规则 |
 * |---|-----------|---------|---------|
 * | F1 | 被拦只看到一条失败提示, 不知道下一步做什么 | HIGH | fool-proof Rule 5 |
 * | F2 | 原因让操作员自由打字, 写不出来也没统计价值 | HIGH | fool-proof Rule 3 |
 * | F3 | 百分比概念抽象, 看不懂「超多少」 | MED | 内联 UX 规则: 信息密度 |
 * | F4 | 原因按钮太小误触 | MED | 内联 UX 规则: 触摸目标 ≥44pt |
 * | F5 | 选错后已填数据被清空 | MED | 内联 UX 规则: 错误恢复 |
 *
 * ### 每个摩擦点的设计回应
 * - F1 → 后端 409 后弹窗, 标题写清「回料超过上限」, 第一句就是后端算好的占比与上限, 第二句告诉两条路 (核对 / 选原因继续)。
 * - F2 → 5 个固定原因按钮, 只有「其他」才出输入框。
 * - F3 → 直接用后端那句「占比 X%，超过上限 Y%」, 不在前端换算。
 * - F4 → 原因按钮 TouchableRipple, minHeight 44、间距 8, 选中高亮。
 * - F5 → 弹窗不动页面状态; 「返回核对」只关弹窗, 批次与数量保留。
 */
import React, { useEffect, useState } from 'react';
import { Modal, View, Text, TextInput, StyleSheet, ScrollView } from 'react-native';
import { TouchableRipple } from 'react-native-paper';
import {
  REWORK_REASONS,
  REWORK_REASON_OTHER,
  reworkReasonBlockReason,
  type ReworkRatioInput,
} from '../../utils/reworkRatio';

interface Props {
  /** 后端写好的那句「回料占比 X%，超过该产品上限 Y%…」; null = 不显示弹窗 */
  message: string | null;
  /** 上一次选的原因 (再次被拦时带回, 免得重选) */
  previous?: ReworkRatioInput | null;
  submitting?: boolean;
  onConfirm: (input: ReworkRatioInput) => void;
  onCancel: () => void;
}

export default function ReworkRatioReasonModal({ message, previous, submitting, onConfirm, onCancel }: Props) {
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');

  useEffect(() => {
    if (message != null) {
      setReason(previous?.reason ?? '');
      setNote(previous?.note ?? '');
    }
  }, [message, previous]);

  const block = reworkReasonBlockReason(reason, note);

  return (
    <Modal visible={message != null} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.backdrop}>
        <View style={styles.card} testID="rework-ratio-modal">
          <ScrollView keyboardShouldPersistTaps="handled">
            <Text style={styles.title}>回料超过上限</Text>
            <Text style={styles.message} testID="rework-ratio-message">{message}</Text>
            <Text style={styles.hint}>先核对回料用量；确实要这样投，请选一个原因，原因会随这次报工记录下来：</Text>
            <View style={styles.reasons}>
              {REWORK_REASONS.map((r) => {
                const selected = reason === r;
                return (
                  <TouchableRipple
                    key={r}
                    testID={`rework-ratio-reason-${r}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected, disabled: !!submitting }}
                    disabled={submitting}
                    onPress={() => setReason(r)}
                    borderless={false}
                    style={[styles.reasonBtn, selected && styles.reasonBtnSelected]}
                  >
                    <Text style={[styles.reasonText, selected && styles.reasonTextSelected]}>{r}</Text>
                  </TouchableRipple>
                );
              })}
            </View>
            {reason === REWORK_REASON_OTHER ? (
              <TextInput
                testID="rework-ratio-note"
                value={note}
                onChangeText={setNote}
                editable={!submitting}
                placeholder="请写一句具体原因"
                maxLength={200}
                style={styles.note}
              />
            ) : null}
            {block ? <Text style={styles.block} testID="rework-ratio-block">{block}</Text> : null}
          </ScrollView>
          <View style={styles.actions}>
            <TouchableRipple
              testID="rework-ratio-cancel"
              accessibilityRole="button"
              disabled={submitting}
              onPress={onCancel}
              style={[styles.actionBtn, styles.cancelBtn]}
            >
              <Text style={styles.cancelText}>返回核对</Text>
            </TouchableRipple>
            <TouchableRipple
              testID="rework-ratio-confirm"
              accessibilityRole="button"
              accessibilityState={{ disabled: !!block || !!submitting }}
              disabled={!!block || !!submitting}
              onPress={() => {
                // 置灰只是外观: 这里再拦一道, 没选原因 / 提交中绝不放行
                if (block || submitting) return;
                onConfirm({ reason, note: note.trim() || undefined });
              }}
              style={[styles.actionBtn, styles.confirmBtn, (!!block || !!submitting) && styles.confirmBtnDisabled]}
            >
              <Text style={styles.confirmText}>按此原因继续提交</Text>
            </TouchableRipple>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'center', padding: 20 },
  card: { maxHeight: '85%', backgroundColor: '#fff', borderRadius: 12, padding: 16 },
  title: { fontSize: 18, fontWeight: '700', color: '#303133' },
  message: { marginTop: 8, fontSize: 16, fontWeight: '600', color: '#f56c6c', lineHeight: 22 },
  hint: { marginTop: 8, fontSize: 14, color: '#606266', lineHeight: 20 },
  reasons: { marginTop: 10, flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  reasonBtn: {
    minHeight: 44, minWidth: 44, paddingHorizontal: 12, justifyContent: 'center',
    borderRadius: 8, borderWidth: 1, borderColor: '#dcdfe6', backgroundColor: '#fff',
  },
  reasonBtnSelected: { borderColor: '#409eff', backgroundColor: '#ecf5ff' },
  reasonText: { fontSize: 15, color: '#303133' },
  reasonTextSelected: { color: '#409eff', fontWeight: '700' },
  note: {
    marginTop: 10, minHeight: 44, borderWidth: 1, borderColor: '#dcdfe6', borderRadius: 8,
    paddingHorizontal: 10, fontSize: 15,
  },
  block: { marginTop: 8, fontSize: 13, color: '#f56c6c' },
  actions: { marginTop: 14, flexDirection: 'row', gap: 8 },
  actionBtn: { flex: 1, minHeight: 48, justifyContent: 'center', alignItems: 'center', borderRadius: 8 },
  cancelBtn: { borderWidth: 1, borderColor: '#dcdfe6', backgroundColor: '#fff' },
  cancelText: { fontSize: 15, color: '#606266' },
  confirmBtn: { backgroundColor: '#409eff' },
  confirmBtnDisabled: { backgroundColor: '#a0cfff' },
  confirmText: { fontSize: 15, color: '#fff', fontWeight: '700' },
});
