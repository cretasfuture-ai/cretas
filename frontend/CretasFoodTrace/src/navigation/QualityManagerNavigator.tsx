import React, { useRef } from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon, Text } from 'react-native-paper';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import AttendanceStackNavigator from './AttendanceStackNavigator';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';
import QILabelQcQueueScreen from '../screens/quality-inspector/QILabelQcQueueScreen';
import QILabelQcReviewScreen from '../screens/quality-inspector/QILabelQcReviewScreen';
import QILabelQcSubmittedScreen from '../screens/quality-inspector/QILabelQcSubmittedScreen';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';
import { QualityInspectorStackParamList } from '../types/qualityInspector';
import { getFactoryId, getUserRole } from '../types/auth';
import { useAuthStore } from '../store/authStore';
import { hasModulePermission } from '../utils/permissionHelper';

const Tab = createBottomTabNavigator();
const QualityStack = createNativeStackNavigator<QualityInspectorStackParamList>();

function QualityQueueScreen(props: React.ComponentProps<typeof QILabelQcQueueScreen>) {
  return <QILabelQcQueueScreen {...props} managerMode />;
}

function QualityManagerReviewScreen(props: React.ComponentProps<typeof QILabelQcReviewScreen>) {
  return <QILabelQcReviewScreen {...props} managerMode />;
}

function QualityManagerSubmittedScreen(props: React.ComponentProps<typeof QILabelQcSubmittedScreen>) {
  return <QILabelQcSubmittedScreen {...props} managerMode />;
}

function QualityManagerQueueStack() {
  return (
    <QualityStack.Navigator
      initialRouteName="QILabelQcQueue"
      screenLayout={screenErrorBoundary}
      screenOptions={{ headerShown: false }}
    >
      <QualityStack.Screen name="QILabelQcQueue" component={QualityQueueScreen} />
      <QualityStack.Screen name="QILabelQcReview" component={QualityManagerReviewScreen} />
      <QualityStack.Screen name="QILabelQcSubmitted" component={QualityManagerSubmittedScreen} />
    </QualityStack.Navigator>
  );
}

function QualityUnavailableScreen() {
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: '#F5F7FA' }} testID="quality-module-unavailable">
      <Icon source="lock-outline" size={44} color="#616161" />
      <Text variant="titleLarge" style={{ marginTop: 12 }}>质量模块未开通</Text>
      <Text variant="bodyLarge" style={{ marginTop: 8, textAlign: 'center', color: '#667085' }}>当前账号没有质量读取权限，请联系管理员开通后再操作。</Text>
    </View>
  );
}

function QualityManagerModuleScreen() {
  const user = useAuthStore((state) => state.user);
  return hasModulePermission(user, 'quality', 'read')
    ? <QualityManagerQueueStack />
    : <QualityUnavailableScreen />;
}

/** 质量经理：待审队列、审核/记录、考勤和账号；不暴露现场拍摄入口。 */
export default function QualityManagerNavigator() {
  const insets = useSafeAreaInsets();
  const user = useAuthStore((state) => state.user);
  const actorRef = useRef({ user, generation: 0 });
  if (actorRef.current.user !== user) {
    actorRef.current = { user, generation: actorRef.current.generation + 1 };
  }
  const actorIdentity = `${actorRef.current.generation}:${user?.id ?? 'none'}:${getFactoryId(user)}:${getUserRole(user)}`;

  return (
    <Tab.Navigator
      key={actorIdentity}
      initialRouteName="QualityManagerQueueTab"
      screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#08795A',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      <Tab.Screen
        name="QualityManagerQueueTab"
        component={QualityManagerModuleScreen}
        options={{
          title: '标签拍检',
          tabBarButtonTestID: 'quality-manager-tab-queue',
          tabBarIcon: ({ color, size }) => <Icon source="clipboard-check-outline" color={color} size={size} />,
        }}
      />
      <Tab.Screen
        name="QualityManagerAttendanceTab"
        component={AttendanceStackNavigator}
        options={{
          title: '考勤',
          tabBarButtonTestID: 'quality-manager-tab-attendance',
          tabBarIcon: ({ color, size }) => <Icon source="clock-outline" color={color} size={size} />,
        }}
      />
      <Tab.Screen
        name="QualityManagerProfileTab"
        component={MobileAccountScreen}
        options={{
          title: '我的',
          tabBarButtonTestID: 'quality-manager-tab-profile',
          tabBarIcon: ({ color, size }) => <Icon source="account-outline" color={color} size={size} />,
        }}
      />
    </Tab.Navigator>
  );
}
