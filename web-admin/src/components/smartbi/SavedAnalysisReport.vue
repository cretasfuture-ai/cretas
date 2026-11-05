<script setup lang="ts">
import type { BlacklakePeriodJob, SectionInsightReport } from '@/api/smartbi/blacklake';
import { localizeBlacklakeText } from '@/views/smart-bi/blacklakePresentation';
import { computed, ref, watch } from 'vue';

const props = defineProps<{ report: BlacklakePeriodJob | null }>();
const selectedId = ref('');
const supplementOpen=ref(false),supplementPage=ref(1),selectedSupplement=ref('');
const coverage=computed(()=>props.report?.result?.ai.analysisFactCoverage);
const omitted=computed(()=>props.report?.result?.ai.omittedAnalysisFacts||0);

const sections = computed(() => props.report?.result?.sectionInsights || []);
const preferred = ['coverage', 'product_cost', 'production', 'inventory', 'quality', 'materials', 'labor'];
const activeSection = computed(() => sections.value.find(section => section.sectionId === selectedId.value) || sections.value.find(section => preferred.includes(section.sectionId)) || sections.value[0] || null);
const supplements=computed(()=>activeSection.value?.supplementalReviews||[]);
const supplementRows=computed(()=>supplements.value.slice((supplementPage.value-1)*8,supplementPage.value*8));
watch(activeSection,()=>{supplementOpen.value=false;supplementPage.value=1;selectedSupplement.value='';});
watch(sections, value => { if (!value.some(section => section.sectionId === selectedId.value)) selectedId.value = ''; }, { immediate: true });

const periodDate = (value: string | null | undefined) => value ? `${value.slice(5, 7)}-${value.slice(8, 10)}` : '未提供';
const clean = (value: string) => localizeBlacklakeText(value).replace(/\s{2,}/g, ' ').trim();
const isBoilerplate = (value: string) => /北京时间含首尾|完整生命周期成本|不是期间实际发生|当时已知历史关账|资料采集早于周期结束|数据版本|快照|这段时间/.test(value);
const shortNames:Record<string,string>={coverage:'待补资料',material:'原料投入',labor:'人工成本',finished:'成品产出',product_cost:'产品成本',production:'生产进度',inventory:'库存核对'};
const sectionName = (section: SectionInsightReport) => shortNames[section.sectionId] || section.title;
const category = (section: SectionInsightReport) => section.sectionId.includes('cost') || section.sectionId.includes('material') ? '成本' : section.sectionId.includes('inventory') ? '库存' : section.sectionId.includes('production') || section.sectionId.includes('quality') ? '生产' : '资料';
const roleSuggestions:Record<string,string>={coverage:'生产、仓库和财务负责人',material:'仓库负责人、生产领料经办人',labor:'生产负责人、财务',finished:'生产负责人、成品仓管',product_cost:'生产负责人、财务',production:'生产主管、计划负责人',inventory:'仓库负责人、财务'};
const ownerLabel=computed(()=>activeSection.value?.localAnalysis?.owner||roleSuggestions[activeSection.value?.sectionId||'']||'工厂管理员');
const plainStatistic=(value:string)=>value.includes('缺什么')?value.split(/[；;]\s*找谁/)[0]!.replace(/缺什么[—\-：:]+/,'缺少').replace(/[。；]+$/,'。'):value;
const statisticText = (section: SectionInsightReport) => (section.localAnalysis?.statistics || []).filter(item=>!isBoilerplate(item.text)).map(item => plainStatistic(clean(item.text))).filter(Boolean).slice(0, 2);
const fallbackEvidence = (section: SectionInsightReport) => [...section.observations, ...section.calculation].filter(item=>!isBoilerplate(item)).map(clean).filter(Boolean);
const metrics = computed(() => {
  const summary = props.report?.result?.summary || '';
  const entries = [
    ['纳入分析的工单', summary.match(/(\d[\d,]*)\s*张工单/)?.[1]],
    ['可核对材料与人工', summary.match(/(\d[\d,]*)\s*张资料具备/)?.[1]],
    ['暂不能核对成本', summary.match(/(\d[\d,]*)\s*张仍需核对/)?.[1]],
  ].filter(([, value]) => value).map(([label, value]) => ({ label, value: String(value) }));
  return entries;
});
const conclusion=computed(()=>{
  const section=activeSection.value;
  if(section?.sectionId==='coverage'&&metrics.value.length===3){
    return `这 ${metrics.value[0]!.value} 张工单里，${metrics.value[1]!.value} 张可核对材料和人工成本；其余 ${metrics.value[2]!.value} 张，先确认完工进度或补齐成本资料。`;
  }
  return clean(section?.localAnalysis?.whatItMeans||section?.summary||'本节暂未形成可读结论。');
});
</script>

