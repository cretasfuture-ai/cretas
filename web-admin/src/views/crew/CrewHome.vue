<script setup lang="ts">
/**
 * 数字班组首页（P8，原型 /crew：只留跳转，启用配置不在这里，在「设置 › 数字班组配置」）。
 * 五位数字员工 = 正式端已有的五个数字专员；每位链到自己的对话页和 #1753 已有的数字专员工作台。
 * 能不能查 / 办跟着当前登录人的权限走（服务端算好的 moduleLevels），停用的员工写明，不藏。
 */
import { onMounted, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { crewApi, CREW_AVATARS, LEVEL_CN, MODULE_CN, type CrewMember } from '@/api/digitalCrew';
import { digitalSpecialistTitles } from '@/components/workdesk/digitalSpecialistRoles';

const auth = useAuthStore();
const members = ref<CrewMember[]>([]);
const error = ref('');
const loading = ref(false);
async function load() {
  if (!auth.factoryId) return;
  loading.value = true; error.value = '';
  try { members.value = await crewApi.members(auth.factoryId); }
  catch (e) { members.value = []; error.value = e instanceof Error ? e.message : '读取失败'; }
  finally { loading.value = false; }
}
onMounted(load);
watch(() => auth.factoryId, load);
const levelTone = (l: string) => (l === 'rw' ? 'success' : l === 'r' ? 'info' : 'neutral');
</script>

<template>
  <div class="crew-home" data-testid="crew-home">
    <header class="head">
      <div>
        <h1>数字班组</h1>
        <p class="why">五位数字员工各管一摊：查询直接回答；要办的事先整理成预览，交给有权限的人确认后才执行。只有确认执行、回读核实办成的事才扣额度。</p>
      </div>
      <div class="jumps">
        <UButton to="/todo?tab=confirm" variant="outline" color="neutral">等我确认</UButton>
        <UButton to="/crew/ledger" variant="outline" color="neutral">工作记录与额度</UButton>
        <UButton to="/issues" variant="outline" color="neutral">发现的问题</UButton>
        <UButton to="/crew/settings" variant="ghost" color="neutral">数字班组配置</UButton>
      </div>
    </header>
    <UAlert v-if="error" color="error" variant="soft" title="数字员工没读到" :description="error">
      <template #actions><UButton color="error" variant="outline" @click="load">再试一次</UButton></template>
    </UAlert>
    <p v-else-if="loading" class="muted">读取中…</p>
    <div class="grid">
      <article v-for="m in members" :key="m.key" class="card" :data-testid="`crew-member-${m.key}`" :data-enabled="m.enabled">
        <header>
          <img :src="CREW_AVATARS[m.key]" alt="" width="48" height="48">
          <div class="who"><h2>{{ m.name }}</h2><small>{{ m.duty }}</small></div>
          <UBadge v-if="!m.enabled" label="本厂未启用" color="neutral" variant="soft" />
          <UBadge v-else :label="LEVEL_CN[m.level]" :color="levelTone(m.level)" variant="soft" />
        </header>
        <p class="mods">{{ m.modules.map((k) => `${MODULE_CN[k] || k}：${LEVEL_CN[m.moduleLevels[k] || '-'] || m.moduleLevels[k]}`).join('、') }}</p>
        <ul><li v-for="c in m.capabilities" :key="c.label">{{ c.label }}</li></ul>
        <footer>
          <UButton v-if="m.enabled && m.level !== '-'" :to="`/crew/member/${m.key}`" color="primary" :data-testid="`crew-talk-${m.key}`">和{{ m.shortName }}说</UButton>
          <span v-else-if="!m.enabled" class="muted">停用了：请管理员到「数字班组配置」启用</span>
          <span v-else class="muted">你在这几个模块都没有权限，请联系管理员开通</span>
          <UButton v-for="p in m.workdesks" :key="p" :to="p" variant="link" color="neutral">{{ digitalSpecialistTitles[p] || p }}</UButton>
        </footer>
      </article>
    </div>
  </div>
</template>

<style scoped>
.crew-home { padding: 16px 0; max-width: 1200px; display: grid; gap: 16px; }
.head { display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
.why, .muted, small { color: var(--cs-muted); font-size: var(--cs-type-body); margin: 4px 0 0; }
small { font-size: var(--cs-type-caption); }
.jumps { display: flex; gap: 8px; flex-wrap: wrap; align-items: flex-start; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 12px; }
.card { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md, 10px); background: var(--cs-surface); padding: 14px; display: grid; gap: 8px; }
.card[data-enabled='false'] { opacity: .75; }
.card header { display: flex; gap: 10px; align-items: center; }
.card img { border-radius: 50%; flex: none; }
.who { flex: 1; min-width: 0; }
h2 { margin: 0; font-size: var(--cs-type-section); color: var(--cs-text); }
.mods { margin: 0; font-size: var(--cs-type-caption); color: var(--cs-muted); }
ul { margin: 0; padding-left: 18px; font-size: var(--cs-type-body); color: var(--cs-text); }
footer { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
