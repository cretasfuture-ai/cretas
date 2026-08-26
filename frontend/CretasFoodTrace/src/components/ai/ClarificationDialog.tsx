/**
 * 澄清问题对话框组件
 *
 * 当AI意图执行需要更多信息时，显示澄清问题并收集用户答案
 *
 * 功能：
 * - 显示澄清问题列表
 * - 根据参数类型自动生成合适的输入组件
 * - 支持必填字段验证
 * - 支持枚举类型的选择器
 * - 优雅的UI设计
 *
 * @version 1.0.0
 * @since 2026-01-06
 */

import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Modal,
  TextInput,
  ScrollView,
  Platform,
} from 'react-native';
import { IconButton } from 'react-native-paper';
import DateTimePicker from '@react-native-community/datetimepicker';
import { LinearGradient } from 'expo-linear-gradient';
import { MissingParameter } from '../../types/intent';
import { formatDate, parseYmdOrToday } from '../../utils/formatters';

// DATE 型参数在这条链上的唯一格式是 YYYY-MM-DD (原来那个文本框的 placeholder 就是它)。
// 滚轮选完之后必须经过这里再写回 answers, 不要在别处另拼一份。
export interface ClarificationDialogProps {
  /** 是否可见 */
  visible: boolean;
  /** 澄清问题列表 */
  questions: string[];
  /** 缺失参数列表（可选，用于结构化输入） */
  missingParameters?: MissingParameter[];
  /** 提交回调 */
  onSubmit: (answers: Record<string, any>) => void;
  /** 取消回调 */
  onCancel: () => void;
}

/**
 * DATE 型参数的输入 —— 点一下弹滚轮, 不许手打。
 *
 * ⚠️ 这个组件挂在 RN 的 <Modal> 里面, 所以 iOS 不能用 react-native-paper 的 Portal:
 *    Portal 渲染在 PaperProvider 的根上, 而 RN Modal 在 iOS 是另一层原生窗口,
 *    盖在它上面 —— Portal 里的东西会被压在底下看不见。
 *    ⇒ iOS 的 spinner 就地内联渲染, 配一个「完成」收起; Android 的本来就是原生模态。
 *
 * 每个 DATE 参数都是一个独立的组件实例, open 状态是它自己的 ——
 * 多个日期参数并存时不会串到别人身上。
 */
function DateParameterInput({
  parameter,
  value,
  onChange,
  error,
}: {
  parameter: MissingParameter;
  value: any;
  onChange: (value: any) => void;
  error?: string;
}) {
  const { displayName, description, required } = parameter;
  const [pickerOpen, setPickerOpen] = useState(false);
  const current = value?.toString() ?? '';
  const pickerInitialDate = parseYmdOrToday(current);

  // 写回一律走 formatYmd —— 原来那个文本框承诺的就是 YYYY-MM-DD, 格式不能变。
  const commitPickedDate = (date: Date) => {
    onChange(formatDate(date));
  };

  return (
    <View style={styles.inputGroup}>
      <View style={styles.inputLabelRow}>
        <Text style={styles.inputLabel}>{displayName}</Text>
        {required && <Text style={styles.requiredMark}>*</Text>}
      </View>
      {description && (
        <Text style={styles.inputDescription}>{description}</Text>
      )}
      <TouchableOpacity
        onPress={() => setPickerOpen(true)}
        accessibilityRole="button"
        accessibilityLabel={`选择${displayName}`}
        testID={`clarification-date-picker-${parameter.parameterName}`}
      >
        <View pointerEvents="none">
          <TextInput
            style={[styles.textInput, error && styles.textInputError]}
            value={current}
            editable={false}
            placeholder="YYYY-MM-DD"
            placeholderTextColor="#999"
          />
        </View>
      </TouchableOpacity>
      {pickerOpen && (
        <DateTimePicker
          value={pickerInitialDate}
          mode="date"
          display="spinner"
          onChange={(event, date) => {
            if (Platform.OS === 'android') {
              // Android 是模态: 无论确定还是取消都要收起, 否则下次点不开
              setPickerOpen(false);
              if (event.type === 'set' && date) commitPickedDate(date);
              return;
            }
            // iOS 的 spinner 是内联控件: 一 onChange 就关会导致滚一格就消失
            if (date) commitPickedDate(date);
          }}
        />
      )}
      {pickerOpen && Platform.OS === 'ios' && (
        <TouchableOpacity
          style={styles.datePickerDone}
          onPress={() => setPickerOpen(false)}
          accessibilityRole="button"
          accessibilityLabel={`完成选择${displayName}`}
          testID={`clarification-date-done-${parameter.parameterName}`}
        >
          <Text style={styles.datePickerDoneText}>完成</Text>
        </TouchableOpacity>
      )}
      {error && <Text style={styles.errorText}>{error}</Text>}
    </View>
  );
}

