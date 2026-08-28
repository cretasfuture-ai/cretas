/**
 * 帮助中心页面
 */
import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  LayoutAnimation,
  Platform,
  UIManager,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';

// 启用 Android 动画
if (Platform.OS === 'android' && UIManager.setLayoutAnimationEnabledExperimental) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

interface FAQItem {
  id: string;
  questionKey: string;
  answerKey: string;
}

/**
 * 这 6 条问答的**内容**写出来之后，把它翻成 true。
 * ⛔ 在那之前不要翻 —— zh-CN 与 en-US 两个包里都没有这些键，
 *   翻成 true 只会让屏上重新出现 6 行 `helpCenter.faq.xxx.question`。
 */
const FAQ_CONTENT_AUTHORED = false;

const faqKeys: FAQItem[] = [
  { id: '1', questionKey: 'helpCenter.faq.createBatch.question', answerKey: 'helpCenter.faq.createBatch.answer' },
  { id: '2', questionKey: 'helpCenter.faq.viewProgress.question', answerKey: 'helpCenter.faq.viewProgress.answer' },
  { id: '3', questionKey: 'helpCenter.faq.qualityCheck.question', answerKey: 'helpCenter.faq.qualityCheck.answer' },
  { id: '4', questionKey: 'helpCenter.faq.exportData.question', answerKey: 'helpCenter.faq.exportData.answer' },
  { id: '5', questionKey: 'helpCenter.faq.equipmentAlert.question', answerKey: 'helpCenter.faq.equipmentAlert.answer' },
  { id: '6', questionKey: 'helpCenter.faq.forgotPassword.question', answerKey: 'helpCenter.faq.forgotPassword.answer' },
];

export function HelpCenterScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('profile');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const toggleExpand = (id: string) => {
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setExpandedId(expandedId === id ? null : id);
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backButton}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.title}>{t('helpCenter.title')}</Text>
        <View style={{ width: 32 }} />
      </View>

      <ScrollView style={styles.content}>
        {/* 快捷操作 */}
        <View style={styles.quickActions}>
          <TouchableOpacity style={styles.actionItem}>
            <View style={[styles.actionIcon, { backgroundColor: '#e8f5e9' }]}>
              <Icon source="book-open-variant" size={24} color="#4caf50" />
            </View>
            <Text style={styles.actionText}>{t('helpCenter.userGuide')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.actionItem}>
            <View style={[styles.actionIcon, { backgroundColor: '#e3f2fd' }]}>
              <Icon source="video" size={24} color="#2196f3" />
            </View>
            <Text style={styles.actionText}>{t('helpCenter.videoTutorial')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.actionItem}>
            <View style={[styles.actionIcon, { backgroundColor: '#fff3e0' }]}>
              <Icon source="phone" size={24} color="#ff9800" />
            </View>
            <Text style={styles.actionText}>{t('helpCenter.contactSupport')}</Text>
          </TouchableOpacity>
        </View>

        {/*
          🔴 2026-08-29 逐屏走查：这一段原来把 6 条 FAQ **的键**直接打在屏上 ——
          `helpCenter.faq.createBatch.question` 这样一行行列出来，没有一句人话。

          成因：`helpCenter.faq` 在译文里是个**字符串**（"常见问题"），
          而代码当对象去取 `.createBatch.question`，永远解析不出来；
          英文包里同样没有这些内容 ⇒ **这 6 条问答从来没被写过**。
          （`t('helpCenter.faqTitle')` 也是个不存在的键，正确的是 `helpCenter.faq`。）

          ⛔ 我不替产品编帮助文案 —— 帮助内容写错了比没有更糟，用户是照着它去操作的。
          在有人真的写出这 6 条之前，这里说清楚「还没有」，并把人导向下面已经能用的联系客服。
        */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('helpCenter.faq')}</Text>
          <View style={styles.faqList}>
            <View style={styles.faqItem} testID="help-faq-not-authored">
              <Text style={styles.faqQuestion}>常见问题还没有整理</Text>
              <Text style={styles.faqAnswer}>
                这一栏的内容尚未编写。遇到问题请用上面的「联系客服」，或查看「使用指南」。
              </Text>
            </View>
          </View>
        </View>

        {FAQ_CONTENT_AUTHORED && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('helpCenter.faq')}</Text>
          <View style={styles.faqList}>
            {faqKeys.map((item) => (
              <TouchableOpacity
                key={item.id}
                style={styles.faqItem}
                onPress={() => toggleExpand(item.id)}
                activeOpacity={0.7}
              >
                <View style={styles.faqHeader}>
                  <Text style={styles.faqQuestion}>{t(item.questionKey)}</Text>
                  <Icon
                    source={expandedId === item.id ? 'chevron-up' : 'chevron-down'}
                    size={20}
                    color="#999"
                  />
                </View>
                {expandedId === item.id && (
                  <Text style={styles.faqAnswer}>{t(item.answerKey)}</Text>
                )}
              </TouchableOpacity>
            ))}
          </View>
        </View>
        )}

        {/* 联系方式 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('helpCenter.contactUs')}</Text>
          <View style={styles.contactCard}>
            <View style={styles.contactItem}>
              <Icon source="phone" size={20} color="#667eea" />
              <Text style={styles.contactText}>{t('helpCenter.hotline')}: 400-XXX-XXXX</Text>
            </View>
            <View style={styles.contactItem}>
              <Icon source="email" size={20} color="#667eea" />
              <Text style={styles.contactText}>{t('helpCenter.email')}: support@cretas.com</Text>
            </View>
            <View style={styles.contactItem}>
              <Icon source="clock-outline" size={20} color="#667eea" />
              <Text style={styles.contactText}>{t('helpCenter.workingHours')}: {t('helpCenter.workingHoursValue')}</Text>
            </View>
          </View>
        </View>

        <View style={{ height: 32 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  backButton: {
    padding: 4,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
  },
  content: {
    flex: 1,
  },
  quickActions: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    padding: 20,
    backgroundColor: '#fff',
    marginBottom: 16,
  },
  actionItem: {
    alignItems: 'center',
  },
  actionIcon: {
    width: 56,
    height: 56,
    borderRadius: 28,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
  },
  actionText: {
    fontSize: 13,
    color: '#666',
  },
  section: {
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 13,
    color: '#999',
    marginLeft: 20,
    marginBottom: 8,
  },
  faqList: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    borderRadius: 12,
    overflow: 'hidden',
  },
  faqItem: {
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  faqHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  faqQuestion: {
    flex: 1,
    fontSize: 15,
    color: '#333',
    fontWeight: '500',
    marginRight: 12,
  },
  faqAnswer: {
    fontSize: 14,
    color: '#666',
    lineHeight: 22,
    marginTop: 12,
  },
  contactCard: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    borderRadius: 12,
    padding: 16,
  },
  contactItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
  },
  contactText: {
    fontSize: 14,
    color: '#333',
    marginLeft: 12,
  },
});

export default HelpCenterScreen;
