<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { FactoryRecommendation } from '@/api/smartbi/factory-charts';
import { analysisFamilies, chartTypeLabel, recommendedAnalysisIds, replaceFamilySelection, type AnalysisFamily } from './factoryChartGuide';

const props = defineProps<{
  items: FactoryRecommendation[];
  selectedIds: string[];
  subjects: Array<{ id: string; label: string }>;
  subject: string | null;
  loading: boolean;
  context?: { mode: string; domain: string; family: string };
}>();
const emit = defineEmits<{
  analysis: [ids: string[]];
  selection: [ids: string[]];
  subject: [id: string | null];
  context: [value: { mode: string; domain: string; family: string }];
}>();
const mode = ref(props.context?.mode || 'guide');
const domain = ref(props.context?.domain || 'all');
const family = ref(props.context?.family || 'all');
const search = ref('');
const families = computed(() => analysisFamilies(props.items));
const domains = computed(() => [...new Set(families.value.map(group => group.domain))]);
const scopedFamilies = computed(() => families.value.filter(group => domain.value === 'all' || group.domain === domain.value));
const domainChoices = computed(() => domain.value !== 'all' && !domains.value.includes(domain.value)
  ? [...domains.value, domain.value] : domains.value);
const rememberedFamilyLabels = ref(new Map<string, string>());
watch(families, groups => groups.forEach(group => rememberedFamilyLabels.value.set(group.id, group.label)), { immediate: true });
const familyChoices = computed(() => {
  if (family.value === 'all' || scopedFamilies.value.some(group => group.id === family.value)) return scopedFamilies.value;
  return [...scopedFamilies.value, {
    id: family.value,
    label: `${rememberedFamilyLabels.value.get(family.value) || family.value}（当前数据无可用分析）`,
    domain: domain.value,
    items: [],
  }];
});
const searchedFamilies = computed(() => scopedFamilies.value.filter(group => !search.value.trim() || group.label.includes(search.value.trim())));
const recommendationIds = computed(() => recommendedAnalysisIds(props.items, domain.value, family.value));
const subjectChoices = computed(() => [
  { label: '全部产品 / 全厂范围', value: '' },
  ...props.subjects.map(item => ({ label: item.label, value: item.id })),
  ...(props.subject && !props.subjects.some(item => item.id === props.subject)
    ? [{ value: props.subject, label: '此前选择的产品（当前来源无数据）' }] : []),
]);
watch(domain, () => { family.value = 'all'; });
watch([mode, domain, family], () => emit('context', { mode: mode.value, domain: domain.value, family: family.value }));

const questions: Record<string, string> = {
  生产: '生产完成情况怎样？', 成本: '成本有哪些变化？', 质量: '质量表现怎样？',
  库存: '库存有哪些风险？', 销售: '销售表现怎样？', 采购: '采购情况怎样？',
  经营: '经营表现怎样？', 财务: '资金与经营情况怎样？',
};
function chosen(group: AnalysisFamily): string {
  return group.items.find(item => props.selectedIds.includes(item.id))?.id || group.items[0].id;
}
function selected(group: AnalysisFamily): boolean { return group.items.some(item => props.selectedIds.includes(item.id)); }
function toggle(group: AnalysisFamily, checked: unknown) {
  emit('selection', replaceFamilySelection(props.selectedIds, group, checked ? chosen(group) : null));
}
function variant(group: AnalysisFamily, id: string) { emit('selection', replaceFamilySelection(props.selectedIds, group, id)); }
</script>

