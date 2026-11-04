<script setup lang="ts">
import type { BlacklakePeriodJob, BlacklakePeriodMetadata, BlacklakePeriodSchedule } from '@/api/smartbi/blacklake';
import { localizeBlacklakeText } from './blacklakePresentation';
import { formatBlacklakeDateTime } from './blacklakePeriod';

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
const relationText = (value: string) => /previous|prior|old|前期|历史|旧/i.test(value) ? '旧版本/前期报告' : '包含周期报告';
const AI_STATUS_TEXT: Record<string, string> = { complete: 'AI 已完成核对', partial: 'AI 已部分核对', not_applicable: '本板块不适用 AI 核对', busy: 'AI 正在处理', rate_limited: 'AI 服务繁忙，稍后重试', not_configured: '未配置 AI，以下仍是规则分析结果', unavailable: 'AI 未可用，以下仍是规则分析结果', not_run: 'AI 尚未运行，以下仍是规则分析结果', failed: 'AI 分析失败，以下仍是规则分析结果' };
const aiStatusText = (value: string) => AI_STATUS_TEXT[value] || `AI 状态：${value}`;
const JOB_STATUS_TEXT: Record<BlacklakePeriodJob['status'], string> = { queued: '等候分析', running: '正在分析', ready: '已生成', failed: '分析失败' };
const jobStatusText = (value: BlacklakePeriodJob['status']) => JOB_STATUS_TEXT[value];
const tokenText = (value: number | null | undefined) => value == null ? '未知' : value.toLocaleString('zh-CN');
</script>

<template>
  <section class="period-analysis" aria-label="周期分析状态">
    <div v-if="metadata" class="period-scope"><strong>{{ metadata.scopeDescription }}</strong><span>数据截至 {{ formatDate(metadata.dataAsOf) }} · {{ metadata.timezone }}</span><span>选中工单 {{ metadata.selectedProjects }} · 未归入周期 {{ metadata.unassignedProjects }}</span><span v-if="metadata.unassignedMovementOrders != null">未归期出入库单 {{ metadata.unassignedMovementOrders }}</span><span v-if="metadata.coverageStatus">{{ coverageText(metadata.coverageStatus) }}</span><span v-if="metadata.inventoryStatus">{{ inventoryText(metadata.inventoryStatus) }}</span></div>
    <div v-if="job" class="period-job" :class="`period-job--${job.status}`">
      <div><span class="period-job__label">周期分析</span><strong>{{ job.status === 'ready' && job.fromCache ? '复用已保存分析' : jobStatusText(job.status) }}</strong><small v-if="job.finishedAt">保存时间：{{ formatDate(job.finishedAt) }} · 版本 {{ job.revision }}</small><small v-else-if="job.result && job.nextAttemptAt">规则分析已保存，本地 AI 等待补充；下次重试：{{ formatDate(job.nextAttemptAt) }}</small><small v-else-if="job.nextAttemptAt">依赖服务暂不可用，将于 {{ formatDate(job.nextAttemptAt) }} 自动重试。</small><small v-else>数据已显示，分析任务不会阻塞当前明细。</small></div>
      <p v-if="job.status === 'failed'" role="alert">{{ localizeBlacklakeText(job.error, '分析任务失败，请重试。') }}</p><button v-if="job.status === 'failed'" type="button" @click="emit('retry')">重试分析</button>
      <div v-else-if="job.result" class="period-result"><p v-if="job.result.summary">{{ localizeBlacklakeText(job.result.summary) }}</p><ul v-if="job.result.observations.length"><li v-for="item in job.result.observations.slice(0, 3)" :key="item">{{ localizeBlacklakeText(item) }}</li></ul><small v-if="job.result.ai">{{ aiStatusText(job.result.ai.status) }}<span v-if="job.result.ai.message">：{{ localizeBlacklakeText(job.result.ai.message) }}</span></small><small v-if="job.result.history.comparisonAvailable || (job.result.history.comparisonAvailable == null && job.result.history.usedReports.some(report => /previous/.test(report.relation)))">历史变化：新增 {{ job.result.history.newIssues.length }} · 持续 {{ job.result.history.recurringIssues.length }} · 本次未再命中，待确认 {{ job.result.history.resolvedIssues.length }}</small><small v-else>历史变化：尚无可比历史，不能判断新增或已解决。</small><div v-if="job.result.history.usedReports.length" class="used-reports"><span>参考来源：</span><span v-for="report in job.result.history.usedReports" :key="report.id">{{ report.startDate }} 至 {{ report.endDate }} · {{ relationText(report.relation) }}</span></div></div>
    </div>
    <small v-if="job?.result?.ai.usage" class="period-schedule">本报告 AI 用量：输入 {{ tokenText(job.result.ai.usage.inputTokens) }} · 输出 {{ tokenText(job.result.ai.usage.outputTokens) }} · 合计 {{ tokenText(job.result.ai.usage.totalTokens) }} token（模型实际返回值）<span v-if="job.result.ai.cacheHit">；本次复用结果，新增消耗 0 token。</span></small>
    <small v-else-if="job?.result?.ai.status === 'complete' || job?.result?.ai.status === 'partial'" class="period-schedule">本报告未记录 token 用量（旧报告或模型未返回），不补估算。</small>
    <details v-if="history.length" class="period-history"><summary>打开历史周期报告（{{ history.length }}）</summary><button v-for="item in history" :key="item.id" type="button" class="history-row" @click="emit('open-history', item)"><span>{{ item.period.startDate }} 至 {{ item.period.endDate }}</span><small>{{ jobStatusText(item.status) }} · {{ formatDate(item.finishedAt || item.createdAt) }}</small></button></details>
    <p v-if="schedule" class="period-schedule">自动分析：{{ schedule.enabled ? '已启用' : '未启用' }} · {{ schedule.message || schedule.rules.map(rule => rule.label).join('、') || '暂无规则' }}</p>
  </section>
</template>

<style scoped>
.period-analysis{display:grid;gap:10px}.period-scope,.period-job,.period-history,.period-schedule{padding:12px 14px;border:1px solid #e5eaf1;border-radius:9px;background:#fff;color:#41536a;font-size:12px}.period-scope{display:flex;flex-wrap:wrap;gap:10px 18px}.period-scope strong{color:#1a293d}.period-scope span,.period-job small,.period-schedule{color:#8491a3;font-size:11px}.period-job{display:flex;align-items:center;justify-content:space-between;gap:16px}.period-job>div{display:grid;gap:3px}.period-job__label{color:#8491a3;font-size:11px}.period-job strong{color:#1a293d;font-size:15px}.period-job p{margin:0;max-width:52%;line-height:1.7}.period-job button{border:1px solid #bb635f;border-radius:6px;padding:6px 10px;color:#bb635f;background:#fff;cursor:pointer;font:inherit;font-size:12px}.period-job--ready{border-color:#bddbcf}.period-job--failed{border-color:#e6c2bf;background:#fffafa}.period-history summary{cursor:pointer;color:#1b65a8}.history-row{display:flex;justify-content:space-between;width:100%;margin-top:8px;padding:8px;border:0;border-top:1px solid #eef2f6;color:#41536a;background:#fff;text-align:left;cursor:pointer;font:inherit}.history-row small{color:#8491a3}@media(max-width:700px){.period-job{align-items:stretch;flex-direction:column}.period-job p{max-width:none}}
</style>
