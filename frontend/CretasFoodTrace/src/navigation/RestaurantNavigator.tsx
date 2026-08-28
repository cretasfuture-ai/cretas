import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  RRecipeStackNavigator,
  RRequisitionStackNavigator,
  RStocktakingStackNavigator,
  RWastageStackNavigator,
} from './restaurant';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';

const Tab = createBottomTabNavigator();

/**
 * 餐饮侧的入口 —— 在这之前**没有**。
 *
 * <h3>为什么补这个（2026-08-28 逐屏走查 G1）</h3>
 *
 * `navigation/restaurant/` 下有四棵 stack（菜谱 3 屏 / 领用 3 屏 / 盘点 3 屏 / 损耗 2 屏），
 * 它们只在自己的 `index.ts` 里互相导出，**没有任何地方 import 过** ——
 * 而 `AppNavigator` 的角色分派里也没有任何一条命中餐饮角色。
 *
 * 生产库实测（2026-08-28）：`restaurant_owner` / `restaurant_manager` /
 * `restaurant_purchaser` 各 1 个账号，三个人登录后一律落到 `RestrictedRoleNavigator`
 * （锁定页），这 11 屏一屏都进不去。
 *
 * ⚠️ 顺带订正一条我先前记错的事：**平台侧是进得去的** ——
 * `PlatformStackNavigator` 挂在 `MainNavigator` 里，而 `platform_admin`（生产库有 1 个账号）
 * 命中 `MainNavigator` 分支。之前把平台侧和餐饮侧一起算成「约 270 条路由无人可达」是错的。
 *
 * <h3>这棵树是「把已经建好的东西装起来」，不是新设计</h3>
 *
 * 四个 tab 就是那四棵 stack 各自的入口屏，⛔ 没有新造任何业务屏；
 * 「我的」复用与仓库员同一个 {@link MobileAccountScreen}。
 * 如果业务上认为餐饮侧还需要一个首页/看板，那是产品决定，这里不替它决定 ——
 * 现在的状态是「11 屏进得去了」，不是「餐饮侧做完了」。
 */
export default function RestaurantNavigator() {
  const insets = useSafeAreaInsets();

  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#C2185B',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      <Tab.Screen
        name="RestaurantRecipeTab"
        component={RRecipeStackNavigator}
        options={{ title: '菜谱', tabBarIcon: ({ color, size }) => <Icon source="chef-hat" color={color} size={size} /> }}
      />
      <Tab.Screen
        name="RestaurantRequisitionTab"
        component={RRequisitionStackNavigator}
        options={{ title: '领用', tabBarIcon: ({ color, size }) => <Icon source="clipboard-arrow-right" color={color} size={size} /> }}
      />
      <Tab.Screen
        name="RestaurantStocktakingTab"
        component={RStocktakingStackNavigator}
        options={{ title: '盘点', tabBarIcon: ({ color, size }) => <Icon source="clipboard-list" color={color} size={size} /> }}
      />
      <Tab.Screen
        name="RestaurantWastageTab"
        component={RWastageStackNavigator}
        options={{ title: '损耗', tabBarIcon: ({ color, size }) => <Icon source="delete-variant" color={color} size={size} /> }}
      />
      <Tab.Screen
        name="RestaurantProfileTab"
        component={MobileAccountScreen}
        options={{ title: '我的', tabBarIcon: ({ color, size }) => <Icon source="account" color={color} size={size} /> }}
      />
    </Tab.Navigator>
  );
}
