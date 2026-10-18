<template>
  <div class="vision-page" data-testid="vision-training-page">
    <template v-if="hasFactoryContext">
      <div class="page-header">
        <div>
          <p class="eyebrow">质量管理 · 本厂日常检查</p>
          <h1>视觉质检训练</h1>
          <p class="subtitle">
            告诉系统要检查什么，再按提示准备照片、确认效果。
          </p>
        </div>
        <div class="factory-scope" data-testid="vision-factory-scope">
          <span>当前工厂</span>
          <strong>{{ factoryName }}</strong>
        </div>
      </div>

      <el-alert
        class="daily-note"
        type="info"
        :closable="false"
        show-icon
        title="每个工厂单独准备、单独试用"
        description="使用本厂照片，由质量负责人确认合格要求。新照片试用通过后，才能按流程启用。"
      />

      <FactoryVisionTrainingSetup :factory-id="factoryId" />
    </template>

    <el-result
      v-else
      data-testid="vision-training-no-auth"
      icon="warning"
      title="需要工厂账号"
      sub-title="当前登录账号没有工厂业务上下文，请使用工厂账号登录后再进入视觉质检训练。"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import FactoryVisionTrainingSetup from '@/components/vision-training/FactoryVisionTrainingSetup.vue';

const authStore = useAuthStore();
const hasFactoryContext = computed(() => Boolean(authStore.isFactory && authStore.factoryId));
const factoryId = computed(() => hasFactoryContext.value ? authStore.factoryId : '');
const factoryName = computed(() => hasFactoryContext.value ? (authStore.factoryName || authStore.factoryId) : '');
</script>

<style scoped>
.vision-page {
  min-height: 100%;
  padding: 20px;
  background: #f4f6f9;
  color: #1a2332;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
  padding: 4px 2px;
}

.eyebrow {
  margin: 0 0 6px;
  color: #1b65a8;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

h1 {
  margin: 0 0 8px;
  font-size: 24px;
  line-height: 1.25;
}

.subtitle {
  max-width: 720px;
  margin: 0;
  color: #7a8599;
  font-size: 14px;
  line-height: 1.7;
}

.factory-scope {
  display: flex;
  min-width: 180px;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  padding: 12px 16px;
  border: 1px solid #dceaf5;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

.factory-scope span { color: #7a8599; font-size: 12px; }
.factory-scope strong { font-size: 14px; }
.factory-scope code { color: #7a8599; font-size: 12px; }

.daily-note { margin-bottom: 16px; }

.vision-page :deep(.el-result) {
  margin: 48px auto;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
}

@media (max-width: 640px) {
  .vision-page { padding: 12px; }
  .page-header { flex-direction: column; gap: 14px; }
  .factory-scope { width: 100%; align-items: flex-start; }
}
</style>
