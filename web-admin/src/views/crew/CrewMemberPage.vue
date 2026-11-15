<script setup lang="ts">
/**
 * 和一位数字员工自由对话（P8，F-crew-gaps D2）。界面复用 #1753 的 DigitalEmployeeConversation（已按 ai-chat-standard）。
 * 后端 /digital-crew/members/{key}/ask：走同一个意图识别（READ），按员工能力 + 登录人权限过滤；
 * 查询直接回答；写操作 ⛔ 不在这里执行，只记「已交办」进「待办 › 等我确认」，有权限的人预览后确认。
 */
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import DigitalEmployeeConversation from '@/components/workdesk/DigitalEmployeeConversation.vue';
import { crewApi, CREW_AVATARS, type CrewAsk, type CrewMember } from '@/api/digitalCrew';

const route = useRoute();
const auth = useAuthStore();
const key = computed(() => String(route.params.member || ''));
const member = ref<CrewMember | null>(null);
const loadError = ref('');
const draft = ref('');
const busy = ref(false);
const error = ref('');
const response = ref('');
const last = ref<CrewAsk | null>(null);
const sessionId = ref<string | null>(null);
let lastQuestion = '';

async function loadMember() {
  member.value = null; loadError.value = '';
  if (!auth.factoryId) return;
  try {
    const all = await crewApi.members(auth.factoryId);
    member.value = all.find((m) => m.key === key.value) || null;
    if (!member.value) loadError.value = '没有这位数字员工';
  } catch (e) { loadError.value = e instanceof Error ? e.message : '读取失败'; }
}
watch([key, () => auth.factoryId], () => { void loadMember(); sessionId.value = null; last.value = null; response.value = ''; }, { immediate: true });

const quick = computed(() => {
  const q: Record<string, string[]> = {
    planner: ['今天各产线的生产进度', '这周有哪些批次缺料'],
    keeper: ['哪些原料批次 7 天内到期', '今天有哪些待收货'],
    inspector: ['最近一周的质检不合格有哪些', '今天待检的批次'],
    clerk: ['这周待处理的销售订单', '最近的采购订单'],
    accountant: ['本月成本最高的产品', '这个月的毛利情况'],
  };
  return q[key.value] || [];
});

async function send(text: string) {
  lastQuestion = text; busy.value = true; error.value = '';
  try {
    const r = await crewApi.ask(auth.factoryId!, key.value, text, sessionId.value);
    last.value = r; sessionId.value = r.sessionId || sessionId.value;
    response.value = r.text || '（没有返回文字）';
  } catch (e) {
    error.value = `${e instanceof Error ? e.message : '没有完成'}，没有执行任何写操作`;
  } finally { busy.value = false; }
}
function reset() { sessionId.value = null; last.value = null; response.value = ''; error.value = ''; }
</script>

<template>
  <div class="crew-member" data-testid="crew-member-page">
    <nav class="crumbs"><RouterLink to="/crew">数字班组</RouterLink> › {{ member?.name || key }}</nav>
    <UAlert v-if="loadError" color="error" variant="soft" title="打不开这位数字员工" :description="loadError" />
    <UAlert v-else-if="member && !member.enabled" data-testid="crew-member-disabled" color="warning" variant="soft"
      :title="`本厂没有启用${member.name}`" description="请管理员到「设置 › 数字班组配置」启用后再来。">
      <template #actions><UButton to="/crew/settings" variant="outline" color="neutral">去数字班组配置</UButton></template>
    </UAlert>
    <div v-else-if="member" class="layout">
      <aside class="side">
        <img :src="CREW_AVATARS[member.key]" alt="" width="72" height="72">
        <h1>{{ member.name }}</h1>
        <p>{{ member.duty }}</p>
        <p class="muted">查询直接回答；要办的事整理成预览交给有权限的人确认，确认后才执行。</p>
        <UButton to="/todo?tab=confirm" variant="outline" color="neutral">等我确认</UButton>
      </aside>
      <DigitalEmployeeConversation
        v-model="draft" :busy="busy" :error="error" :response="response"
        :result-title="`和${member.shortName}说`" :employee-name="member.name" scope-label="数字班组"
        :scope-key="`${auth.factoryId}:${member.key}`" :quick-questions="quick"
        :response-status="last?.status || ''" :clarification-choices="last?.clarificationChoices || []"
        @send="send" @retry="send(lastQuestion)" @reset="reset"
      >
        <template #action>
          <UAlert v-if="last?.kind === 'DELEGATED' && last.item" data-testid="crew-delegated" color="info" variant="soft"
            :title="`已交办：${last.item.title}`" :description="last.item.previewSummary || ''">
            <template #actions><UButton to="/todo?tab=confirm" color="primary">去等我确认</UButton></template>
          </UAlert>
          <UAlert v-else-if="last?.kind === 'OUT_OF_SCOPE' && last.ownerKey" data-testid="crew-out-of-scope" color="neutral" variant="soft" title="这件事归别的数字员工管">
            <template #actions><UButton :to="`/crew/member/${last.ownerKey}`" variant="outline" color="neutral">去找它</UButton></template>
          </UAlert>
        </template>
      </DigitalEmployeeConversation>
    </div>
  </div>
</template>

<style scoped>
.crew-member { padding: 16px 0; max-width: 1200px; display: grid; gap: 12px; }
.crumbs { color: var(--cs-muted); font-size: var(--cs-type-caption); }
.layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 24px; align-items: start; }
.side { display: grid; gap: 8px; justify-items: start; }
.side img { border-radius: 50%; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
.side p { margin: 0; color: var(--cs-text); font-size: var(--cs-type-body); }
.side .muted { color: var(--cs-muted); font-size: var(--cs-type-caption); }
@media (max-width: 900px) { .layout { grid-template-columns: minmax(0, 1fr); } }
</style>