<template>
  <section class="saved-report" aria-label="已保存分析报告">
    <div v-if="!props.report" class="empty">暂无已保存报告。</div>
    <template v-else>
      <header class="report-header"><div><p class="eyebrow">已保存分析</p><h2>本次数据概况</h2><p class="period">{{ periodDate(props.report.period.startDate) }} 至 {{ periodDate(props.report.period.endDate) }}</p></div><span class="state">{{ props.report.status === 'ready' ? '已生成' : props.report.status }}</span></header>
      <div class="metrics" v-if="metrics.length"><div v-for="metric in metrics" :key="metric.label" class="metric"><strong>{{ metric.value }}</strong><span>{{ metric.label }}</span></div></div>
      <p v-if="coverage" class="supplement-status">已补充核对 {{coverage.additionalReviewed}} 项分析{{coverage.remaining?`，另有 ${coverage.remaining} 项待补`:'；单据缺项仍需按建议处理'}}。</p>
      <p v-else-if="props.report.result?.ai.status==='partial'&&omitted" class="supplement-status">已提供的 AI 解读通过核验，另有 {{omitted}} 项分析尚未覆盖。</p>
      <p v-if="!metrics.length" class="summary-fallback">{{ clean(props.report.result?.summary || '本次报告尚未提供可读的数据概况。').slice(0, 180) }}</p>
      <nav v-if="sections.length" class="section-nav" aria-label="报告内容"><button v-for="section in sections" :key="section.sectionId" type="button" :class="{active: activeSection?.sectionId === section.sectionId}" @click="selectedId = section.sectionId">{{ sectionName(section) }}</button></nav>
      <article v-if="activeSection" class="section-card">
        <div class="card-title"><span class="category">{{category(activeSection)}}</span><h3>{{sectionName(activeSection)}}</h3></div>
        <p class="conclusion">{{conclusion}}</p>
        <div class="action-grid">
          <div><b>哪里要核对</b><p v-for="item in (statisticText(activeSection).length?statisticText(activeSection):fallbackEvidence(activeSection).slice(0,2))" :key="item">{{item}}</p><p v-if="!statisticText(activeSection).length&&!fallbackEvidence(activeSection).length" class="muted">暂无明确数字依据。</p></div>
          <div><b>找谁处理</b><p>{{ownerLabel}}</p></div>
          <div><b>建议先做</b><p>{{clean(activeSection.localAnalysis?.nextStep||activeSection.nextSteps?.[0]||'先补齐本节所需记录。')}}</p></div>
        </div>
        <details class="evidence"><summary>查看数字与依据</summary><p v-for="item in fallbackEvidence(activeSection)" :key="item">{{item}}</p><p v-for="item in activeSection.evidenceSources||[]" :key="item.label"><b>{{item.label}}</b>：{{clean(item.description)}}</p><p v-for="item in activeSection.localAnalysis?.hypotheses||activeSection.hypotheses||[]" :key="item" class="hypothesis">可能原因，尚未确认：{{clean(item)}}</p><p v-if="!fallbackEvidence(activeSection).length&&!activeSection.hypotheses?.length" class="muted">暂无更多依据。</p></details>
        <details v-if="supplements.length" class="supplement-list" :open="supplementOpen" @toggle="supplementOpen=($event.target as HTMLDetailsElement).open">
          <summary>补充核对（{{supplements.length}} 项）</summary>
          <template v-if="supplementOpen">
            <div v-for="review in supplementRows" :key="review.factId" class="supplement-row">
              <button type="button" :aria-expanded="selectedSupplement===review.factId" @click="selectedSupplement=selectedSupplement===review.factId?'':review.factId">{{clean(review.statistics[0]?.text||'查看这项统计').slice(0,160)}}</button>
              <div v-if="selectedSupplement===review.factId" class="supplement-detail">
                <p>{{clean(review.whatItMeans)}}</p><p><b>找谁处理：</b>{{review.owner}}</p><p><b>下一步：</b>{{clean(review.nextStep)}}</p><p class="muted">{{clean(review.cannotConclude)}}</p>
              </div>
            </div>
            <div v-if="supplements.length>8" class="supplement-pages"><button :disabled="supplementPage===1" @click="supplementPage--;selectedSupplement=''">上一页</button><span>{{supplementPage}} / {{Math.ceil(supplements.length/8)}}</span><button :disabled="supplementPage*8>=supplements.length" @click="supplementPage++;selectedSupplement=''">下一页</button></div>
          </template>
        </details>
      </article>
      <p v-else class="empty">报告暂未提供分节内容。</p>
    </template>
  </section>
