import React, { useRef } from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon, Text } from 'react-native-paper';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import AttendanceStackNavigator from './AttendanceStackNavigator';
import EquipmentAlertsScreen from '../screens/processing/EquipmentAlertsScreen';
import EquipmentDetailScreen from '../screens/processing/EquipmentDetailScreen';
import EquipmentManagementScreen from '../screens/processing/EquipmentManagementScreen';
import EquipmentMonitoringScreen from '../screens/processing/EquipmentMonitoringScreen';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';
import { ProcessingStackParamList } from '../types/navigation';
import { getFactoryId, getUserRole } from '../types/auth';
import { useAuthStore } from '../store/authStore';
import { hasModulePermission } from '../utils/permissionHelper';

const Tab = createBottomTabNavigator();
const EquipmentStack = createNativeStackNavigator<ProcessingStackParamList>();

function EquipmentStackNavigator() {
  return (
    <EquipmentStack.Navigator
      initialRouteName="EquipmentManagement"
      screenLayout={screenErrorBoundary}
      screenOptions={{ headerShown: false }}
    >
      <EquipmentStack.Screen name="EquipmentManagement" component={EquipmentManagementScreen} />
      <EquipmentStack.Screen name="EquipmentMonitoring" component={EquipmentMonitoringScreen} />
      <EquipmentStack.Screen name="EquipmentDetail" component={EquipmentDetailScreen} />
      <EquipmentStack.Screen name="EquipmentAlerts" component={EquipmentAlertsScreen} />
    </EquipmentStack.Navigator>
  );
}

function EquipmentUnavailableScreen() {
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: '#F5F7FA' }} testID="equipment-module-unavailable">
      <Icon source="lock-outline" size={44} color="#616161" />
      <Text variant="titleLarge" style={{ marginTop: 12 }}>设备模块未开通</Text>
      <Text variant="bodyLarge" style={{ marginTop: 8, textAlign: 'center', color: '#667085' }}>当前账号没有设备读取权限，请联系管理员开通后再操作。</Text>
    </View>
  );
}

function EquipmentAdminModuleScreen() {
  const user = useAuthStore((state) => state.user);
  return hasModulePermission(user, 'equipment', 'read')
    ? <EquipmentStackNavigator />
    : <EquipmentUnavailableScreen />;
}

/** 设备管理员：设备工作台、考勤和账号，业务入口均复用现有页面。 */
export default function EquipmentAdminNavigator() {
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
      initialRouteName="EquipmentAdminEquipmentTab"
      screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#1565C0',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      <Tab.Screen
        name="EquipmentAdminEquipmentTab"
        component={EquipmentAdminModuleScreen}
        options={{
          title: '设备',
          tabBarButtonTestID: 'equipment-admin-tab-equipment',
          tabBarIcon: ({ color, size }) => <Icon source="cog-outline" color={color} size={size} />,
        }}
      />
      <Tab.Screen
        name="EquipmentAdminAttendanceTab"
        component={AttendanceStackNavigator}
        options={{
          title: '考勤',
          tabBarButtonTestID: 'equipment-admin-tab-attendance',
          tabBarIcon: ({ color, size }) => <Icon source="clock-outline" color={color} size={size} />,
        }}
      />
      <Tab.Screen
        name="EquipmentAdminProfileTab"
        component={MobileAccountScreen}
        options={{
          title: '我的',
          tabBarButtonTestID: 'equipment-admin-tab-profile',
          tabBarIcon: ({ color, size }) => <Icon source="account-outline" color={color} size={size} />,
        }}
      />
    </Tab.Navigator>
  );
}
