/**
 * 现场质检员导航器
 *
 * 质检员负责批次质检、标签拍照上传和查看处理记录；不良品处置 / 批次召回按 quality 模块权限出现 (B14)。
 * 人工审核、分析报表不在该角色中暴露。
 */

import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';
import QIHomeScreen from '../screens/quality-inspector/QIHomeScreen';
import QILabelQcCreateScreen from '../screens/quality-inspector/QILabelQcCreateScreen';
import QILabelQcQueueScreen from '../screens/quality-inspector/QILabelQcQueueScreen';
import QILabelQcSubmittedScreen from '../screens/quality-inspector/QILabelQcSubmittedScreen';
import QIProfileScreen from '../screens/quality-inspector/QIProfileScreen';
import QISettingsScreen from '../screens/quality-inspector/QISettingsScreen';
import QIBatchSelectScreen from '../screens/quality-inspector/QIBatchSelectScreen';
import QIFormScreen from '../screens/quality-inspector/QIFormScreen';
import QIInspectListScreen from '../screens/quality-inspector/QIInspectListScreen';
import QIScanScreen from '../screens/quality-inspector/QIScanScreen';
import QIRecordsScreen from '../screens/quality-inspector/QIRecordsScreen';
import QIRecordDetailScreen from '../screens/quality-inspector/QIRecordDetailScreen';
import QIResultScreen from '../screens/quality-inspector/QIResultScreen';
import QIDispositionScreen from '../screens/quality-inspector/QIDispositionScreen';
import QIDispositionExecuteScreen from '../screens/quality-inspector/QIDispositionExecuteScreen';
import QIRecallScreen from '../screens/quality-inspector/QIRecallScreen';
import QIRecallCreateScreen from '../screens/quality-inspector/QIRecallCreateScreen';
import QIRecallDetailScreen from '../screens/quality-inspector/QIRecallDetailScreen';
import QITimeoutScreen from '../screens/quality-inspector/QITimeoutScreen';
import QITimeoutDecisionScreen from '../screens/quality-inspector/QITimeoutDecisionScreen';
import { QI_COLORS, QualityInspectorStackParamList } from '../types/qualityInspector';
// 一线 AI 助手 (Line B 2026-10-05): 复用 FA 端 AIChatScreen, 熔断开关关掉时连路由都不注册
import AIChatScreen from '../screens/factory-admin/ai-analysis/AIChatScreen';
import { useFrontlineAIEntryEnabled } from '../hooks/useFrontlineAIEntry';
import { useMyTodoEntry } from '../hooks/useMyTodos';
import { todoTabScreen } from './todoTab';

const Tab = createBottomTabNavigator();
const RootStack = createNativeStackNavigator();
const HomeStackNavigator = createNativeStackNavigator<QualityInspectorStackParamList>();
const CaptureStackNavigator = createNativeStackNavigator<QualityInspectorStackParamList>();
const RecordsStackNavigator = createNativeStackNavigator<QualityInspectorStackParamList>();
const ProfileStackNavigator = createNativeStackNavigator<QualityInspectorStackParamList>();

const stackScreenOptions = {
  headerStyle: { backgroundColor: QI_COLORS.primary },
  headerTintColor: '#fff',
  headerTitleStyle: { fontWeight: '600' as const },
};

function HomeStack() {
  return (
    <HomeStackNavigator.Navigator screenLayout={screenErrorBoundary} screenOptions={stackScreenOptions}>
      <HomeStackNavigator.Screen name="QIHome" component={QIHomeScreen} options={{ title: '质检拍照' }} />
      <HomeStackNavigator.Screen name="QIInspectList" component={QIInspectListScreen} options={{ title: '待检批次' }} />
      {/* 纪律排查 B14 App 端: 首页入口按 quality 模块权限出现; 页面内写/审批按钮再各按后端权限判 */}
      <HomeStackNavigator.Screen name="QIDisposition" component={QIDispositionScreen} options={{ title: '不良品处置' }} />
      <HomeStackNavigator.Screen name="QIDispositionExecute" component={QIDispositionExecuteScreen} options={{ title: '处置批次' }} />
      <HomeStackNavigator.Screen name="QIRecall" component={QIRecallScreen} options={{ title: '批次召回' }} />
      <HomeStackNavigator.Screen name="QIRecallCreate" component={QIRecallCreateScreen} options={{ title: '发起召回' }} />
      <HomeStackNavigator.Screen name="QIRecallDetail" component={QIRecallDetailScreen} options={{ title: '召回详情' }} />
      {/* 食品 DAG 缺口 14: 超时处置 (网页同名页签搬到 App, 质检员不登网页) */}
      <HomeStackNavigator.Screen name="QITimeout" component={QITimeoutScreen} options={{ title: '超时处置' }} />
      <HomeStackNavigator.Screen name="QITimeoutDecision" component={QITimeoutDecisionScreen} options={{ title: '处置超时的料' }} />
    </HomeStackNavigator.Navigator>
  );
}