</template>

<style scoped>
.supplement-status{font-size:13px;color:#526276;line-height:1.6}.supplement-list{margin-top:16px;font-size:13px}.supplement-list summary{cursor:pointer;color:#1b65a8}.supplement-row{padding:10px 0;border-bottom:1px solid #edf1f5}.supplement-row>button{border:0;background:transparent;color:#245f88;text-align:left;line-height:1.65;cursor:pointer;width:100%}.supplement-detail{padding:10px 14px;background:#f8fafc;margin-top:8px;border-radius:8px;line-height:1.65}.supplement-pages{display:flex;justify-content:flex-end;align-items:center;gap:12px;margin-top:12px}.supplement-pages button{padding:5px 10px;border:1px solid #dce3ec;background:#fff;border-radius:6px;cursor:pointer}.supplement-pages button:disabled{opacity:.5;cursor:default}
.saved-report{padding:22px;border:1px solid var(--c-line,#e5eaf0);border-radius:12px;background:var(--c-surface,#fff);color:var(--c-ink,#1a2332)}.report-header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.eyebrow{margin:0;color:#738196;font-size:12px}.report-header h2{margin:4px 0;font-size:20px}.period{margin:0;color:#66758a;font-size:13px}.state,.category{display:inline-flex;border-radius:999px;padding:4px 9px;background:#e7f5ed;color:#176b45;font-size:12px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:20px 0}.metric{padding:14px;border:1px solid #e8edf2;border-radius:8px;background:#f8fafc}.metric strong{display:block;font-size:21px;color:#1d4e7a}.metric span{font-size:12px;color:#66758a}.summary-fallback{margin:18px 0;padding:12px;background:#f8fafc;border-radius:8px;font-size:13px;line-height:1.7}.section-nav{display:flex;gap:8px;flex-wrap:wrap;margin:20px 0 14px}.section-nav button{border:1px solid #dce3ec;border-radius:6px;padding:7px 12px;background:#fff;color:#526276;cursor:pointer}.section-nav button.active{border-color:#3478ad;background:#edf5fb;color:#145681}.section-card{border-top:3px solid #3478ad;padding-top:14px}.card-title{display:flex;align-items:center;gap:9px}.card-title h3{margin:0;font-size:18px}.category{background:#edf5fb;color:#245f88}.conclusion{font-size:15px;line-height:1.75;color:#26384d}.action-grid{display:grid;grid-template-columns:1.4fr 1fr 1.4fr;gap:12px}.action-grid>div{padding:12px;border:1px solid #edf1f5;border-radius:8px}.action-grid b{font-size:12px;color:#66758a}.action-grid p{margin:6px 0;font-size:13px;line-height:1.65}.muted{color:#8793a2}.evidence{margin-top:16px;font-size:13px}.evidence summary{cursor:pointer;color:#1b65a8}.evidence p{line-height:1.65;color:#526276}.hypothesis{color:#86570e!important}.empty{text-align:center;padding:34px;color:#738196;font-size:13px}@media(max-width:720px){.metrics,.action-grid{grid-template-columns:1fr}.report-header{flex-direction:column}}
</style>
