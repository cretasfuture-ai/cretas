<script setup lang="ts">
import { ref } from 'vue';
import DataAlertsPanel from '@/components/smartbi/DataAlertsPanel.vue';
import { pythonFetch } from '@/api/smartbi/common';
import type { DatasetAlertReport } from '@/api/smartbi/data-alerts';
const tab = ref('excel');const inline = ref<DatasetAlertReport>();const error = ref('');
async function analyzeApi(ai = false) {
  error.value = '';
  try {
    const value = await pythonFetch<{ success: boolean; data: DatasetAlertReport }>('/api/smartbi/data-alerts/analyze', { method: 'POST', preserveResponseKeys: true, timeoutMs: 170000,
      body: JSON.stringify({ source_type: 'api', source_id: 'synthetic-inline', dataset_kind: 'generic', with_ai: ai,
        columns: [{ originalName: '测量值', standardName: '测量值', isMeasure: true, fieldType: 'number' }],
        rows: [{ 流水号: 'SYNTH-1', 测量值: 100 }, { 流水号: 'SYNTH-2', 测量值: null }, { 流水号: 'SYNTH-3', 测量值: '待核' }, { 流水号: 'SYNTH-1', 测量值: 100 }] }) });
    inline.value = value.data;
  } catch (e) { error.value = e instanceof Error ? e.message : '接口分析失败'; }
}
</script>
<template>
  <main class="shared-test"><h1>SmartBI 多来源异常识别</h1><p>独立合成数据验收：实际 Excel 解析入库、接口输入、租户隔离与本地 AI。使用与常规 Excel 分析页相同的提醒组件。</p><el-alert v-if="error" type="warning" :closable="false" :title="error" />
    <el-tabs v-model="tab">
      <el-tab-pane label="Excel 质量检查" name="excel"><DataAlertsPanel :upload-id="101" :active="tab === 'excel'" can-review /></el-tab-pane>
      <el-tab-pane label="接口数据检查" name="api"><el-button @click="analyzeApi()">检查合成接口数据</el-button><el-button @click="analyzeApi(true)">本地 AI 核对接口数据</el-button><DataAlertsPanel v-if="inline" :report="inline" :active="tab === 'api'" /></el-tab-pane>
      <el-tab-pane label="成本权限边界" name="cost"><DataAlertsPanel :upload-id="102" :active="tab === 'cost'" can-review /></el-tab-pane>
    </el-tabs>
  </main>
</template>
<style scoped>.shared-test{padding:24px;max-width:1500px;margin:auto}h1{font-size:24px}p{line-height:1.8;color:#596579}</style>
