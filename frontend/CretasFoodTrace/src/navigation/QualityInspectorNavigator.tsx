/**
 * 现场质检员导航器
 *
 * 质检员只负责标签拍照上传和查看处理记录；人工审核、分析报表和通用质检管理不在该角色中暴露。
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
import { QI_COLORS, QualityInspectorStackParamList } from '../types/qualityInspector';

const Tab = createBottomTabNavigator();
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

export default function QualityInspectorNavigator() {
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
      <Tab.Screen
        name="QIProfileTab"
        component={ProfileStack}
        options={{ tabBarLabel: '我的', tabBarButtonTestID: 'qi-tab-profile' }}
      />
    </Tab.Navigator>
  );
}
