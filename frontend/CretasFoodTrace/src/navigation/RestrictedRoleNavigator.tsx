import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Card, Icon, Text } from 'react-native-paper';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuthStore } from '../store/authStore';
import { useMyTodoEntry } from '../hooks/useMyTodos';
import { todoTabScreen } from './todoTab';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Tab = createBottomTabNavigator<any>();

function RestrictedRoleScreen() {
  const insets = useSafeAreaInsets();
  const logout = useAuthStore((state) => state.logout);

  return (
    <View style={[styles.container, { paddingTop: insets.top + 24, paddingBottom: insets.bottom + 24 }]}>
      <Card style={styles.card}>
        <Card.Content>
          <Text variant="headlineSmall" style={styles.title}>暂无可用的移动工作台</Text>
          <Text style={styles.body}>
            当前账号角色尚未配置 RN 工作路径。请联系管理员核对角色；系统不会自动开放通用业务入口。
          </Text>
          <Button mode="contained" onPress={logout} style={styles.button}>退出登录</Button>
        </Card.Content>
      </Card>
    </View>
  );
}

/**
 * 兜底锁定页。B4: 若后端 /my-todos/types 说这个账号有能办的待办, 底栏多一格「待办」
 * (只开放待办, 不开放别的业务入口); 没有待办时只剩锁定页, 底栏隐藏 —— 与改前一致。
 */
export default function RestrictedRoleNavigator() {
  const todoEntry = useMyTodoEntry();
  return (
    <Tab.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{ headerShown: false, tabBarStyle: todoEntry.visible ? undefined : { display: 'none' } }}>
      <Tab.Screen
        name="RestrictedHomeTab"
        component={RestrictedRoleScreen}
        options={{
          title: '首页',
          tabBarButtonTestID: 'restricted-tab-home',
          tabBarIcon: ({ color, size }) => <Icon source="lock-outline" size={size} color={color} />,
        }}
      />
      {todoTabScreen(Tab, todoEntry, 'restricted-tab-oa-todo')}
    </Tab.Navigator>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', backgroundColor: '#F5F5F5', paddingHorizontal: 20 },
  card: { width: '100%' },
  title: { marginBottom: 12 },
  body: { color: '#667085', lineHeight: 22 },
  button: { marginTop: 24, minHeight: 48, justifyContent: 'center' },
});
