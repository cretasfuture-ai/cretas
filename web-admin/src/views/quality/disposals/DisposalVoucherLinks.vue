<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { useRouter } from 'vue-router';
import { get } from '@/api/request';
import { VOUCHER_STATUS_LABEL, type Voucher } from '@/api/voucher';
import { formatAmount } from '@/utils/tableFormatters';

const props=defineProps<{ factoryId: string; disposalId: string }>();
const router=useRouter();
const vouchers=ref<Voucher[]>([]);
const loading=ref(false);
const error=ref('');
let sequence=0;
let active=true;

function label(v: Voucher): string {
  return v.sourceBusinessType==='DISPOSAL_RECORD' ? '原始凭证'
    : v.sourceBusinessType==='VOUCHER_REVERSAL' ? '红字冲销' : '成本修订';
}

async function load() {
  const request=++sequence, factory=props.factoryId, record=props.disposalId;
  const current=()=>active && request===sequence && factory===props.factoryId && record===props.disposalId;
  vouchers.value=[];error.value='';loading.value=true;
  try {
    if (!factory || !/^\d+$/.test(record)) throw new Error('缺少有效的工厂或报废记录号');
    const response=await get<Voucher[]>(`/${encodeURIComponent(factory)}/disposal-records/${encodeURIComponent(record)}/vouchers`);
    if (!current()) return;
    if (!response.success || !Array.isArray(response.data)) throw new Error(response.message || '关联凭证读取失败');
    const rows=response.data;
    const sourceIds=new Set(rows.filter(v=>v.sourceBusinessType==='DISPOSAL_RECORD' && v.sourceBusinessId===record
      || v.sourceBusinessType==='DISPOSAL_RECORD_REVISION' && v.sourceBusinessId.startsWith(record+'#')).map(v=>v.id));
    for (const row of rows) {
      if (row.factoryId!==factory || !row.id || (!sourceIds.has(row.id)
        && !(row.sourceBusinessType==='VOUCHER_REVERSAL' && sourceIds.has(row.originalVoucherId || '')
          && row.sourceBusinessId===row.originalVoucherId))) throw new Error('关联凭证与当前工厂或报废记录不一致');
    }
    vouchers.value=rows;
  } catch (e) {
    if (current()) error.value=e instanceof Error ? e.message : '关联凭证读取失败';
  } finally { if (current()) loading.value=false; }
}

function open(v: Voucher) {
  if (!active || !vouchers.value.includes(v) || v.factoryId!==props.factoryId) return;
  void router.push(`/finance/voucher/${encodeURIComponent(v.id)}`);
}

watch(()=>[props.factoryId,props.disposalId],()=>{void load();},{immediate:true});
onDeactivated(()=>{active=false;++sequence;vouchers.value=[];loading.value=false;});
onActivated(()=>{if(!active){active=true;void load();}});
onBeforeUnmount(()=>{active=false;++sequence;});
</script>

<template>
  <section aria-label="对应财务凭证" v-loading="loading">
    <h3>对应财务凭证</h3>
    <p v-if="vouchers.some(v=>v.sourceBusinessType==='VOUCHER_REVERSAL')">原凭证与红字相抵；已过账的成本修订凭证记录重新核定的报废损失。</p>
    <p v-else>已过账凭证记录核定的报废损失。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon>
      <el-button link type="primary" @click="load">重新读取凭证</el-button>
    </el-alert>
    <p v-else-if="!loading && !vouchers.length">尚无对应凭证，请核对审批和过账情况。</p>
    <ul v-else>
      <li v-for="v in vouchers" :key="v.id">
        {{ label(v) }} · {{ VOUCHER_STATUS_LABEL[v.status]?.text || v.status }} · {{ formatAmount(v.totalDebit) }}
        <el-button link type="primary" @click="open(v)">{{ v.voucherNumber }}</el-button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
section { margin-top:16px; }
h3 { margin:0 0 8px; font-size:14px; }
p { color:var(--el-text-color-secondary); font-size:12px; }
ul { padding-left:16px; margin-bottom:0; }
li { margin-top:8px; }
</style>
