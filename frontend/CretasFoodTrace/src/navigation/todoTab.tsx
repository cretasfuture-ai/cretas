/**
 * 「待办」tab —— 各岗位底栏共用（纪律排查 B4：App 按权限拼界面）。
 *
 * 显不显示只认后端 `GET /my-todos/types`（useMyTodoEntry）：谁能办待办由工厂的权限配置决定，
 * 不按岗位名写死，App 里也不抄一份权限映射。返回 null 时这一格不注册。
 *
 * 用法（必须是 Tab.Navigator 的直接子元素，所以是函数调用，不是组件）：
 *   const todoEntry = useMyTodoEntry();
 *   ...
 *   {todoTabScreen(Tab, todoEntry, 'dispatcher-tab-oa-todo')}
 */
import React from 'react';
import { Icon } from 'react-native-paper';
import OATodoStackNavigator from './OATodoStackNavigator';
import type { MyTodoEntry } from '../hooks/useMyTodos';

/** 各岗位底栏里「待办」这一格的路由名（同一个名字，深链 / 通知跳转只认这一个）。 */
export const TODO_TAB_ROUTE = 'OATodoTab';

type TabWithScreen = { Screen: React.ComponentType<any> };

export function todoTabScreen(Tab: TabWithScreen, entry: MyTodoEntry, testID: string): React.ReactElement | null {
  if (!entry.visible) return null;
  return (
    <Tab.Screen
      name={TODO_TAB_ROUTE}
      component={OATodoStackNavigator}
      options={{
        title: '待办',
        tabBarButtonTestID: testID,
        tabBarIcon: ({ color, size }: { color: string; size: number }) => (
          <Icon source="clipboard-check-outline" size={size} color={color} />
        ),
        tabBarBadge: entry.count > 0 ? entry.count : undefined,
      }}
    />
  );
}
