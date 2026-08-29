import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { AttendanceStackParamList } from '../types/navigation';  // ✅ 统一命名
import {
  TimeClockScreen,
  AttendanceStatisticsScreen,
  AttendanceHistoryScreen,
  DepartmentAttendanceScreen,
} from '../screens/attendance';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<AttendanceStackParamList>();  // ✅ 使用新类型

/**
 * 考勤模块导航器
 * Phase 2新增
 * ✅ 使用统一的AttendanceStackParamList类型
 */
export function AttendanceStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
      }}
    >
      <Stack.Screen
        name="TimeClockScreen"
        component={TimeClockScreen}
        options={{ title: '考勤打卡' }}
      />

      {/* 工时统计页面 */}
      <Stack.Screen
        name="TimeStatistics"
        component={AttendanceStatisticsScreen}
        options={{ title: '工时统计' }}
      />

      {/* Phase 3 P2 - 工时查询 */}
      <Stack.Screen
        name="AttendanceHistory"
        component={AttendanceHistoryScreen}
        options={{ title: '工时查询' }}
      />

      {/* P2-考勤 - 部门考勤查询 */}
      <Stack.Screen
        name="DepartmentAttendance"
        component={DepartmentAttendanceScreen}
        options={{ title: '部门考勤' }}
      />
    </Stack.Navigator>
  );
}

export default AttendanceStackNavigator;
