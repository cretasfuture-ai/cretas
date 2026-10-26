/**
 * AiImageNotice — 「图片会发给 AI 识别」一句话提示
 *
 * 放在每个「拍照 / 从相册选」后图片会交给 AI 识别的入口旁边 (图片识别不做名称脱敏, 见官网数据安全说明)。
 * tone="dark" 用在相机取景画面上 (白字), 其余用默认浅色背景 (灰字, 对白底对比度 7:1)。
 */
import React from 'react';
import { View, Text, StyleSheet, StyleProp, ViewStyle } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';

interface AiImageNoticeProps {
  tone?: 'light' | 'dark';
  style?: StyleProp<ViewStyle>;
}

export default function AiImageNotice({ tone = 'light', style }: AiImageNoticeProps) {
  const { t } = useTranslation('common');
  const color = tone === 'dark' ? '#FFFFFF' : '#4B5563';
  return (
    <View
      style={[styles.row, style]}
      testID="ai-image-notice"
      accessibilityRole="text"
      accessibilityLabel={t('aiImageNotice')}
    >
      <MaterialCommunityIcons name="information-outline" size={16} color={color} />
      <Text style={[styles.text, { color }]}>{t('aiImageNotice')}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    paddingVertical: 4,
  },
  text: {
    fontSize: 14,
  },
});
