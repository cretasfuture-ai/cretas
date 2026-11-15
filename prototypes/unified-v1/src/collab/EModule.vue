<script setup lang="ts">
// E 线模块根：质量检验（分阶段）、检验标准、协同任务。两个工作区共用，数据按工作区 + 会话隔离。
// 页面里不画第二套侧栏，也不自带 AI 面板：AI 走统一 AiFloat（D 维护），本模块经 e-ai-adapter.ts 提供 inspection / task 对象与事实。
import {computed,defineAsyncComponent,onBeforeUnmount,onMounted,ref} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {E,reload,loadObjects,setActor,tag,syncContext} from './e-store'
import './e.css'
const QualityInspections=defineAsyncComponent(()=>import('../quality/QualityInspections.vue'))
const QualityStandards=defineAsyncComponent(()=>import('../quality/QualityStandards.vue'))
const QualityLoop=defineAsyncComponent(()=>import('../quality/QualityLoop.vue'))
const CollabTasks=defineAsyncComponent(()=>import('./CollabTasks.vue'))
const QualityDefects=defineAsyncComponent(()=>import('../quality/QualityDefects.vue'))
const path=ref('')
function sync(){path.value=location.hash.replace(/^#/,'').split('?')[0]||'/quality/inspections';if(syncContext()){reload().then(loadObjects)}}
const LOOP:Record<string,[string,string]>={'/quality/ccp':['CCP 监控记录','记录工序关键控制点的实测值，偏离由代码按关键限值判定。'],'/quality/returns':['质检退回单','检验不合格后的退回记录与处理结果。'],'/quality/disposals':['废弃处理','报废、销毁、退供应商、回炉返工的记录。'],'/quality/recalls':['批次召回','按 C 的入库批次发起召回，进行中的召回会扣留该批次。'],'/quality/param-deviation':['工艺参数偏离','缺口说明：黑湖没有工艺参数数据。'],'/quality/cold-chain':['冷链超温批次','缺口说明：黑湖没有温度采集数据。'],'/quality/vision-training':['视觉质检训练','缺口说明：属于 AI 能力配置，下沉到设置。'],'/quality/label-qc':['包装标签拍检','按七项标签内容记录拍检结果。']}
// 缺口页底部只写本页具体缺什么（不用通用的数量 / 判定说明）
const GAPNOTE:Record<string,string>={'/quality/param-deviation':'本页缺：工序工艺设定值与设备或人工采集的实测值（黑湖报工只有数量和重量）。数据来源定下之前这里不会有记录。','/quality/cold-chain':'本页缺：冷库、冷藏车的逐时温度读数，以及批次出入库时间对应关系（黑湖没有温度数据）。采集来源定下之前这里不会有记录。','/quality/vision-training':'本页缺：训练选样、标注、模型版本等 AI 能力配置（不是质量业务单据），由设置里的 AI 能力配置承接，这里不做训练。'}
const view=computed(()=>LOOP[path.value]?'loop':path.value.startsWith('/quality/standards')?'standards':path.value.startsWith('/quality/defects')?'defects':path.value.startsWith('/collab')?'tasks':'inspections')
const TITLES={inspections:['质量管理','质量检验','按来料、生产、入库、发货、其他五个阶段记录检验，判定由标准上下限计算。'],standards:['质量管理','检验标准','标准跟着阶段走：来料与入库针对原材料，生产针对工单，发货针对成品。'],tasks:['个人 OA','协同任务','把异常交给具体岗位，带截止、状态、评论和关联单据，跟进到关闭。'],defects:['质量管理','不良分析','不良品项字典由质量人员维护；工序不良分布只用报工记录里的良品数与不良数，代码计算。']} as const
const title=computed(()=>view.value==='loop'?['质量管理',...LOOP[path.value]]:TITLES[view.value])
const wsName=computed(()=>E.ws==='lsm'?'六膳门':'味美')
// 顶部来源说明按黑湖快照的实际条数写（BATCH3-AUDIT 12），不写死
const lsmText=computed(()=>{const c=E.lake?.counts;if(!E.lake?.available||!c)return '黑湖检验数据未读取；检验标准、Cretas 检验单与协同任务在本机补充，不回写黑湖';const n=(k:string)=>c[k]??'未返回';return '黑湖检验单 '+n('inspection_order')+' 张（只读）；黑湖检验规范 '+n('inspection_spec')+' 条、检验项 '+n('inspection_item')+' 条、检验结果 '+n('inspection_result')+' 条、协同任务 '+n('collaborative_task')+' 条。检验标准、Cretas 检验单与协同任务在本机补充，不回写黑湖'})
const ACTORS=['质量经理','品控','生产主管','采购','仓库','销售','其他']
onMounted(async()=>{sync();window.addEventListener('hashchange',sync);await reload();await loadObjects()})
onBeforeUnmount(()=>window.removeEventListener('hashchange',sync))
</script>
<template>
<div class="e-module" :class="{'drawer-open':E.drawerOpen}">
<div class="page-heading">
  <div><p>{{wsName}} · {{title[0]}}</p><h1>{{title[1]}} <i class="help" tabindex="0" :title="title[2]">?</i></h1></div>
  <div class="actions">
    <label class="e-actor">我是<select aria-label="当前岗位" :value="E.actor" @change="setActor(($event.target as HTMLSelectElement).value)"><option value="">未选择</option><option v-for="a in ACTORS" :key="a" :value="a">{{a}}</option></select><i class="help" tabindex="0" title="原型没有统一的登录人名单。这里只决定「我负责的 / 我发起的」和留痕署名，不是权限。">?</i></label>
    <UButton color="neutral" variant="outline" size="sm" :loading="E.loading" @click="reload()">重新读取</UButton>
  </div>
</div>
<div class="source-strip e-strip">
  <span v-html="tag('cretas')"/>
  <span>{{wsName}}工作区 · {{E.ws==='lsm'?lsmText:'味美账号当前没有检验记录，记录保存在统一入口独立会话，不写账号工作区'}}</span>
  <span v-if="E.session" class="e-session">练习会话 {{E.session}}{{E.sessionFrom?'（取自'+E.sessionFrom+'）':''}}</span>
  <span v-if="E.objectsSource" class="revision">对象来源：{{E.objectsSource}}</span>
</div>
<p v-if="E.error" role="alert" class="error">{{E.error}}</p>
<p v-if="E.notice" role="status" class="notice">{{E.notice}} <button aria-label="关闭提示" @click="E.notice=''">×</button></p>
<p v-if="E.objectsNote" class="source-gap">{{E.objectsNote}}</p>
<template v-if="E.state">
  <QualityInspections v-if="view==='inspections'"/>
  <QualityStandards v-else-if="view==='standards'"/>
  <QualityDefects v-else-if="view==='defects'"/>
  <QualityLoop v-else-if="view==='loop'"/>
  <CollabTasks v-else/>
</template>
<p v-else-if="E.loading" class="empty">正在读取…</p>
<p v-if="GAPNOTE[path]" class="footnote">{{GAPNOTE[path]}}</p>
<p v-else class="footnote">数量两位小数并带单位；缺失显示「未填写」不显示 0。判定、超期、状态由代码计算；解读只给解释与核对方向，不改判定、不替人执行。</p>
<!-- AI 只有一个悬浮窗实现（DECISIONS A3，由 D 维护 AiFloat）；本模块只通过 e-ai-adapter.ts 交出对象与事实，不自带面板 -->
</div>
</template>
