import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import WHInboundStackNavigator from './warehouse/WHInboundStackNavigator';
import WHOutboundStackNavigator from './warehouse/WHOutboundStackNavigator';
import WHInventoryStackNavigator from './warehouse/WHInventoryStackNavigator';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
// 一线 AI 助手 (Line B 2026-10-05): 复用 FA 端 AIChatScreen, 熔断开关关掉时连路由都不注册
import AIChatScreen from '../screens/factory-admin/ai-analysis/AIChatScreen';
import { useFrontlineAIEntryEnabled } from '../hooks/useFrontlineAIEntry';

const Tab = createBottomTabNavigator();
const RootStack = createNativeStackNavigator();

/**
 * 仓库员根导航器: Tab 外包一层 Stack, AI 对话盖住 tab 栏。
 * 入口在收货/发货/库存三个首屏底部 (FrontlineAIEntry); AIChat 只在一线 AI 入口开关打开时注册。
 */
export default function WarehouseWorkerNavigator() {
  const aiEntryEnabled = useFrontlineAIEntryEnabled();
  return (
    <RootStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <RootStack.Screen name="WarehouseWorkerMain" component={WarehouseWorkerTabNavigator} />
      {aiEntryEnabled && (
        <RootStack.Screen name="AIChat" component={AIChatScreen} options={{ headerShown: true, title: 'AI 助手' }} />
      )}
    </RootStack.Navigator>
  );
}

/** 仓库员直接进入收货、发货和库存任务，不开放主管首页、分析、预警治理或管理型个人中心。 */
function WarehouseWorkerTabNavigator() {
  const insets = useSafeAreaInsets();
  const { isScreenEnabled } = useFactoryFeatureStore();

  return (
    <Tab.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#2E7D32',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      {isScreenEnabled('InboundManagement') && (
        <Tab.Screen
          name="WarehouseWorkerInboundTab"
          component={WHInboundStackNavigator}
          options={{ title: '收货', tabBarIcon: ({ color, size }) => <Icon source="package-down" color={color} size={size} /> }}
        />
      )}
      {isScreenEnabled('OutboundManagement') && (
        <Tab.Screen
          name="WarehouseWorkerOutboundTab"
          component={WHOutboundStackNavigator}
          options={{ title: '发货', tabBarIcon: ({ color, size }) => <Icon source="package-up" color={color} size={size} /> }}
        />
      )}
      <Tab.Screen
        name="WarehouseWorkerInventoryTab"
        component={WHInventoryStackNavigator}
        options={{ title: '库存', tabBarIcon: ({ color, size }) => <Icon source="warehouse" color={color} size={size} /> }}
      />
      <Tab.Screen
        name="WarehouseWorkerProfileTab"
        component={MobileAccountScreen}
        options={{ title: '我的', tabBarIcon: ({ color, size }) => <Icon source="account-outline" color={color} size={size} /> }}
      />
    </Tab.Navigator>
  );
}
