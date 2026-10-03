import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import type { FAAIStackParamList } from '../types/navigation';
import AIReportScreen from '../screens/factory-admin/ai-analysis/AIReportScreen';
import AIReportDetailScreen from '../screens/factory-admin/ai-analysis/AIReportDetailScreen';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<Pick<FAAIStackParamList, 'AIReport' | 'AIReportDetail'>>();

/** Read-only history and full details shared by factory owners, finance and warehouse managers. */
export default function OperatingReportStackNavigator() {
  return <Stack.Navigator screenLayout={screenErrorBoundary}
    screenOptions={{ headerShown: true, headerBackTitle: '返回' }}>
    <Stack.Screen name="AIReport" component={AIReportScreen} options={{ title: '经营报告' }} />
    <Stack.Screen name="AIReportDetail" component={AIReportDetailScreen} options={{ title: '报告详情' }} />
  </Stack.Navigator>;
}