function CaptureStack() {
  return (
    <CaptureStackNavigator.Navigator screenLayout={screenErrorBoundary} screenOptions={stackScreenOptions}>
      <CaptureStackNavigator.Screen
        name="QILabelQcCreate"
        component={QILabelQcCreateScreen}
        options={{ title: '发起标签拍检' }}
      />
      <CaptureStackNavigator.Screen name="QIInspectList" component={QIInspectListScreen} options={{ title: '待检批次' }} />
      <CaptureStackNavigator.Screen name="QIBatchSelect" component={QIBatchSelectScreen} options={{ title: '选择批次' }} />
      <CaptureStackNavigator.Screen name="QIScan" component={QIScanScreen} options={{ title: '扫描批次' }} />
      <CaptureStackNavigator.Screen name="QIForm" component={QIFormScreen} options={{ title: '动态质检' }} />
      <CaptureStackNavigator.Screen name="QIResult" component={QIResultScreen} options={{ title: '质检结果' }} />
      <CaptureStackNavigator.Screen
        name="QILabelQcSubmitted"
        component={QILabelQcSubmittedScreen}
        options={{ title: '拍检状态', headerBackVisible: false }}
      />
    </CaptureStackNavigator.Navigator>
  );
}

function RecordsStack() {
  return (
    <RecordsStackNavigator.Navigator screenLayout={screenErrorBoundary} screenOptions={stackScreenOptions}>
      <RecordsStackNavigator.Screen
        name="QILabelQcQueue"
        component={QILabelQcQueueScreen}
        options={{ title: '拍检记录' }}
      />
      <RecordsStackNavigator.Screen name="QIRecords" component={QIRecordsScreen} options={{ title: '质检记录' }} />
      <RecordsStackNavigator.Screen name="QIRecordDetail" component={QIRecordDetailScreen} options={{ title: '质检详情' }} />
      <RecordsStackNavigator.Screen
        name="QILabelQcSubmitted"
        component={QILabelQcSubmittedScreen}
        options={{ title: '拍检状态' }}
      />
      <RecordsStackNavigator.Screen
        name="QILabelQcCreate"
        component={QILabelQcCreateScreen}
        options={{ title: '发起标签拍检' }}
      />
    </RecordsStackNavigator.Navigator>
  );
}

function ProfileStack() {
  return (
    <ProfileStackNavigator.Navigator screenLayout={screenErrorBoundary} screenOptions={stackScreenOptions}>
      <ProfileStackNavigator.Screen name="QIProfile" component={QIProfileScreen} options={{ title: '我的' }} />
      <ProfileStackNavigator.Screen name="QISettings" component={QISettingsScreen} options={{ title: '设置' }} />
    </ProfileStackNavigator.Navigator>
  );
}

type TabIconName = 'home' | 'camera' | 'images' | 'person';

const TAB_ICONS: Record<string, TabIconName> = {
  QIHomeTab: 'home',
  QIInspectTab: 'camera',
  QIRecordsTab: 'images',
  QIProfileTab: 'person',
};

/**
 * 质检员根导航器: Tab 外包一层 Stack, AI 对话盖住 tab 栏。
 * 入口在首页 (FrontlineAIEntry); AIChat 只在一线 AI 入口开关打开时注册。
 */
export default function QualityInspectorNavigator() {
  const aiEntryEnabled = useFrontlineAIEntryEnabled();
  return (
    <RootStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <RootStack.Screen name="QIMain" component={QualityInspectorTabNavigator} />
      {aiEntryEnabled && (
        <RootStack.Screen
          name="AIChat"
          component={AIChatScreen}
          options={{ ...stackScreenOptions, headerShown: true, title: 'AI 助手' }}
        />
      )}
    </RootStack.Navigator>
  );
}

function QualityInspectorTabNavigator() {
  const todoEntry = useMyTodoEntry();
  const insets = useSafeAreaInsets();
  const bottomInset = Math.max(insets.bottom, 6);

  return (
    <Tab.Navigator
      screenLayout={screenErrorBoundary}
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarIcon: ({ focused, color, size }) => {
          const icon = TAB_ICONS[route.name] ?? 'home';
          return (
            <Ionicons
              name={focused ? icon : `${icon}-outline`}
              size={size}
              color={color}
            />
          );
        },
        tabBarActiveTintColor: QI_COLORS.primary,
        tabBarInactiveTintColor: QI_COLORS.textSecondary,
        tabBarStyle: {
          backgroundColor: QI_COLORS.card,
          borderTopColor: QI_COLORS.border,
          height: 64 + bottomInset,
          paddingBottom: bottomInset + 2,
          paddingTop: 6,
        },
        tabBarItemStyle: { minHeight: 48 },
        tabBarLabelStyle: { fontSize: 11, lineHeight: 14, fontWeight: '600', marginBottom: 1 },
        tabBarHideOnKeyboard: true,
      })}
    >
      <Tab.Screen
        name="QIHomeTab"
        component={HomeStack}
        options={{ tabBarLabel: '首页', tabBarButtonTestID: 'qi-tab-home' }}
      />
      <Tab.Screen
        name="QIInspectTab"
        component={CaptureStack}
        options={{ tabBarLabel: '发起拍检', tabBarButtonTestID: 'qi-tab-inspect' }}
      />
      <Tab.Screen
        name="QIRecordsTab"
        component={RecordsStack}
        options={{ tabBarLabel: '拍检记录', tabBarButtonTestID: 'qi-tab-records' }}
      />
      {todoTabScreen(Tab, todoEntry, 'qi-tab-oa-todo')}
      <Tab.Screen
        name="QIProfileTab"
        component={ProfileStack}
        options={{ tabBarLabel: '我的', tabBarButtonTestID: 'qi-tab-profile' }}
      />
    </Tab.Navigator>
  );
}
