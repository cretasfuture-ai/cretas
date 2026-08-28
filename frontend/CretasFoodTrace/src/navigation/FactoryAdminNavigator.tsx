/**
 * Factory Admin 导航器入口
 * 仅 factory_super_admin 角色使用的新界面
 */

import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import FactoryAdminTabNavigator from './FactoryAdminTabNavigator';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator();

export function FactoryAdminNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="FactoryAdminMain" component={FactoryAdminTabNavigator} />
    </Stack.Navigator>
  );
}

export default FactoryAdminNavigator;
