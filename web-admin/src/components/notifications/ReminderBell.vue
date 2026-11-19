<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { Bell } from '@element-plus/icons-vue';
import { getReminderBadge } from '@/api/reminder';
import { ApiError } from '@/types/api';

const router = useRouter();
const pending = ref<number>(0);
// 403 = 当前角色无提醒权限：隐藏铃铛并停止轮询
const forbidden = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

function stopPolling(): void {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

async function refresh(): Promise<void> {
  try {
    const res = await getReminderBadge();
    pending.value = res?.data?.pending ?? 0;
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      forbidden.value = true;
      stopPolling();
    }
    // 其它错误静默 — bell badge 失败不打扰用户，下一轮照常重试
  }
}

function goToReminders(): void {
  router.push('/sales/reminders');
}

onMounted(() => {
  refresh();
  timer = setInterval(refresh, 60_000);
});

onBeforeUnmount(() => {
  stopPolling();
});
</script>

<template>
  <el-tooltip v-if="!forbidden" content="我的提醒" placement="bottom">
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