/**
 * 参数输入组件
 */
function ParameterInput({
  parameter,
  value,
  onChange,
  error,
}: {
  parameter: MissingParameter;
  value: any;
  onChange: (value: any) => void;
  error?: string;
}) {
  const { parameterType, displayName, description, possibleValues, required } = parameter;

  // STRING 和 NUMBER 类型 - 文本输入框
  if (parameterType === 'STRING' || parameterType === 'NUMBER') {
    return (
      <View style={styles.inputGroup}>
        <View style={styles.inputLabelRow}>
          <Text style={styles.inputLabel}>{displayName}</Text>
          {required && <Text style={styles.requiredMark}>*</Text>}
        </View>
        {description && (
          <Text style={styles.inputDescription}>{description}</Text>
        )}
        <TextInput
          style={[styles.textInput, error && styles.textInputError]}
          value={value?.toString() ?? ''}
          onChangeText={(text) => {
            if (parameterType === 'NUMBER') {
              const num = parseFloat(text);
              onChange(isNaN(num) ? text : num);
            } else {
              onChange(text);
            }
          }}
          placeholder={`请输入${displayName}`}
          placeholderTextColor="#999"
          keyboardType={parameterType === 'NUMBER' ? 'numeric' : 'default'}
        />
        {error && <Text style={styles.errorText}>{error}</Text>}
      </View>
    );
  }

  // BOOLEAN 类型 - 开关按钮
  if (parameterType === 'BOOLEAN') {
    return (
      <View style={styles.inputGroup}>
        <View style={styles.inputLabelRow}>
          <Text style={styles.inputLabel}>{displayName}</Text>
          {required && <Text style={styles.requiredMark}>*</Text>}
        </View>
        {description && (
          <Text style={styles.inputDescription}>{description}</Text>
        )}
        <View style={styles.booleanButtons}>
          <TouchableOpacity
            style={[
              styles.booleanButton,
              value === true && styles.booleanButtonSelected,
            ]}
            onPress={() => onChange(true)}
          >
            <Text
              style={[
                styles.booleanButtonText,
                value === true && styles.booleanButtonTextSelected,
              ]}
            >
              是
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.booleanButton,
              value === false && styles.booleanButtonSelected,
            ]}
            onPress={() => onChange(false)}
          >
            <Text
              style={[
                styles.booleanButtonText,
                value === false && styles.booleanButtonTextSelected,
              ]}
            >
              否
            </Text>
          </TouchableOpacity>
        </View>
        {error && <Text style={styles.errorText}>{error}</Text>}
      </View>
    );
  }

  // ENUM 类型 - 选择器
  if (parameterType === 'ENUM' && possibleValues && possibleValues.length > 0) {
    return (
      <View style={styles.inputGroup}>
        <View style={styles.inputLabelRow}>
          <Text style={styles.inputLabel}>{displayName}</Text>
          {required && <Text style={styles.requiredMark}>*</Text>}
        </View>
        {description && (
          <Text style={styles.inputDescription}>{description}</Text>
        )}
        <View style={styles.enumButtons}>
          {possibleValues.map((optionValue) => (
            <TouchableOpacity
              key={optionValue}
              style={[
                styles.enumButton,
                value === optionValue && styles.enumButtonSelected,
              ]}
              onPress={() => onChange(optionValue)}
            >
              <Text
                style={[
                  styles.enumButtonText,
                  value === optionValue && styles.enumButtonTextSelected,
                ]}
              >
                {optionValue}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
        {error && <Text style={styles.errorText}>{error}</Text>}
      </View>
    );
  }

  // DATE 类型 - 滚轮选 (2026-08-26)
  // 现场原话:「这个日期改下备, 改成滚轮的, 手写有点费事」
  if (parameterType === 'DATE') {
    return (
      <DateParameterInput
        parameter={parameter}
        value={value}
        onChange={onChange}
        error={error}
      />
    );
  }

  // 默认文本输入
  return (
    <View style={styles.inputGroup}>
      <View style={styles.inputLabelRow}>
        <Text style={styles.inputLabel}>{displayName}</Text>
        {required && <Text style={styles.requiredMark}>*</Text>}
      </View>
      {description && (
        <Text style={styles.inputDescription}>{description}</Text>
      )}
      <TextInput
        style={[styles.textInput, error && styles.textInputError]}
        value={value?.toString() ?? ''}
        onChangeText={onChange}
        placeholder={`请输入${displayName}`}
        placeholderTextColor="#999"
      />
      {error && <Text style={styles.errorText}>{error}</Text>}
    </View>
  );
}

/**
 * 澄清问题对话框组件
 */
export function ClarificationDialog({
  visible,
  questions,
  missingParameters,
  onSubmit,
  onCancel,
}: ClarificationDialogProps) {
  const [answers, setAnswers] = useState<Record<string, any>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [freeTextAnswers, setFreeTextAnswers] = useState<string[]>([]);

  // 重置状态当对话框打开时
  useEffect(() => {
    if (visible) {
      setAnswers({});
      setErrors({});
      setFreeTextAnswers(new Array(questions.length).fill(''));
    }
  }, [visible, questions.length]);

  /**
   * 更新参数答案
   */
  const handleParameterChange = (parameterName: string, value: any) => {
    setAnswers((prev) => ({ ...prev, [parameterName]: value }));
    // 清除该字段的错误
    if (errors[parameterName]) {
      setErrors((prev) => {
        const newErrors = { ...prev };
        delete newErrors[parameterName];
        return newErrors;
      });
    }
  };

  /**
   * 更新自由文本答案
   */
  const handleFreeTextChange = (index: number, text: string) => {
    setFreeTextAnswers((prev) => {
      const newAnswers = [...prev];
      newAnswers[index] = text;
      return newAnswers;
    });
  };

  /**
   * 验证表单
   */
  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    let hasError = false;

    // 如果有结构化参数，验证它们
    if (missingParameters && missingParameters.length > 0) {
      missingParameters.forEach((param) => {
        if (param.required) {
          const value = answers[param.parameterName];
          if (value === undefined || value === null || value === '') {
            newErrors[param.parameterName] = `${param.displayName}是必填项`;
            hasError = true;
          }
        }
      });
    } else {
      // 如果没有结构化参数，验证自由文本答案
      freeTextAnswers.forEach((answer, index) => {
        if (!answer.trim()) {
          newErrors[`question_${index}`] = '请回答此问题';
          hasError = true;
        }
      });
    }

    setErrors(newErrors);
    return !hasError;
  };

  /**
   * 提交表单
   */
  const handleSubmit = () => {
    if (!validateForm()) {
      return;
    }

    // 如果有结构化参数，返回参数答案
    if (missingParameters && missingParameters.length > 0) {
      onSubmit(answers);
    } else {
      // 否则，将自由文本答案转换为对象格式
      const answersObject: Record<string, any> = {};
      questions.forEach((question, index) => {
        answersObject[`answer_${index}`] = freeTextAnswers[index];
      });
      onSubmit(answersObject);
    }
  };

  /**
   * 取消对话框
   */
  const handleCancel = () => {
    setAnswers({});
    setErrors({});
    setFreeTextAnswers([]);
    onCancel();
  };

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent
      onRequestClose={handleCancel}
    >
      <View style={styles.modalOverlay}>
        <View style={styles.modalContent}>
          {/* 头部 */}
          <View style={styles.header}>
            <View style={styles.headerIcon}>
              <LinearGradient
                colors={['#667eea', '#764ba2']}
                style={styles.headerIconGradient}
              >
                <IconButton icon="comment-question" size={24} iconColor="#fff" />
              </LinearGradient>
            </View>
            <View style={styles.headerText}>
              <Text style={styles.headerTitle}>需要更多信息</Text>
              <Text style={styles.headerSubtitle}>
                请回答以下问题以继续执行操作
              </Text>
            </View>
            <TouchableOpacity onPress={handleCancel} style={styles.closeButton}>
              <IconButton icon="close" size={24} iconColor="#666" />
            </TouchableOpacity>
          </View>

          {/* 内容区域 */}
          <ScrollView style={styles.contentContainer} showsVerticalScrollIndicator={false}>
            {/* 显示澄清问题（如果没有结构化参数） */}
            {(!missingParameters || missingParameters.length === 0) && questions.length > 0 && (
              <View style={styles.questionsContainer}>
                {questions.map((question, index) => (
                  <View key={index} style={styles.questionGroup}>
                    <Text style={styles.questionText}>
                      {index + 1}. {question}
                    </Text>
                    <TextInput
                      style={[
                        styles.textInput,
                        errors[`question_${index}`] && styles.textInputError,
                      ]}
                      value={freeTextAnswers[index]}
                      onChangeText={(text) => handleFreeTextChange(index, text)}
                      placeholder="请输入您的答案"
                      placeholderTextColor="#999"
                      multiline
                    />
                    {errors[`question_${index}`] && (
                      <Text style={styles.errorText}>{errors[`question_${index}`]}</Text>
                    )}
                  </View>
                ))}
              </View>
            )}

            {/* 显示结构化参数输入 */}
            {missingParameters && missingParameters.length > 0 && (
              <View style={styles.parametersContainer}>
                {missingParameters.map((parameter) => (
                  <ParameterInput
                    key={parameter.parameterName}
                    parameter={parameter}
                    value={answers[parameter.parameterName]}
                    onChange={(value) => handleParameterChange(parameter.parameterName, value)}
                    error={errors[parameter.parameterName]}
                  />
                ))}
              </View>
            )}
          </ScrollView>

          {/* 底部按钮 */}
          <View style={styles.footer}>
            <TouchableOpacity style={styles.cancelButton} onPress={handleCancel}>
              <Text style={styles.cancelButtonText}>取消</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.submitButton} onPress={handleSubmit}>
              <LinearGradient
                colors={['#667eea', '#764ba2']}
                style={styles.submitButtonGradient}
              >
                <Text style={styles.submitButtonText}>提交</Text>
              </LinearGradient>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '90%',
    paddingBottom: Platform.OS === 'ios' ? 34 : 20,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  headerIcon: {
    marginRight: 12,
  },
  headerIconGradient: {
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
  },
  headerText: {
    flex: 1,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
    marginBottom: 4,
  },
  headerSubtitle: {
    fontSize: 13,
    color: '#666',
    lineHeight: 18,
  },
  closeButton: {
    marginTop: -8,
    marginRight: -8,
  },
  contentContainer: {
    paddingHorizontal: 16,
    paddingTop: 12,
    maxHeight: 500,
  },
  questionsContainer: {
    paddingBottom: 12,
  },
  questionGroup: {
    marginBottom: 20,
  },
  questionText: {
    fontSize: 15,
    color: '#333',
    fontWeight: '500',
    marginBottom: 10,
    lineHeight: 22,
  },
  parametersContainer: {
    paddingBottom: 12,
  },
  inputGroup: {
    marginBottom: 20,
  },
  inputLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  inputLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  requiredMark: {
    fontSize: 14,
    color: '#ff4d4f',
    marginLeft: 4,
  },
  inputDescription: {
    fontSize: 12,
    color: '#999',
    marginBottom: 8,
    lineHeight: 18,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: '#333',
    backgroundColor: '#fff',
    minHeight: 44,
  },
  textInputError: {
    borderColor: '#ff4d4f',
  },
  datePickerDone: {
    alignSelf: 'flex-end',
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  datePickerDoneText: {
    fontSize: 15,
    fontWeight: '600',
    color: '#1B65A8',
  },
  booleanButtons: {
    flexDirection: 'row',
    gap: 12,
  },
  booleanButton: {
    flex: 1,
    paddingVertical: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    backgroundColor: '#fff',
    alignItems: 'center',
  },
  booleanButtonSelected: {
    borderColor: '#667eea',
    backgroundColor: '#f0f4ff',
  },
  booleanButtonText: {
    fontSize: 14,
    color: '#666',
    fontWeight: '500',
  },
  booleanButtonTextSelected: {
    color: '#667eea',
    fontWeight: '600',
  },
  enumButtons: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  enumButton: {
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    backgroundColor: '#fff',
  },
  enumButtonSelected: {
    borderColor: '#667eea',
    backgroundColor: '#f0f4ff',
  },
  enumButtonText: {
    fontSize: 14,
    color: '#666',
    fontWeight: '500',
  },
  enumButtonTextSelected: {
    color: '#667eea',
    fontWeight: '600',
  },
  errorText: {
    fontSize: 12,
    color: '#ff4d4f',
    marginTop: 4,
  },
  footer: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingTop: 16,
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
  },
  cancelButton: {
    flex: 1,
    paddingVertical: 14,
    marginRight: 10,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    alignItems: 'center',
  },
  cancelButtonText: {
    fontSize: 15,
    color: '#666',
    fontWeight: '500',
  },
  submitButton: {
    flex: 2,
    borderRadius: 12,
    overflow: 'hidden',
  },
  submitButtonGradient: {
    paddingVertical: 14,
    alignItems: 'center',
  },
  submitButtonText: {
    fontSize: 15,
    color: '#fff',
    fontWeight: '600',
  },
});
