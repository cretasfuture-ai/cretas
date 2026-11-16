<script setup lang="ts">
/**
 * 工作记录与额度（P8，原型 /crew/ledger，R26/R29）。四种结果分开显示：
 * 已交办（等人确认，不扣）/ 已办成（确认执行且回读核实，扣 1）/ 没办成（不办，不扣）/ 结果不明（不扣，要人去核对）。
 * 额度按北京时间真实日期计数（服务端 resolvedDate），⛔ 不按快照日。额度与件数是全厂合计（服务端库内聚合）；明细只显示我有读权限的模块的事。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { crewApi, STATUS_TONE, type CrewLedger } from '@/api/digitalCrew';

const auth = useAuthStore();
const data = ref<CrewLedger | null>(null);
// reka-ui 不允许空字符串作选项值，「全部」用哨兵值，发请求时再映射回「不传参数」
const ALL_MEMBERS = '__all__';
const member = ref(ALL_MEMBERS);
const error = ref('');
async function load() {
  if (!auth.factoryId) return;
  error.value = '';
  try { data.value = await crewApi.ledger(auth.factoryId, member.value === ALL_MEMBERS ? undefined : member.value); }
  catch (e) { data.value = null; error.value = e instanceof Error ? e.message : '读取失败'; }
}
onMounted(load);
watch([() => auth.factoryId, member], load);
const memberItems = computed(() => [{ label: '全部数字员工', value: ALL_MEMBERS },
  ...(data.value?.perMember || []).map((m) => ({ label: m.memberName, value: m.memberKey }))]);
const t = (s?: string | null) => (s ? s.replace('T', ' ').slice(0, 16) : '');
</script>

<template>
  <div class="crew-ledger" data-testid="crew-ledger">
    <header class="head">
      <div><h1>工作记录与额度</h1>
        <p class="why">额度口径：每件「已办成」的事 1 个额度（确认执行后回读核实才算）；已交办、没办成、结果不明都不扣。按真实日期计数。额度和件数是全厂合计，所有人看到同一个数；下面的明细只列你有读权限的模块的事，最多最近 300 条。「办理中」超过 15 分钟没结果的，打开本页时会按回读凭据自动定为已办成 / 已交办 / 结果不明。</p></div>
      <UButton to="/crew" variant="ghost" color="neutral">回数字班组</UButton>
    </header>
    <UAlert v-if="error" color="error" variant="soft" title="工作记录没读到" :description="`${error}。不显示数量，也不推算。`">
      <template #actions><UButton color="error" variant="outline" @click="load">再试一次</UButton></template>
    </UAlert>
    <template v-if="data">
      <div class="tiles">
        <div><span>今天消耗额度（{{ data.today }}）</span><b data-testid="crew-quota-today">{{ data.quotaToday }}</b></div>
        <div><span>本月消耗额度</span><b data-testid="crew-quota-month">{{ data.quotaThisMonth }}</b></div>
        <div><span>累计消耗额度</span><b data-testid="crew-quota-total">{{ data.quotaTotal }}</b></div>
        <div><span>已办成</span><b data-testid="crew-done">{{ data.done }}</b></div>
        <div><span>已交办（等人确认，不扣）</span><b data-testid="crew-delegated-count">{{ data.delegated }}</b></div>
      </div>
      <section class="panel">
        <h2>按数字员工</h2>
        <table class="list"><thead><tr><th>数字员工</th><th class="n">已交办</th><th class="n">办理中</th><th class="n">已办成</th><th class="n">没办成</th><th class="n">结果不明</th><th class="n">额度</th></tr></thead>
          <tbody><tr v-for="p in data.perMember" :key="p.memberKey"><td>{{ p.memberName }}</td><td class="n">{{ p.delegated }}</td><td class="n">{{ p.confirming }}</td><td class="n">{{ p.done }}</td><td class="n">{{ p.notDone }}</td><td class="n">{{ p.outcomeUnknown }}</td><td class="n">{{ p.quota }}</td></tr></tbody></table>
      </section>
      <section class="panel">
        <div class="bar"><h2>工作记录 <small>{{ data.records.length }} 条</small></h2>
          <USelect v-model="member" :items="memberItems" aria-label="按数字员工筛选" class="sel" /></div>
        <p v-if="!data.records.length" class="muted">还没有记录。对数字员工说要办的事，它会记「已交办」；有权限的人确认执行、回读核实后才记「已办成」。</p>
        <table v-else class="list" data-testid="crew-ledger-table"><thead><tr><th>交办时间</th><th>数字员工</th><th>事项</th><th>结果</th><th class="n">额度</th><th>依据（回读）</th><th>交办人 / 确认人</th></tr></thead>
          <tbody><tr v-for="r in data.records" :key="r.id">
            <td>{{ t(r.createdAt) }}</td><td>{{ r.memberName }}</td><td>{{ r.title }}</td>
            <td><UBadge :label="r.statusLabel" :color="STATUS_TONE[r.status]" variant="soft" /><div v-if="r.resultNote" class="muted">{{ r.resultNote }}</div></td>
            <td class="n">{{ r.quotaCost }}</td><td>{{ r.evidence || (r.status === 'DONE' ? '' : '—') }}<div v-if="r.resolvedDate" class="muted">{{ r.resolvedDate }}</div></td>
            <td>{{ r.createdByName }}<div v-if="r.confirmedByName" class="muted">{{ r.confirmedByName }} {{ t(r.confirmedAt) }}</div></td>
          </tr></tbody></table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.crew-ledger { padding: 16px 0; max-width: 1200px; display: grid; gap: 16px; }
.head { display: flex; justify-content: space-between; gap: 16px; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
h2 { margin: 0 0 8px; font-size: var(--cs-type-section); color: var(--cs-text); }
.why, .muted, small { color: var(--cs-muted); font-size: var(--cs-type-caption); margin: 4px 0 0; }
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
.tiles div { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md, 10px); background: var(--cs-surface); padding: 12px; display: grid; gap: 4px; }
.tiles span { color: var(--cs-muted); font-size: var(--cs-type-caption); }
.tiles b { font-size: 24px; color: var(--cs-text); }
.panel { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md, 10px); background: var(--cs-surface); padding: 12px 14px; }
.bar { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.sel { min-width: 180px; }
.list { width: 100%; border-collapse: collapse; font-size: var(--cs-type-body); }
.list th, .list td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--cs-border); vertical-align: top; }
.list th { color: var(--cs-muted); font-weight: 500; }
.n { text-align: right !important; font-variant-numeric: tabular-nums; }
</style>
