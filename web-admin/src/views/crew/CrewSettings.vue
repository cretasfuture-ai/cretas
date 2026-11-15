<script setup lang="ts">
/**
 * 设置 › 数字班组配置（P8，原型 /crew/settings，R27）：
 * ① 本厂启用哪几位（改动写原因，保存后回读，留调整记录）；② 能力跟着权限走的对照表（当前登录人）；
 * ③ 一线岗位 → 数字员工（原型是代码常量，正式端按厂可改，F-crew-gaps D4）；④ 调整记录。
 * 能不能改由服务端判（system:read_write）；没权限时只读并写明。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { crewApi, CREW_AVATARS, LEVEL_CN, MODULE_CN, type CrewSettings } from '@/api/digitalCrew';

const auth = useAuthStore();
const s = ref<CrewSettings | null>(null);
const error = ref('');
const notice = ref('');
const draft = reactive<Record<string, boolean>>({});
const mapDraft = reactive<Record<string, string>>({});
const reason = ref('');
const mapReason = ref('');
const saving = ref(false);

function fill(v: CrewSettings) {
  s.value = v;
  for (const m of v.members) draft[m.key] = m.enabled;
  for (const [r, k] of Object.entries(v.roleMapping)) mapDraft[r] = k || '';
}
async function load() {
  if (!auth.factoryId) return;
  error.value = '';
  try { fill(await crewApi.settings(auth.factoryId)); }
  catch (e) { s.value = null; error.value = e instanceof Error ? e.message : '读取失败'; }
}
onMounted(load);
watch(() => auth.factoryId, load);

const dirty = computed(() => !!s.value && s.value.members.some((m) => draft[m.key] !== m.enabled));
const mapDirty = computed(() => !!s.value && Object.entries(s.value.roleMapping).some(([r, k]) => (mapDraft[r] || '') !== (k || '')));
const memberItems = computed(() => [{ label: '不给入口', value: '' }, ...(s.value?.members || []).map((m) => ({ label: m.name, value: m.key }))]);

async function saveMembers() {
  saving.value = true; error.value = ''; notice.value = '';
  try {
    const changed = Object.fromEntries(s.value!.members.filter((m) => draft[m.key] !== m.enabled).map((m) => [m.key, draft[m.key]]));
    fill(await crewApi.updateMembers(auth.factoryId!, changed, reason.value.trim()));
    reason.value = '';
    notice.value = `已保存并回读：本厂启用 ${s.value!.members.filter((m) => m.enabled).length} 位`;
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败'; }
  finally { saving.value = false; }
}
async function saveMapping() {
  saving.value = true; error.value = ''; notice.value = '';
  try {
    const changed = Object.fromEntries(Object.entries(s.value!.roleMapping)
      .filter(([r, k]) => (mapDraft[r] || '') !== (k || '')).map(([r]) => [r, mapDraft[r] || null]));
    fill(await crewApi.updateRoleMapping(auth.factoryId!, changed, mapReason.value.trim()));
    mapReason.value = '';
    notice.value = '已保存并回读岗位对应';
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败'; }
  finally { saving.value = false; }
}
const nameOf = (k?: string | null) => s.value?.members.find((m) => m.key === k)?.name || '不给入口';
</script>

<template>
  <div class="crew-settings" data-testid="crew-settings">
    <header><h1>数字班组配置</h1>
      <p class="why">各厂按需启用；能办什么另由登录人的权限决定。停用的员工不出现在对话入口和手机端，已有记录保留。</p></header>
    <UAlert v-if="error" data-testid="crew-settings-error" color="error" variant="soft" title="没有完成" :description="error" />
    <UAlert v-if="notice" color="success" variant="soft" :title="notice" />
    <template v-if="s">
      <p v-if="!s.canEdit" class="muted" data-testid="crew-settings-readonly">你没有系统设置权限，这一页只读；要调整请找工厂管理员。</p>
      <section class="panel">
        <h2>本厂启用哪几位</h2>
        <label v-for="m in s.members" :key="m.key" class="row">
          <input v-model="draft[m.key]" type="checkbox" :disabled="!s.canEdit" :data-testid="`crew-enable-${m.key}`">
          <img :src="CREW_AVATARS[m.key]" alt="" width="24" height="24"><b>{{ m.name }}</b><span class="muted">{{ m.duty }}</span>
        </label>
        <div v-if="s.canEdit" class="save">
          <UInput v-model="reason" placeholder="写一句为什么调整（必填）" class="reason" data-testid="crew-enable-reason" />
          <UButton color="primary" :disabled="!dirty || reason.trim().length < 2" :loading="saving" data-testid="crew-enable-save" @click="saveMembers">保存启用</UButton>
        </div>
      </section>
      <section class="panel">
        <h2>能力跟着权限走 <small>当前登录人</small></h2>
        <table class="list"><thead><tr><th>数字员工</th><th>管的模块 · 你的权限</th><th>能办的事（写操作都要先确认）</th></tr></thead>
          <tbody><tr v-for="m in s.members" :key="m.key"><td>{{ m.name }}</td>
            <td>{{ m.modules.map((k) => `${MODULE_CN[k] || k}：${LEVEL_CN[m.moduleLevels[k] || '-'] || m.moduleLevels[k]}`).join('、') }}</td>
            <td>{{ m.capabilities.map((c) => c.label).join('；') }}</td></tr></tbody></table>
      </section>
      <section class="panel">
        <h2>一线岗位 → 数字员工 <small>手机端按岗位只给一个入口</small></h2>
        <table class="list"><thead><tr><th>一线岗位</th><th>对应的数字员工</th></tr></thead>
          <tbody><tr v-for="(label, role) in s.roleLabels" :key="role"><td>{{ label }}</td>
            <td><USelect v-if="s.canEdit" v-model="mapDraft[role]" :items="memberItems" :aria-label="`${label}对应的数字员工`" class="sel" />
              <span v-else>{{ nameOf(s.roleMapping[role]) }}</span></td></tr></tbody></table>
        <div v-if="s.canEdit" class="save">
          <UInput v-model="mapReason" placeholder="写一句为什么调整（必填）" class="reason" />
          <UButton color="primary" :disabled="!mapDirty || mapReason.trim().length < 2" :loading="saving" @click="saveMapping">保存岗位对应</UButton>
        </div>
      </section>
      <section class="panel">
        <h2>调整记录 <small>{{ s.logs.length }} 条</small></h2>
        <p v-if="!s.logs.length" class="muted">还没有调整过。</p>
        <table v-else class="list" data-testid="crew-settings-logs"><thead><tr><th>时间</th><th>谁</th><th>改了什么</th><th>改前 → 改后</th><th>原因</th></tr></thead>
          <tbody><tr v-for="(l, i) in s.logs" :key="i"><td>{{ l.at?.replace('T', ' ').slice(0, 16) }}</td><td>{{ l.by }}</td>
            <td>{{ l.kind === 'MEMBERS' ? '启用哪几位' : '岗位对应' }}</td><td class="muted">{{ l.before }} → {{ l.after }}</td><td>{{ l.reason }}</td></tr></tbody></table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.crew-settings { padding: 16px 0; max-width: 1100px; display: grid; gap: 16px; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
h2 { margin: 0 0 8px; font-size: var(--cs-type-section); color: var(--cs-text); }
.why, .muted, small { color: var(--cs-muted); font-size: var(--cs-type-caption); margin: 4px 0 0; }
.panel { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md, 10px); background: var(--cs-surface); padding: 12px 14px; }
.row { display: flex; gap: 10px; align-items: center; padding: 4px 0; }
.row img { border-radius: 50%; }
.save { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.reason { min-width: 320px; }
.sel { min-width: 180px; }
.list { width: 100%; border-collapse: collapse; font-size: var(--cs-type-body); }
.list th, .list td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--cs-border); vertical-align: top; }
.list th { color: var(--cs-muted); font-weight: 500; }
</style>
