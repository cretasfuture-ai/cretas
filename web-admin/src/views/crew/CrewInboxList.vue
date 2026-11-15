<script setup lang="ts">
/**
 * 待办 › 等我确认（P8，原型 /crew/inbox 并入这里）。
 * 每件事：先「按我的权限预览」（服务端按我的身份重新预览，改前 / 改后每一行都显示，不藏），
 * 看过点「确认执行」才发确认请求；或者「不办」（选原因，记没办成，不扣额度）。
 * 能不能确认由服务端按登录人判；这里的按钮只是提示，没权限的事项服务端根本不返回。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { crewApi, CREW_AVATARS, MODULE_CN, STATUS_TONE, type CrewConfirmableAction, type CrewInbox, type CrewItem, type CrewPreviewResponse } from '@/api/digitalCrew';
import { previewRows } from '@/views/smart-bi/components/chat/aiWriteConfirmRows';

const emit = defineEmits<{ changed: [] }>();
const auth = useAuthStore();
const inbox = ref<CrewInbox | null>(null);
const loading = ref(false);
const error = ref('');
interface Row { preview?: CrewPreviewResponse; busy: boolean; error: string; rejecting: boolean; reason: string; other: string; done?: CrewItem }
const rows = reactive<Record<number, Row>>({});
const REASONS = ['不需要了', '内容不对，要重新说', '这周排不开', '已经在别处办了', '其他'];

const row = (id: number): Row => (rows[id] ||= { busy: false, error: '', rejecting: false, reason: REASONS[0], other: '' });
const errText = (e: unknown) => (e instanceof Error && e.message ? e.message : '操作失败');

async function load() {
  if (!auth.factoryId) return;
  loading.value = true; error.value = '';
  try { inbox.value = await crewApi.inbox(auth.factoryId); }
  catch (e) { inbox.value = null; error.value = `读取失败：${errText(e)}。没有执行任何写操作。`; }
  finally { loading.value = false; }
}
onMounted(load);
watch(() => auth.factoryId, load);

async function preview(item: CrewItem) {
  const r = row(item.id); r.busy = true; r.error = ''; r.preview = undefined;
  try { r.preview = (await crewApi.preview(auth.factoryId!, item.id)).preview; }
  catch (e) { r.error = `${errText(e)}（没有执行任何写操作）`; }
  finally { r.busy = false; }
}
const action = (r: Row): CrewConfirmableAction | null => r.preview?.confirmableAction ?? null;
const lines = (r: Row) => previewRows(action(r));

async function confirm(item: CrewItem) {
  const r = row(item.id); const a = action(r); if (!a) return;
  r.busy = true; r.error = '';
  try { r.done = await crewApi.confirm(auth.factoryId!, item.id, a); emit('changed'); }
  catch (e) { r.error = `${errText(e)}。请先在业务页面核对结果，勿重复办理。`; }
  finally { r.busy = false; }
}
async function reject(item: CrewItem) {
  const r = row(item.id);
  const reason = r.reason === '其他' ? r.other.trim() : r.reason;
  if (reason.length < 2) { r.error = '选「其他」时请写一句原因（至少 2 个字）'; return; }
  r.busy = true; r.error = '';
  try { r.done = await crewApi.reject(auth.factoryId!, item.id, reason); r.rejecting = false; emit('changed'); }
  catch (e) { r.error = errText(e); }
  finally { r.busy = false; }
}
const hiddenText = computed(() => inbox.value && inbox.value.hiddenCount
  ? `另有 ${inbox.value.hiddenCount} 件要别的岗位确认（${inbox.value.hiddenModules.map((m) => MODULE_CN[m] || m).join('、')}），按你的权限不显示。` : '');
defineExpose({ load });
</script>

<template>
  <section class="crew-inbox" data-testid="crew-inbox">
    <p v-if="loading" class="muted">读取中…</p>
    <UAlert v-else-if="error" data-testid="crew-inbox-error" color="error" variant="soft" title="等我确认没读到" :description="error">
      <template #actions><UButton color="error" variant="outline" @click="load">再试一次</UButton></template>
    </UAlert>
    <template v-else-if="inbox">
      <p v-if="hiddenText" class="muted" data-testid="crew-inbox-hidden">{{ hiddenText }}</p>
      <p v-if="!inbox.mine.length" class="muted" data-testid="crew-inbox-empty">
        没有等你确认的事。在「数字班组」里对数字员工说要办的事，它整理好的预览会进这里。
        <RouterLink to="/crew">去数字班组</RouterLink>
      </p>
      <article v-for="item in inbox.mine" :key="item.id" class="item" :data-testid="`crew-item-${item.id}`">
        <header>
          <img :src="CREW_AVATARS[item.memberKey]" alt="" width="32" height="32">
          <div class="who">
            <h4>{{ item.title }}</h4>
            <small>{{ item.memberName }} · {{ item.createdByName }} 交办 · {{ item.createdAt?.replace('T', ' ').slice(0, 16) }} · 需要 {{ MODULE_CN[item.module] || item.module }} 办理权限</small>
          </div>
          <UBadge :label="row(item.id).done?.statusLabel || item.statusLabel" :color="STATUS_TONE[row(item.id).done?.status || item.status]" variant="soft" />
        </header>
        <p class="summary">{{ item.previewSummary }}</p>
        <p v-if="item.resultNote" class="note">上次：{{ item.resultNote }}</p>

        <template v-if="row(item.id).done">
          <p class="result" data-testid="crew-item-result">
            {{ row(item.id).done!.statusLabel }}<span v-if="row(item.id).done!.quotaCost">（扣 1 个额度）</span>：{{ row(item.id).done!.resultNote || row(item.id).done!.evidence || '' }}
          </p>
        </template>
        <template v-else>
          <div v-if="row(item.id).preview" class="preview" data-testid="crew-item-preview">
            <p class="pv-msg">{{ row(item.id).preview!.formattedText || row(item.id).preview!.message || row(item.id).preview!.status }}</p>
            <table v-if="lines(row(item.id)).length" data-testid="ai-confirm-rows">
              <tr v-for="l in lines(row(item.id))" :key="l.key"><th>{{ l.label }}</th><td><s v-if="l.changed && l.before">{{ l.before }}</s> {{ l.value }}</td></tr>
            </table>
            <p v-if="action(row(item.id))?.impactSummary" class="muted">{{ action(row(item.id))!.impactSummary }}</p>
            <p v-if="!action(row(item.id))" class="note">这次预览没有给出可确认的内容（见上面的说明），不能确认。</p>
          </div>
          <div v-if="row(item.id).rejecting" class="reject">
            <USelect v-model="row(item.id).reason" :items="REASONS" aria-label="不办的原因" />
            <UTextarea v-if="row(item.id).reason === '其他'" v-model="row(item.id).other" :rows="2" placeholder="写一句为什么不办" />
            <UButton color="neutral" :loading="row(item.id).busy" data-testid="crew-reject-submit" @click="reject(item)">记为没办成</UButton>
            <UButton variant="ghost" color="neutral" @click="row(item.id).rejecting = false">取消</UButton>
          </div>
          <div v-else class="actions">
            <UButton v-if="!row(item.id).preview" :loading="row(item.id).busy" data-testid="crew-preview" @click="preview(item)">按我的权限预览</UButton>
            <template v-else>
              <UButton color="primary" :disabled="!action(row(item.id))" :loading="row(item.id).busy" data-testid="crew-confirm" @click="confirm(item)">确认执行</UButton>
              <UButton variant="outline" color="neutral" :disabled="row(item.id).busy" @click="preview(item)">重新预览</UButton>
            </template>
            <UButton variant="ghost" color="neutral" :disabled="row(item.id).busy" data-testid="crew-reject" @click="row(item.id).rejecting = true">不办</UButton>
          </div>
        </template>
        <p v-if="row(item.id).error" class="err" data-testid="crew-item-error">{{ row(item.id).error }}</p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.crew-inbox { display: grid; gap: 12px; }
.item { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md, 10px); padding: 12px 14px; background: var(--cs-surface); display: grid; gap: 8px; }
.item header { display: flex; gap: 10px; align-items: center; }
.item img { border-radius: 50%; flex: none; }
.who { flex: 1; min-width: 0; }
h4 { margin: 0; font-size: var(--cs-type-body); color: var(--cs-text); }
small, .muted { color: var(--cs-muted); font-size: var(--cs-type-caption); }
.summary { margin: 0; color: var(--cs-text); font-size: var(--cs-type-body); }
.note { margin: 0; color: var(--cs-warning, #b26a00); font-size: var(--cs-type-caption); }
.err { margin: 0; color: var(--cs-danger); font-size: var(--cs-type-caption); }
.result { margin: 0; font-weight: 600; color: var(--cs-text); }
.preview { background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); padding: 8px 10px; }
.pv-msg { margin: 0 0 6px; white-space: pre-wrap; }
table { border-collapse: collapse; width: 100%; font-size: var(--cs-type-body); }
th { text-align: left; color: var(--cs-muted); font-weight: 400; padding: 2px 12px 2px 0; width: 30%; vertical-align: top; }
td { padding: 2px 0; }
s { color: var(--cs-muted); }
.actions, .reject { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
