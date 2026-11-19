<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { Bell } from '@element-plus/icons-vue';
import { getReminderBadge } from '@/api/reminder';
import { ApiError } from '@/types/api';
import { usePermissionStore } from '@/store/modules/permission';

const router = useRouter();
const permissionStore = usePermissionStore();
const pending = ref<number>(0);
// 后端仍返回 403(前端权限快照与后端矩阵不一致)时的兜底: 隐藏铃铛并停止轮询。
const forbidden = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

// 与后端 ReminderController.badge 的 @RequirePermission 同一组:
// sales:read / sales:read_write / finance:read / finance:read_write / system:read_write。
// 没有这些权限的角色(如质量经理)请求会 403, 全局拦截器会弹红色 toast, 所以连铃铛带请求都不挂。
const canSeeReminders = computed<boolean>(() => {
  const readable = (module: 'sales' | 'finance') => {
    const level = permissionStore.getPermissionLevel(module);
    return level === 'r' || level === 'rw';
  };
  return readable('sales') || readable('finance') || permissionStore.getPermissionLevel('system') === 'rw';
});

async function refresh(): Promise<void> {
  try {
    const res = await getReminderBadge();
    pending.value = res?.data?.pending ?? 0;
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      forbidden.value = true;
      stop();
    }
    // 其它错误静默 — bell badge 失败不打扰用户，下一轮照常重试
  }
}

function goToReminders(): void {
  router.push('/sales/reminders');
}

function stop(): void {
  if (timer) clearInterval(timer);
  timer = null;
}

// 权限可能晚于组件挂载才加载完: 跟着 canSeeReminders 起停, 无权限时一次请求都不发。
watch(canSeeReminders, (allowed) => {
  stop();
  if (!allowed || forbidden.value) return;
  void refresh();
  timer = setInterval(refresh, 60_000);
}, { immediate: true });

onBeforeUnmount(stop);
</script>

<template>
  <el-tooltip v-if="canSeeReminders && !forbidden" content="我的提醒" placement="bottom">
    <el-badge :value="pending" :hidden="pending === 0" :max="99" class="reminder-bell">
      <el-icon class="header-action" @click="goToReminders">
        <Bell />
      </el-icon>
    </el-badge>
  </el-tooltip>
</template>

<style lang="scss" scoped>
.reminder-bell {
  :deep(.el-badge__content) {
    background: #f56c6c;
    border: 0;
  }
}

.header-action {
  font-size: 18px;
  cursor: pointer;
  color: var(--color-text-secondary, #7A8599);
  padding: 8px;
  border-radius: var(--radius-sm, 6px);
  transition: all 0.2s;
  // 修复铃铛图标 svg 宽度塌成 ~2px 不可见 (同 AppHeader.header-action)。
  :deep(svg) {
    width: 1em;
    height: 1em;
    flex-shrink: 0;
  }
  &:hover {
    background-color: var(--color-bg-hover, #EDF2F7);
    color: var(--color-primary, #1B65A8);
  }
}
</style>