<template>
  <section class="analysis-guide" data-testid="factory-chart-guide">
    <div class="guide-header">
      <el-radio-group v-model="mode" aria-label="图表查找方式" data-testid="factory-chart-mode">
        <el-radio-button value="guide">问答引导</el-radio-button>
        <el-radio-button value="browse">按分类浏览</el-radio-button>
      </el-radio-group>
      <span class="guide-note">{{ families.length }} 类分析 · 产品、单位与画法按需选择</span>
    </div>
    <div class="guide-step">
      <h3>{{ mode === 'guide' ? '1. 想了解什么？' : '业务分类' }}</h3>
      <el-radio-group v-model="domain" aria-label="业务问题" data-testid="factory-chart-guide-domain">
        <el-radio-button value="all">{{ mode === 'guide' ? '先看整体情况' : '全部分类' }}</el-radio-button>
        <el-radio-button v-for="item in domainChoices" :key="item" :value="item">{{ mode === 'guide' ? `${questions[item] || `${item}情况怎样？`}${!domains.includes(item) ? '（当前数据无可用分析）' : ''}` : `${item}${!domains.includes(item) ? '（当前数据无可用分析）' : ''}` }}</el-radio-button>
      </el-radio-group>
    </div>
    <div class="guide-step guide-fields">
      <div v-if="mode === 'guide'" class="guide-field">
        <label for="factory-analysis-topic">2. 重点看哪方面？</label>
        <el-select id="factory-analysis-topic" v-model="family" data-testid="factory-chart-guide-topic" aria-label="分析主题">
          <el-option label="推荐组合（最多 4 张）" value="all" />
          <el-option v-for="item in familyChoices" :key="item.id" :label="item.label" :value="item.id" />
        </el-select>
      </div>
      <div class="guide-field">
        <label for="factory-analysis-subject">{{ mode === 'guide' ? '3. 分析对象' : '分析对象' }}</label>
        <el-select-v2 id="factory-analysis-subject" :model-value="subject || ''" :options="subjectChoices" placeholder="全部产品 / 全厂范围" filterable :loading="loading" data-testid="factory-chart-guide-subject" aria-label="分析产品" @update:model-value="emit('subject', $event || null)" />
      </div>
    </div>
    <template v-if="mode === 'guide'">
      <p class="guide-note">期间使用上方选择。产品目录来自当前数据源，结果按所选期间计算；查看分析不会自动保存选择。</p>
      <el-button type="primary" :disabled="loading || !recommendationIds.length" data-testid="factory-chart-guide-generate" @click="emit('analysis', recommendationIds)">查看分析{{ recommendationIds.length ? `（${recommendationIds.length} 张）` : '' }}</el-button>
      <p v-if="!recommendationIds.length" class="guide-note" role="status">当前范围没有满足数据条件的分析，请调整范围或查看下方条件说明。</p>
    </template>
    <div v-else class="selection-panel" data-testid="factory-chart-selection">
      <el-input v-model="search" clearable placeholder="搜索分析主题" aria-label="搜索分析主题" class="family-search" />
      <div v-for="group in searchedFamilies" :key="group.id" class="family-row" data-testid="factory-chart-family">
        <el-checkbox :model-value="selected(group)" @update:model-value="toggle(group, $event)">{{ group.label }}</el-checkbox>
        <span class="family-domain">{{ group.domain }}</span>
        <el-select :model-value="chosen(group)" :aria-label="`${group.label}的画法与单位`" size="small" @update:model-value="variant(group, $event)">
          <el-option v-for="item in group.items" :key="item.id" :value="item.id" :label="`${chartTypeLabel(item)} · ${item.unit}${group.items.some(other => other.id !== item.id && other.unit === item.unit && other.chart.chartType === item.chart.chartType) ? ` · ${item.title}` : ''}`" />
        </el-select>
      </div>
      <el-empty v-if="!searchedFamilies.length" description="此分类没有匹配的分析" :image-size="50" />
    </div>
  </section>
</template>

<style scoped>
.analysis-guide { padding: 20px; background: var(--el-bg-color, #fff); border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 10px; margin: 16px 0; }
.guide-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.guide-step { margin-top: 20px; }
.guide-step h3 { font-size: 14px; color: var(--el-text-color-primary); margin: 0 0 10px; }
.guide-step :deep(.el-radio-group) { display: flex; flex-wrap: wrap; gap: 8px; }
.guide-step :deep(.el-radio-button__inner) { border: 1px solid var(--el-border-color); border-radius: 6px; box-shadow: none; white-space: normal; }
.guide-fields { display: flex; flex-wrap: wrap; gap: 16px; }
.guide-field { flex: 1; min-width: 220px; max-width: 480px; }
.guide-field label { display: block; margin-bottom: 8px; font-weight: 600; font-size: 14px; }
.guide-note, .family-domain { font-size: 12px; color: var(--el-text-color-secondary, #7a8599); line-height: 1.7; }
.selection-panel { margin-top: 16px; }
.family-search { max-width: 320px; margin-bottom: 8px; }
.family-row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.family-row :deep(.el-checkbox) { flex: 1; min-width: 0; height: auto; }
.family-row :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; }
.family-row .el-select { width: 210px; }
@media (max-width: 650px) { .analysis-guide { padding: 14px; } .family-row { flex-wrap: wrap; } .family-row .el-select { width: 100%; } .guide-field { min-width: 100%; } }
</style>
