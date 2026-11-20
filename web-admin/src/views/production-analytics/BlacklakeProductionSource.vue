<script setup lang="ts">
import { computed } from 'vue';
import type { ExternalProductionData } from '@/api/smartbi/factoryGold';

const props = defineProps<{ data: ExternalProductionData }>();
const collectedAt = computed(() => new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai', hourCycle: 'h23',
}).format(new Date(props.data.snapshotCollectedAt)));
</script>

<template>
  <div class="blacklake-source">
    <div class="blacklake-source__details">
      <strong>数据来源　{{ data.sourceLabel }}</strong>
      <span>快照采集　<time :datetime="data.snapshotCollectedAt">{{ collectedAt }}</time>（北京时间）</span>
      <el-tag v-if="data.stale" type="warning" effect="plain">数据未更新</el-tag>
    </div>
    <el-alert :title="`出成率与生产成本 · ${data.unavailableMessage}`" type="info" :closable="false" show-icon />
  </div>
</template>

<style scoped>
.blacklake-source { display: grid; gap: 12px; margin-bottom: 16px; }
.blacklake-source__details { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; }
.blacklake-source__details span { color: var(--el-text-color-secondary); }
</style>
