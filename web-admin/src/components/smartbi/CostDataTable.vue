<script setup lang="ts" generic="T extends object">
import {computed,reactive,ref,watch} from 'vue';
import {Filter} from '@element-plus/icons-vue';
import {filterActive,queryCostTable,tableValue,type CostTableColumn,type CostTableFilter,type CostTableSort} from '@/utils/costTableQuery';
const props=withDefaults(defineProps<{rows:readonly T[];columns:CostTableColumn<T>[];rowKey?:string;paginate?:boolean;missingLabel?:string}>(),{paginate:false,missingLabel:'未提供'});
const emit=defineEmits<{(event:'row-click',row:T):void}>();
const filters=reactive<Record<string,CostTableFilter>>({});
const sort=ref<CostTableSort>({}),page=ref(1),pageSize=ref(20);
const result=computed(()=>queryCostTable(props.rows,props.columns,filters,sort.value));
const visible=computed(()=>props.paginate?result.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value):result.value);
const active=computed(()=>Object.values(filters).some(filterActive));
watch(()=>props.columns,columns=>{for(const key of Object.keys(filters))if(!columns.some(c=>c.key===key))delete filters[key];for(const c of columns)if(!filters[c.key])filters[c.key]={};},{immediate:true});
watch(()=>props.rows,()=>{page.value=1;});
watch(filters,()=>{page.value=1;},{deep:true});
watch([sort,pageSize],()=>{page.value=1;});
watch(()=>result.value.length,length=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(length/pageSize.value)));});
function filter(key:string){return filters[key]??(filters[key]={});}
function choices(column:CostTableColumn<T>){return [...new Set(props.rows.map(row=>tableValue(row,column)).filter(v=>v!=null&&v!=='').map(String))].sort((a,b)=>a.localeCompare(b,'zh-CN'));}
function display(row:T,column:CostTableColumn<T>){const value=tableValue(row,column);if(value==null||value===''||typeof value==='number'&&!Number.isFinite(value))return props.missingLabel;return typeof value==='number'?value.toLocaleString('zh-CN',{minimumFractionDigits:column.count?0:2,maximumFractionDigits:column.count?0:2})+(column.suffix||''):String(value);}
function resetFilters(){for(const key of Object.keys(filters))filters[key]={};}
</script>
<template>
  <div class="cost-data-table">
    <el-table :data="visible" :row-key="rowKey" stripe @row-click="emit('row-click',$event)" @sort-change="sort={prop:$event.prop,order:$event.order}">
      <el-table-column v-if="$slots.expand" type="expand"><template #default="{row}"><slot v-if="row&&rows.includes(row)" name="expand" :row="row"/></template></el-table-column>
      <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :width="column.width" :min-width="column.minWidth||110" :sortable="column.sortable===false?false:'custom'">
        <template #header><span>{{ column.label }}</span><el-popover v-if="column.filterable!==false" trigger="click" :width="240" placement="bottom-start">
          <template #reference><button type="button" :aria-label="`筛选${column.label}`" :class="['filter-trigger',{active:filterActive(filters[column.key])}]" @click.stop><el-icon><Filter/></el-icon></button></template>
          <div class="column-filter"><strong>{{ column.label }}</strong>
            <template v-if="column.type==='number'||column.type==='date'"><label>最低 / 开始<input v-model="filter(column.key).min" :aria-label="`最低${column.label}`" :type="column.type==='date'?'date':'number'" step="any" placeholder="不限"></label><label>最高 / 结束<input v-model="filter(column.key).max" :aria-label="`最高${column.label}`" :type="column.type==='date'?'date':'number'" step="any" placeholder="不限"></label></template>
            <label v-else-if="column.type==='select'">选择值<select v-model="filter(column.key).selected" :aria-label="`选择${column.label}`"><option value="">全部</option><option v-for="value in choices(column)" :key="value" :value="value">{{ value }}</option></select></label>
            <label v-else>包含文字<input v-model="filter(column.key).text" :aria-label="`查找${column.label}`" placeholder="输入关键词"></label>
            <label><input v-model="filter(column.key).missingOnly" type="checkbox">仅看未提供</label>
            <button type="button" @click="filters[column.key]={}">清除此列筛选</button>
          </div>
        </el-popover></template>
        <template #default="{row}"><slot :name="column.key" :row="row">{{ display(row,column) }}</slot></template>
      </el-table-column>
    </el-table>
    <div v-if="active" class="table-tools"><span>筛选后 {{ result.length }} / 共 {{ rows.length }} 条 · 仅筛明细，原分析范围不变</span><button type="button" @click="resetFilters">清空表头筛选</button></div>
    <el-pagination v-if="paginate" v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[20,50,100]" :total="result.length" layout="total, sizes, prev, pager, next"/>
  </div>
</template>
<style scoped>
.filter-trigger{border:0;background:transparent;cursor:pointer;color:#7a8599;padding:3px 5px;vertical-align:middle}.filter-trigger.active{color:#1b65a8;background:#eaf2fa;border-radius:4px}.column-filter{display:grid;gap:12px;color:#1a2332;font-size:13px}.column-filter label{display:grid;gap:5px}.column-filter label:has(input[type=checkbox]){display:flex;align-items:center;gap:6px}.column-filter input:not([type=checkbox]),.column-filter select{width:100%;padding:7px;border:1px solid #dce3ec;border-radius:5px}.column-filter button,.table-tools button{border:0;background:transparent;color:#1b65a8;cursor:pointer;text-align:left;font:inherit}.table-tools{display:flex;gap:16px;font-size:12px;color:#7a8599;margin:10px 0}.el-pagination{margin-top:16px;overflow:auto}
</style>
