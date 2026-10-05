import { useAuthStore } from '../store/authStore';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';
import { getUserRole } from '../types/auth';
import {
  FRONTLINE_AI_ENTRY_GLOBAL_ENABLED,
  FRONTLINE_AI_SCREEN_ID,
  isFrontlineAIEntryEnabled,
} from '../config/frontlineAIEntry';

/**
 * 当前用户是否显示一线 AI 助手入口（并注册 `AIChat` 路由）。
 *
 * - 角色用 `getUserRole(user)` —— 与 AppNavigator 分派导航器用的是同一个函数，两边不会对角色码各说各话。
 * - 第②层用选择器直接取布尔值：功能配置晚到 / 被改时会触发重渲染
 *   （只取 `isScreenEnabled` 函数本身的话，函数引用不变，开关变了也不会刷新）。
 * - 第①层显式传入导入的常量（而不是依赖默认参数），测试 mock 这个常量才打得到这里。
 */
export function useFrontlineAIEntryEnabled(): boolean {
  const user = useAuthStore((state) => state.user);
  const remoteEnabled = useFactoryFeatureStore((state) => state.isScreenEnabled(FRONTLINE_AI_SCREEN_ID));
  return isFrontlineAIEntryEnabled(
    getUserRole(user),
    () => remoteEnabled,
    FRONTLINE_AI_ENTRY_GLOBAL_ENABLED,
  );
}
