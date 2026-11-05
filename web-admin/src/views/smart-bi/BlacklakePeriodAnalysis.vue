<script setup lang="ts">
import type { BlacklakePeriodJob, BlacklakePeriodMetadata, BlacklakePeriodSchedule } from '@/api/smartbi/blacklake';
import { localizeBlacklakeText } from './blacklakePresentation';
import { formatBlacklakeDateTime } from './blacklakePeriod';
import AnalysisProgress from '@/components/smartbi/AnalysisProgress.vue';

withDefaults(defineProps<{
  metadata?: BlacklakePeriodMetadata;
  job?: BlacklakePeriodJob | null;
  history?: BlacklakePeriodJob[];
  schedule?: BlacklakePeriodSchedule | null;
}>(), { job: null, history: () => [], schedule: null });
const emit = defineEmits<{ (event: 'retry'): void; (event: 'open-history', job: BlacklakePeriodJob): void }>();
const formatDate = (value: string | null | undefined) => formatBlacklakeDateTime(value);
const coverageText = (value: string) => value === 'source_completeness_unverified' ? '已覆盖所选日期，来源完整性仍需核对' : /early|before|partial|incomplete|未|不完整/i.test(value) ? '仅分析截至采集时点，周期数据尚未齐全' : value;
const inventoryText = (value: string) => value === 'snapshot_within_period' ? '展示本期内采集时点余额，不是期末余额' : /unavailable|missing|unknown|不可用|缺失/i.test(value) ? '没有历史库存余额，不能把 0 条库存记录解释为 0 库存' : value;
const JOB_STATUS_TEXT: Record<BlacklakePeriodJob['status'], string> = { queued: '等候分析', running: '正在分析', ready: '已生成', failed: '分析失败' };
const jobStatusText = (value: BlacklakePeriodJob['status']) => JOB_STATUS_TEXT[value];
const tokenText = (value: number | null | undefined) => value == null ? '未知' : value.toLocaleString('zh-CN');
</script>

<template>
  <section class="period-analysis" aria-label="周期分析状态">
    <div class="period-job" v-if="job || metadata">
      <div><strong>本期分析</strong><span v-if="metadata">{{ metadata.startDate.slice(5) }} 至 {{ metadata.endDate.slice(5) }} · {{ metadata.selectedProjects }} 张工单</span></div>
      <span v-if="job" role="status">{{ job.status==='ready'?'分析已完成':jobStatusText(job.status) }}</span>
      <button v-if="job?.status==='failed'" type="button" @click="emit('retry')">重新分析</button>
    </div>
    <AnalysisProgress v-if="job" :progress="job.result?.ai.analysisFactCoverage" :status="job.status" :stage="job.result?.ai.analysisStage" />
    <p v-if="job?.status==='failed'" role="alert">{{ localizeBlacklakeText(job.error, '分析暂未完成，请重试。') }}</p>
    <details v-if="history.length" class="period-history"><summary>查看其他日期的分析</summary><button v-for="item in history" :key="item.id" type="button" class="history-row" @click="emit('open-history', item)"><span>{{ item.period.startDate.slice(5) }} 至 {{ item.period.endDate.slice(5) }}</span><small>{{ jobStatusText(item.status) }} · {{ formatDate(item.finishedAt || item.createdAt) }}</small></button></details>
    <details v-if="metadata || job?.result" class="period-history"><summary>数据说明与用量</summary>
      <p v-if="metadata">{{ metadata.scopeDescription }}</p>
      <p v-if="metadata">资料更新 {{ formatDate(metadata.dataAsOf) }}；未能确定日期的工单 {{ metadata.unassignedProjects }} 张。</p>
      <p v-if="metadata?.inventoryStatus">{{ inventoryText(metadata.inventoryStatus) }}</p>
      <p v-if="metadata?.coverageStatus">{{ coverageText(metadata.coverageStatus) }}</p>
      <p v-if="job?.result?.ai.usage">本报告 AI 用量：输入 {{ tokenText(job.result.ai.usage.inputTokens) }} · 输出 {{ tokenText(job.result.ai.usage.outputTokens) }} · 合计 {{ tokenText(job.result.ai.usage.totalTokens) }} token<span v-if="job.result.ai.cacheHit">；本次复用结果，新增消耗 0 token。</span></p>
      <p v-else-if="job?.result">本报告未记录 token 用量，不补估算。</p>
    </details>
  </section>
</template>

<style scoped>
.period-analysis{display:grid;grid-template-columns:1fr auto;align-items:start;gap:10px}.period-job{grid-column:1/-1}.period-hint{grid-column:1/-1;margin:0;color:#7a8599;font-size:13px}.period-history{font-size:12px}.period-history p{line-height:1.7}.period-job>div span{font-size:12px;color:#7a8599}@media(max-width:700px){.period-analysis{grid-template-columns:1fr}}.period-scope,.period-job,.period-history,.period-schedule{padding:12px 14px;border:1px solid #e5eaf1;border-radius:9px;background:#fff;color:#41536a;font-size:12px}.period-scope{display:flex;flex-wrap:wrap;gap:10px 18px}.period-scope strong{color:#1a293d}.period-scope span,.period-job small,.period-schedule{color:#8491a3;font-size:11px}.period-job{display:flex;align-items:center;justify-content:space-between;gap:16px}.period-job>div{display:grid;gap:3px}.period-job__label{color:#8491a3;font-size:11px}.period-job strong{color:#1a293d;font-size:15px}.period-job p{margin:0;max-width:52%;line-height:1.7}.period-job button{border:1px solid #bb635f;border-radius:6px;padding:6px 10px;color:#bb635f;background:#fff;cursor:pointer;font:inherit;font-size:12px}.period-job--ready{border-color:#bddbcf}.period-job--failed{border-color:#e6c2bf;background:#fffafa}.period-history summary{cursor:pointer;color:#1b65a8}.history-row{display:flex;justify-content:space-between;width:100%;margin-top:8px;padding:8px;border:0;border-top:1px solid #eef2f6;color:#41536a;background:#fff;text-align:left;cursor:pointer;font:inherit}.history-row small{color:#8491a3}@media(max-width:700px){.period-job{align-items:stretch;flex-direction:column}.period-job p{max-width:none}}
</style>
