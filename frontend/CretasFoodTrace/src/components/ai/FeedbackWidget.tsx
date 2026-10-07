/**
 * 食品知识库反馈组件
 *
 * 用于收集用户对 AI 回答的反馈：
 * - 点赞/点踩按钮
 * - 点踩时展开反馈表单（标签选择 + 文本输入）
 * - 提交后显示"已提交"并禁用按钮
 * - 自动调用 log-query 记录查询（用于隐式重查检测）
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  ActivityIndicator,
} from 'react-native';
import { useTheme } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { AppTheme } from '../../theme';
import { useAuthStore } from '../../store/authStore';
import { apiClient } from '../../services/api/apiClient';
import { appAlert } from '../ui/AppDialog';

/** 食品知识库查询的元数据，用于 log-query */
export interface FoodKBQueryMetadata {
  /** 响应时间（毫秒） */
  responseTimeMs?: number;
  /** 检索到的文档数量 */
  documentCount?: number;
  /** 引用文档列表（含 ID 和相似度） */
  citations?: Array<{
    index: number;
    title: string;
    source: string;
    category: string;
    similarity: number;
  }>;
}

interface FeedbackWidgetProps {
  /** 查询ID（关联查询日志） */
  queryId: string;
  /** 用户问题 */
  question: string;
  /** AI 回答 */
  answer: string;
  /** 会话ID（用于隐式反馈检测） */
  sessionId?: string;
  /** 意图代码（用于反馈追溯） */
  intentCode?: string;
  /** 食品知识库查询元数据（用于 log-query） */
  foodKbMetadata?: FoodKBQueryMetadata;
}

