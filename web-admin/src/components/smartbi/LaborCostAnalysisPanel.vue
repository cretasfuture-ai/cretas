<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { analyzeLaborCosts, type LaborCostInput } from '@/utils/laborCostAnalysis';
const props=defineProps<{input:LaborCostInput;scopeLabel:string}>();
const model=computed(()=>analyzeLaborCosts(props.input));
const mode=ref('recorded');
watch(()=>props.input,()=>{mode.value='recorded';});
const fmt=(value:number|null|undefined)=>value==null?'待补资料':value.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
</script>
<template>
  <section class="labor-analysis" aria-label="人工与工序成本分析">
    <h3>人工花在哪里</h3><p class="scope">{{ scopeLabel }}</p>
    <div class="labor-metrics"><div><span>已记录人工费</span><strong>{{ fmt(model.total) }}</strong></div><div><span>每{{ input.unit||'单位成品' }}人工费</span><strong>{{ fmt(model.perUnit) }}</strong></div><div><span>人均人工费</span><strong>{{ fmt(model.perPerson) }}</strong></div><div><span>每人时费用</span><strong>{{ fmt(model.perPersonHour) }}</strong></div></div>
    <p v-if="model.total===0" class="warning">工资记录为零，请先确认是工资另记、漏记，还是确实不计薪。</p>
    <p v-if="!model.reconciled" class="warning">工序明细还没有与人工费合计对齐，先核对金额，再做分摊。</p>
    <div class="mode" v-if="model.canAllocate"><label>查看方式<select v-model="mode" aria-label="人工分摊查看方式"><option value="recorded">已记录的工序人工</option><option value="person_hours">按人时分摊测算</option></select></label></div>
    <p v-if="mode==='person_hours'" class="warning">按各工序人时分配同一笔人工费，仅供比较，不改变工资或账目。</p>
    <el-table :data="model.rows" row-key="id" stripe>
      <el-table-column prop="process" label="工序" min-width="145"/>
      <el-table-column :label="mode==='person_hours'?'测算分摊额':'已记录人工费'" min-width="115"><template #default="{row}">{{ fmt(mode==='person_hours'?row.allocated:row.amount) }}</template></el-table-column>
      <el-table-column label="人工占比" width="105"><template #default="{row}">{{ mode==='person_hours' ? (model.total ? fmt(row.allocated/model.total*100)+'%' : '待核对') : row.share==null?'待核对':fmt(row.share*100)+'%' }}</template></el-table-column>
      <el-table-column :label="`每${input.unit||'单位成品'}人工`" min-width="115"><template #default="{row}">{{ mode==='person_hours' ? fmt(input.output&&input.unit?row.allocated/input.output:null) : fmt(row.perUnit) }}</template></el-table-column>
      <el-table-column label="已报时长" min-width="110"><template #default="{row}">{{ fmt(row.reportedHours) }} 小时</template></el-table-column>
      <el-table-column label="有效人时" width="105"><template #default="{row}">{{ fmt(row.personHours) }}</template></el-table-column>
      <el-table-column v-if="mode==='recorded'&&model.rows.some(r=>r.segmentPerPerson!=null)" label="本时段人均费用" min-width="135"><template #default="{row}">{{ fmt(row.segmentPerPerson) }}</template></el-table-column>
    </el-table>
    <div v-if="model.needs.length" class="missing"><h4>还需要补什么</h4><article v-for="need in model.needs" :key="need.key"><strong>{{ need.missing }}</strong><p>找{{ need.owner }}：{{ need.action }}。</p><p class="scope">补齐后：{{ need.benefit }}。</p></article></div>
    <details class="explain"><summary>怎么算</summary><p>计时人工＝每段时长 × 该段人数 × 费率；计件人工沿用已核定的计件工资。同一笔工资只记一次。</p><p>人均人工费＝所选工单人工费 ÷ 去重后的参与人数；同一人做多道工序只算一个人。时段人均费用只针对该段，不代表整个范围的人均费用。</p><p>人时分摊＝所选工单人工费 × 工序人时 ÷ 总人时，尾差留在最后一个有工时的工序。已报时长不直接当作人时，资料不齐时保持待补。</p></details>
  </section>
</template>
<style scoped>
.labor-analysis{border:1px solid #edf2f7;border-radius:10px;padding:20px;background:#fff;color:#1a2332}.labor-analysis h3,.labor-analysis h4{margin:0 0 12px}.scope{font-size:12px;color:#7a8599}.labor-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin:18px 0}.labor-metrics span,.labor-metrics strong{display:block}.labor-metrics span{font-size:12px;color:#7a8599}.labor-metrics strong{font-size:20px;margin-top:6px}.missing{margin-top:20px}.missing article{border-top:1px solid #edf2f7;padding:12px 0}.missing p{margin:5px 0;line-height:1.7;font-size:13px}.warning{color:#925c16;font-size:13px;line-height:1.7}.mode{margin:14px 0;font-size:13px}.mode select{padding:6px 10px;margin-left:8px;border:1px solid #edf2f7;border-radius:6px}.explain{margin-top:16px;font-size:12px;color:#7a8599}.explain summary{cursor:pointer;color:#1b65a8}.explain p{line-height:1.8}@media(max-width:760px){.labor-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.labor-analysis{padding:14px}}
</style>
