/**
 * ProcessParamsSection — 产出报工屏的「工艺参数」录入栏 (docs/specs/app-process-params-entry.md)。
 *
 * 本道工序配了参数才出现 (没有 enabled 字段 → 整段不渲染)。每行: 参数名 + 大号数字输入框(单位在右) +
 * 下方常驻一行标准文案; 填的数超范围时这行变红「偏高 · …」/「偏低 · …」。参数非必填, 超标不挡提交
 * (只在提交确认里列出)。
 *
 * UX Flow Analysis 见 spec: F1 常驻标准 / F3 字号 ≥ 24 + decimal-pad + 「请填数字」 /
 * F4 只显示 enabled 字段 / F7 标题带工序名。
 */
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { TextInput as PaperTextInput } from 'react-native-paper';
import type { ProcessCustomFieldDef } from '../../services/api/yieldReportApi';
import {
  checkParam, enabledParamDefs, formatParamStandard, isInvalidNumberText,
} from '../../utils/processParamCheck';

interface Props {
  processName?: string | null;
  /** 当前工序的参数定义 (可含未启用项, 本组件只取 enabled)。 */
  schema: ProcessCustomFieldDef[] | null | undefined;
  values: Record<string, string>;
  onChangeValue: (key: string, text: string) => void;
  disabled?: boolean;
}

export const ProcessParamsSection: React.FC<Props> = ({
  processName, schema, values, onChangeValue, disabled = false,
}) => {
  const defs = enabledParamDefs(schema);
  if (defs.length === 0) return null;

  return (
    <View style={styles.card} testID="process-params-section">
      <Text style={styles.title}>{processName ? `${processName} 工艺参数` : '工艺参数'}</Text>
      <Text style={styles.subtitle}>按这锅实际填写，没测的可以不填</Text>
      {defs.map((def) => {
        const text = values[def.key] ?? '';
        const isNumber = def.type === 'number';
        const unit = def.unit?.trim() || '';
        const invalid = isInvalidNumberText(def, text);
        const status = isNumber && !invalid ? checkParam(def, text) : null;
        const standard = formatParamStandard(def);
        const hint = invalid
          ? '请填数字'
          : status === 'HIGH'
            ? `偏高 · ${standard}`
            : status === 'LOW'
              ? `偏低 · ${standard}`
              : standard;
        const bad = invalid || status === 'HIGH' || status === 'LOW';
        return (
          <View key={def.key} style={styles.item} testID={`process-param-row-${def.key}`}>
            <Text style={styles.name}>{def.label}{unit ? `（${unit}）` : ''}</Text>
            <PaperTextInput
              mode="outlined"
              value={text}
              onChangeText={(t) => onChangeValue(def.key, t)}
              keyboardType={isNumber ? 'decimal-pad' : 'default'}
              placeholder={isNumber ? '填实测值' : '填写'}
              editable={!disabled}
              error={bad}
              right={isNumber && unit ? <PaperTextInput.Affix text={unit} /> : undefined}
              style={styles.input}
              contentStyle={styles.inputContent}
              accessibilityLabel={`${def.label}${standard ? `，${standard}` : ''}`}
              testID={`process-param-input-${def.key}`}
            />
            {hint ? (
              <Text
                style={[styles.hint, bad && styles.hintBad]}
                testID={`process-param-hint-${def.key}`}
              >
                {hint}
              </Text>
            ) : null}
          </View>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  card: {
    marginTop: 12, marginBottom: 12, padding: 14, borderRadius: 8,
    borderWidth: 1, borderColor: '#D9ECFF', backgroundColor: '#F4F9FF',
  },
  title: { fontSize: 16, fontWeight: '700', color: '#303133' },
  subtitle: { fontSize: 13, color: '#909399', marginTop: 2, marginBottom: 6 },
  item: { paddingVertical: 8, borderTopWidth: 1, borderTopColor: '#E4EEF9' },
  name: { fontSize: 16, fontWeight: '600', color: '#303133', marginBottom: 6 },
  // Paper 的 fontSize 只认 style (不认 contentStyle)
  input: { minHeight: 52, fontSize: 24, backgroundColor: '#FFFFFF' },
  inputContent: { minHeight: 52 },
  hint: { fontSize: 14, color: '#909399', marginTop: 6, lineHeight: 20, minHeight: 20 },
  hintBad: { color: '#F56C6C', fontWeight: '600' },
});

export default ProcessParamsSection;