export function FeedbackWidget({ queryId, question, answer, sessionId, intentCode, foodKbMetadata }: FeedbackWidgetProps) {
  const theme = useTheme<AppTheme>();
  const factoryId = useAuthStore(s => s.getFactoryId());
  const token = useAuthStore(s => s.tokens?.accessToken);
  const [feedbackSubmitted, setFeedbackSubmitted] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [feedbackText, setFeedbackText] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // 在组件挂载时自动调用 log-query 记录查询（用于隐式重查检测）
  const logQueryCalledRef = useRef(false);
  useEffect(() => {
    if (logQueryCalledRef.current) return;
    logQueryCalledRef.current = true;

    const logQuery = async () => {
      const isFoodKnowledgeAnswer = intentCode === 'FOOD_KNOWLEDGE' || Boolean(foodKbMetadata);
      if (!factoryId || !question || !isFoodKnowledgeAnswer) return;
      try {
        // 从引用中提取 top1 文档信息
        const top1Citation = foodKbMetadata?.citations?.[0];

        await apiClient.post(
          `/api/mobile/${factoryId}/food-kb/feedback/log-query`,
          {
            query: question,
            sessionId: sessionId || undefined,
            retrievedDocIds: foodKbMetadata?.citations
              ?.map((c) => c.index)
              .filter((id): id is number => id != null),
            responseTimeMs: foodKbMetadata?.responseTimeMs,
          },
          {
            headers: token ? { Authorization: `Bearer ${token}` } : undefined,
          }
        );
      } catch (e) {
        // Silent fail — query logging is non-critical
        console.debug('Failed to log food KB query:', e);
      }
    };

    logQuery();
  }, []); // Run once on mount

  // 反馈标签选项
  const FEEDBACK_TAGS = [
    { value: 'inaccurate', label: '不准确' },
    { value: 'incomplete', label: '不完整' },
    { value: 'irrelevant', label: '不相关' },
    { value: 'other', label: '其他' },
  ];

  // 切换标签选择
  const toggleTag = (tagValue: string) => {
    setSelectedTags((prev) =>
      prev.includes(tagValue)
        ? prev.filter((t) => t !== tagValue)
        : [...prev, tagValue]
    );
  };

  // 提交反馈
  const handleSubmit = async (helpful: boolean) => {
    if (isSubmitting || feedbackSubmitted) return;

    setIsSubmitting(true);

    try {
      const requestBody = {
        question,
        answer,
        helpful,
        feedbackTags: helpful ? [] : selectedTags,
        feedbackText: helpful ? null : feedbackText || null,
        sessionId: sessionId || undefined,
        intentCode: intentCode || undefined,
        retrievedDocIds: foodKbMetadata?.citations
          ?.map((c) => c.index)
          .filter((id): id is number => id != null),
        retrievedDocTitles: foodKbMetadata?.citations
          ?.map((c) => c.title)
          .filter((t): t is string => !!t),
      };

      const response = await apiClient.post<{ success: boolean; message?: string; data?: unknown }>(
        `/api/mobile/${factoryId}/food-kb/feedback/submit`,
        requestBody,
        {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        }
      );

      const nestedSuccess = response.data && typeof response.data === 'object' && !Array.isArray(response.data)
        ? (response.data as Record<string, unknown>).success
        : undefined;
      const accepted = response.success === true && nestedSuccess !== false;
      if (accepted) {
        setFeedbackSubmitted(true);
        setIsExpanded(false);
      } else {
        appAlert('提交失败', '反馈没有保存，请稍后重试。');
      }
    } catch (error) {
      console.error('提交反馈失败:', error);
      appAlert('提交失败', '反馈没有保存，请检查网络后重试。');
    } finally {
      setIsSubmitting(false);
    }
  };

  // 点赞：立即提交
  const handleThumbsUp = () => {
    handleSubmit(true);
  };

  // 点踩：展开表单
  const handleThumbsDown = () => {
    if (feedbackSubmitted) return;
    setIsExpanded(true);
  };

  // 点踩后的提交按钮
  const handleSubmitNegative = () => {
    if (selectedTags.length === 0) {
      appAlert('请选择反馈标签', '请至少选择一个反馈原因。');
      return;
    }
    handleSubmit(false);
  };

  if (feedbackSubmitted) {
    return (
      <View style={styles.container}>
        <View style={styles.submittedRow}>
          <MaterialCommunityIcons name="check-circle" size={24} color={theme.colors.primary} />
          <Text style={[styles.submittedText, { color: theme.colors.primary }]}>已提交反馈，感谢您的帮助</Text>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* 点赞/点踩按钮行 */}
      <View style={styles.buttonRow}>
        <Text style={[styles.promptText, { color: theme.colors.onSurfaceVariant }]}>这个回答有帮助吗？</Text>
        <View style={styles.buttonsContainer}>
          <TouchableOpacity
            accessibilityRole="button"
            accessibilityLabel="这个回答有帮助"
            style={styles.iconButton}
            onPress={handleThumbsUp}
            disabled={isSubmitting}
          >
            <MaterialCommunityIcons name="thumb-up-outline" size={24} color={theme.colors.primary} />
          </TouchableOpacity>
          <TouchableOpacity
            accessibilityRole="button"
            accessibilityLabel="这个回答没有帮助"
            style={styles.iconButton}
            onPress={handleThumbsDown}
            disabled={isSubmitting}
          >
            <MaterialCommunityIcons name="thumb-down-outline" size={24} color={theme.colors.error} />
          </TouchableOpacity>
        </View>
      </View>

      {/* 点踩后展开的表单 */}
      {isExpanded && (
        <View style={[styles.expandedForm, { backgroundColor: theme.colors.surfaceVariant }]}>
          <Text style={[styles.formLabel, { color: theme.colors.onSurface }]}>请告诉我们原因：</Text>

          {/* 标签选择 */}
          <View style={styles.tagsContainer}>
            {FEEDBACK_TAGS.map((tag) => (
              <TouchableOpacity
                key={tag.value}
                accessibilityRole="button"
                accessibilityLabel={`反馈原因：${tag.label}`}
                accessibilityState={{ selected: selectedTags.includes(tag.value) }}
                style={[
                  styles.tag,
                  { backgroundColor: theme.colors.surface, borderColor: theme.colors.outline },
                  selectedTags.includes(tag.value) && { backgroundColor: theme.colors.primary, borderColor: theme.colors.primary },
                ]}
                onPress={() => toggleTag(tag.value)}
              >
                <Text
                  style={[
                    styles.tagText,
                    { color: selectedTags.includes(tag.value) ? theme.colors.onPrimary : theme.colors.onSurfaceVariant },
                  ]}
                >
                  {tag.label}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          {/* 文本输入 */}
          <TextInput
            style={[styles.textInput, { backgroundColor: theme.colors.surface, borderColor: theme.colors.outline, color: theme.colors.onSurface }]}
            placeholder="详细说明（可选）"
            placeholderTextColor={theme.colors.onSurfaceVariant}
            value={feedbackText}
            onChangeText={setFeedbackText}
            multiline
            maxLength={500}
            editable={!isSubmitting}
          />

          {/* 提交按钮 */}
          <TouchableOpacity
            style={[styles.submitButton, { backgroundColor: theme.colors.primary }, isSubmitting && { backgroundColor: theme.colors.surfaceVariant }]}
            onPress={handleSubmitNegative}
            disabled={isSubmitting || selectedTags.length === 0}
          >
            {isSubmitting ? (
              <ActivityIndicator size="small" color={theme.colors.onPrimary} />
            ) : (
              <Text style={[styles.submitButtonText, { color: theme.colors.onPrimary }]}>提交反馈</Text>
            )}
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
  },
  buttonRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  promptText: {
    fontSize: 13,
  },
  buttonsContainer: {
    flexDirection: 'row',
    gap: 8,
  },
  iconButton: {
    width: 48,
    height: 48,
    justifyContent: 'center',
    alignItems: 'center',
  },
  submittedRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  submittedText: {
    fontSize: 13,
    marginLeft: 4,
  },
  expandedForm: {
    marginTop: 12,
    padding: 12,
    borderRadius: 8,
  },
  formLabel: {
    fontSize: 13,
    fontWeight: '500',
    marginBottom: 8,
  },
  tagsContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 12,
  },
  tag: {
    minHeight: 44,
    minWidth: 44,
    justifyContent: 'center',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderWidth: 1,
    borderRadius: 4,
  },
  tagText: {
    fontSize: 13,
  },
  textInput: {
    borderWidth: 1,
    borderRadius: 4,
    padding: 8,
    fontSize: 13,
    minHeight: 60,
    textAlignVertical: 'top',
    marginBottom: 12,
  },
  submitButton: {
    borderRadius: 4,
    paddingVertical: 10,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  submitButtonText: {
    fontSize: 14,
    fontWeight: '500',
  },
});

export default FeedbackWidget;
