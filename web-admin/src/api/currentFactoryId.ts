import { useAuthStore } from '@/store/modules/auth';

/**
 * API 层取当前工厂 ID 的唯一入口: 显式传入优先, 否则读 auth store
 * (store 自己从 localStorage['cretas_user'] 恢复, 并尊重平台管理员的工厂视角)。
 * ⛔ 不要在 API 文件里再直接读 localStorage['user'] —— 应用从不写这个 key,
 * 读出来永远是 null, 会在发请求之前就抛「未登录」(sales-needs / reminders 因此整页打不开)。
 */
export function requireFactoryId(explicit?: string): string {
  if (explicit) return explicit;
  const fid = useAuthStore().factoryId;
  if (!fid) throw new Error('未登录或当前账号未绑定工厂');
  return fid;
}
