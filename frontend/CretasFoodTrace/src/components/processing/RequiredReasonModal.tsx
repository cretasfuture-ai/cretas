/**
 * RequiredReasonModal — 后端拦下、要说一个原因才能继续的通用弹窗。用在两处 (纪律排查 2026-11-11):
 * - A12 缺料时开工: 本厂开了「缺料时拦开工」, 签到 / 班组报工页开批次被拦, 选原因后「仍然开工」(原因记进缺料处理记录);
 * - A10 报工超收: 产出超过超收容差上限, 选原因后「仍然提交」(原因随这次报工记录)。
 * 样式与交互照 ReworkRatioReasonModal (回料超限原因), 操作员已经见过这种弹窗。
 *
 * ## UX Flow Analysis（ux-flow 门控产出，不可删除）
 *
 * ### 用户画像
 * 操作员 / 班组长 (operator / workshop_supervisor) — 文化程度不高, 照着屏幕点 — 在车间用手机签到开工、报产出。
 *
 * ### 用户旅程
 * | 步骤 | 用户看到 | 用户操作 | 期望结果 |
 * |------|---------|---------|---------|
 * | 1 | 批次列表 / 报工页 | 点「开始生产」或「提交产出」 | 没被拦: 和以前一样直接成功, 不弹任何东西 |
 * | 2 | 被拦: 弹窗标题写「缺料还没解决」或「产出超过上限」, 第一句是后端算好的「缺什么 / 超多少」 | 先回去核对; 确实要继续就点一个原因按钮 | 原因按钮高亮, 继续按钮可点 |
 * | 3 | 选了「其他」 | 在出现的输入框写一句 | 写了才可继续 |
 * | 4 | 点「仍然开工」/「仍然提交」 | 等待 | 成功提示与没被拦时相同; 原因已留痕 |
 * | 5 | 不想继续 | 点「先不开工」/「返回核对」 | 弹窗关闭, 页面上已填的数量原样保留 |
 *
 * ### 摩擦点清单
 * | # | 摩擦点描述 | 严重程度 | 来源规则 |
 * |---|-----------|---------|---------|
 * | F1 | 工厂一开缺料开关, 手机上开工只看到「开始失败」, 没有任何出路 (A12 死路) | HIGH | fool-proof Rule 5 |
 * | F2 | 超收确认框只问「确认吗」, 不收原因, 事后查不到为什么超收 (A10) | HIGH | fool-proof Rule 3 |
 * | F3 | 让操作员自由打字写原因, 写不出来也没统计价值 | MED | fool-proof Rule 3 |
 * | F4 | 「缺多少 / 超多少」前端自己算容易和后端不一致 | MED | 内联 UX 规则: 信息密度 |
 * | F5 | 原因按钮太小误触 | MED | 内联 UX 规则: 触摸目标 ≥44pt |
 * | F6 | 取消后已填数据被清空 | MED | 内联 UX 规则: 错误恢复 |
 *
 * ### 每个摩擦点的设计回应
 * - F1 → 被 SHORTAGE_CASE_OPEN 拦下时不再弹「开始失败」, 改弹本弹窗, 给出「仍然开工」这条路 (带原因重提)。
 * - F2 → OVER_RECEIPT 的确认框换成本弹窗, 原因随 forceSubmitReason 提交, 后端没有原因直接拒。
 * - F3 → 每种情况 5 个固定原因按钮, 只有「其他」才出输入框。
 * - F4 → 第一句直接用后端那句话, 前端不换算。
 * - F5 → 原因按钮 TouchableRipple, minHeight 44、间距 8, 选中高亮; 底部两个按钮 minHeight 48。
 * - F6 → 弹窗不动页面状态; 取消只关弹窗。
 */
import React, { useEffect, useState } from 'react';
import { Modal, View, Text, TextInput, StyleSheet, ScrollView } from 'react-native';
import { TouchableRipple } from 'react-native-paper';
import { REASON_OTHER, composeReason, reasonBlock } from '../../utils/requiredReason';

interface Props {
  /** 后端写好的那句话 (缺什么 / 超多少); null = 不显示 */
  message: string | null;
  title: string;
  /** 第二句: 告诉用户两条路 */
  hint: string;
  reasons: readonly string[];
  confirmLabel: string;
  cancelLabel: string;
  submitting?: boolean;
  testIDPrefix: string;
  /** 拼好的一句原因 (「其他」= 写的那句; 其余 = 按钮文字, 写了补充接在后面) */
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}

export default function RequiredReasonModal({
  message, title, hint, reasons, confirmLabel, cancelLabel, submitting, testIDPrefix, onConfirm, onCancel,
}: Props) {
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');

  useEffect(() => {
    if (message != null) {
      setReason('');
      setNote('');
    }
  }, [message]);

  const block = reasonBlock(reason, note);

  return (
    <Modal visible={message != null} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.backdrop}>
        <View style={styles.card} testID={`${testIDPrefix}-modal`}>
          <ScrollView keyboardShouldPersistTaps="handled">
            <Text style={styles.title}>{title}</Text>
            <Text style={styles.message} testID={`${testIDPrefix}-message`}>{message}</Text>
            <Text style={styles.hint}>{hint}</Text>
            <View style={styles.reasons}>
              {reasons.map((r) => {
                const selected = reason === r;
                return (
                  <TouchableRipple
                    key={r}
                    testID={`${testIDPrefix}-reason-${r}`}
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
            {reason === REASON_OTHER ? (
              <TextInput
                testID={`${testIDPrefix}-note`}
                value={note}
                onChangeText={setNote}
                editable={!submitting}
                placeholder="请写一句具体原因"
                maxLength={200}
                style={styles.note}
              />
            ) : null}
            {block ? <Text style={styles.block} testID={`${testIDPrefix}-block`}>{block}</Text> : null}
          </ScrollView>
          <View style={styles.actions}>
            <TouchableRipple
              testID={`${testIDPrefix}-cancel`}
              accessibilityRole="button"
              disabled={submitting}
              onPress={onCancel}
              style={[styles.actionBtn, styles.cancelBtn]}
            >
              <Text style={styles.cancelText}>{cancelLabel}</Text>
            </TouchableRipple>
            <TouchableRipple
              testID={`${testIDPrefix}-confirm`}
              accessibilityRole="button"
              accessibilityState={{ disabled: !!block || !!submitting }}
              disabled={!!block || !!submitting}
              onPress={() => {
                // 置灰只是外观: 这里再拦一道, 没选原因 / 提交中绝不放行
                if (block || submitting) return;
                onConfirm(composeReason(reason, note));
              }}
              style={[styles.actionBtn, styles.confirmBtn, (!!block || !!submitting) && styles.confirmBtnDisabled]}
            >
              <Text style={styles.confirmText}>{confirmLabel}</Text>
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
