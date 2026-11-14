<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { useIssues } from './useIssues';
import CloseIssueDialog from './CloseIssueDialog.vue';
import {
  CATEGORY_DEFS, RENDERED_CATEGORIES, SRC_IDS, categoryBadge, issueStatus,
  type Issue, type IssueCategory,
} from './issuesData';
import { closeIssue, convertIssueToTodo, reopenIssue, type IssueConclusion, type IssueRef } from '@/api/issues';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const { S, reload, applyDisposition } = useIssues();
onMounted(() => { void reload(); });
watch(() => auth.factoryId, () => { void reload(); });

type Tab = IssueCategory | 'all';
const tab = computed<Tab>(() => {
  const q = String(route.query.source || '');
  return (RENDERED_CATEGORIES.some((c) => c.id === q) ? q : 'all') as Tab;
});
const setTab = (t: Tab) => { void router.replace({ path: '/issues', query: t === 'all' ? {} : { source: t } }); };
const cat = computed(() => CATEGORY_DEFS.find((c) => c.id === tab.value));
const dispositionsOk = computed(() => S.dispositionState === 'ok');
const badge = (c: IssueCategory) => categoryBadge(c, S.src, S.issues, S.dispositions, dispositionsOk.value);
const allBadge = computed(() => {
  const parts = RENDERED_CATEGORIES.filter((c) => SRC_IDS.some((id) => S.src[id].categories.includes(c.id))).map((c) => badge(c.id));
  return parts.some((n) => n === null) ? null : parts.reduce<number>((a, n) => a + (n ?? 0), 0);
});

const renderedIds = new Set(RENDERED_CATEGORIES.map((c) => c.id));
const status = (i: Issue) => issueStatus(i, S.dispositions);
const list = computed(() => S.issues
  .filter((i) => renderedIds.has(i.category) && (tab.value === 'all' || i.category === tab.value))
  .sort((a, b) => Number(status(a) === 'closed') - Number(status(b) === 'closed')));
/** 本页签涉及的来源里读失败 / 不适用的，逐条写原因（G-2）。 */
const srcNotes = computed(() => SRC_IDS.map((id) => S.src[id])
  .filter((s) => (tab.value === 'all' || s.categories.includes(tab.value)) && (s.state === 'error' || s.state === 'na')));

const ref0 = (i: Issue): IssueRef => ({ sourceType: i.sourceType, sourceId: i.sourceId, title: i.title, linkPath: i.link });
const msg = ref('');
const rowErr = ref<Record<string, string>>({});
const busyKey = ref('');
const errOf = (e: unknown) => (e instanceof Error && e.message ? e.message : '操作失败');

const closing = ref<Issue | null>(null);
const closeOpen = ref(false);
const closeErr = ref('');
const closeBusy = ref(false);
function openClose(i: Issue) { closing.value = i; closeErr.value = ''; closeOpen.value = true; }
async function doClose(v: { conclusion: IssueConclusion; reason: string }) {
  const i = closing.value;
  if (!i) return;
  closeBusy.value = true;
  closeErr.value = '';
  try {
    const res = await closeIssue(S.factoryId, ref0(i), v.conclusion, v.reason);
    applyDisposition(res.data);
    closeOpen.value = false;
    msg.value = `已关闭：${i.title}（${res.data.conclusionLabel}）`;
  } catch (e) { closeErr.value = errOf(e); } finally { closeBusy.value = false; }
}
async function act(i: Issue, kind: 'convert' | 'reopen') {
  busyKey.value = i.key;
  rowErr.value = { ...rowErr.value, [i.key]: '' };
  try {
    const res = kind === 'convert' ? await convertIssueToTodo(S.factoryId, ref0(i)) : await reopenIssue(S.factoryId, ref0(i));
    applyDisposition(res.data);
    msg.value = kind === 'convert' ? `已转成你的待办，在「待办 › 指派给我」：${i.title}` : `已恢复：${i.title}`;
  } catch (e) {
    rowErr.value = { ...rowErr.value, [i.key]: `${kind === 'convert' ? '没有转成待办' : '没有恢复'}：${errOf(e)}` };
  } finally { busyKey.value = ''; }
}
const go = (to: string) => { void router.push(to); };
const conclusionText = (i: Issue) => {
  const d = S.dispositions[i.key];
  return d ? `${d.conclusionLabel ?? '已关闭'}：${d.reason ?? ''}` : '';
};
</script>

<template>
  <div class="issues" data-testid="issues-page">
    <header class="head">
      <div>
        <h1>发现的问题</h1>
        <p class="why">系统发现、还没处理完的问题都在这一页，按来源分类。可以转成自己的待办；关闭要选结论再写一句。来源单据仍在各自页面处理，这里不改它们。</p>
      </div>
      <el-button :loading="S.busy" data-testid="issues-refresh" @click="reload">刷新</el-button>
    </header>

    <div class="tabs" role="tablist">
      <button role="tab" :aria-selected="tab === 'all'" :class="{ on: tab === 'all' }" data-testid="issues-tab-all" @click="setTab('all')">
        全部<b v-if="allBadge !== null" class="badge" data-testid="issues-tab-all-badge">{{ allBadge }}</b>
      </button>
      <button v-for="c in RENDERED_CATEGORIES" :key="c.id" role="tab" :title="c.hint" :aria-selected="tab === c.id"
        :class="{ on: tab === c.id }" :data-testid="`issues-tab-${c.id}`" @click="setTab(c.id)">
        {{ c.label }}<b v-if="badge(c.id) !== null" class="badge" :data-testid="`issues-tab-${c.id}-badge`">{{ badge(c.id) }}</b>
      </button>
    </div>

    <el-card class="card" shadow="never">
      <p v-if="cat" class="hint">{{ cat.hint }}</p>
      <p v-if="cat?.reason" class="reason" :data-testid="`issues-reason-${cat.id}`">{{ cat.reason }}</p>
      <p v-if="S.dispositionState === 'error'" class="bad" data-testid="issues-disposition-error">{{ S.dispositionNote }}</p>
      <p v-for="s in srcNotes" :key="s.id" :class="['src-note', { bad: s.state === 'error' }]" :data-testid="`issues-src-${s.id}`">
        {{ s.title }}：{{ s.note }}
      </p>
      <p v-if="msg" class="ok" data-testid="issues-msg">{{ msg }}</p>

      <article v-for="i in list" :key="i.key" class="row" :class="{ closed: status(i) === 'closed' }"
        :data-testid="`issue-${i.key}`" :data-status="status(i)">
        <div class="main">
          <div class="title">
            <span class="chip">{{ i.sourceLabel }}</span>
            <span>{{ i.title }}</span>
          </div>
          <div class="sub">{{ i.summary }}<template v-if="i.owner"> · 找谁：{{ i.owner }}</template><template v-if="i.at"> · {{ i.at }}</template></div>
          <div v-if="status(i) === 'closed'" class="state" data-testid="issue-conclusion">{{ conclusionText(i) }}</div>
          <div v-else-if="status(i) === 'converted'" class="state conv">已转成待办</div>
          <div v-if="rowErr[i.key]" class="bad">{{ rowErr[i.key] }}</div>
        </div>
        <div class="actions">
          <el-button link type="primary" @click="go(i.link)">{{ i.linkLabel }}</el-button>
          <template v-if="dispositionsOk">
            <el-button v-if="status(i) === 'closed'" size="small" :loading="busyKey === i.key" @click="act(i, 'reopen')">恢复</el-button>
            <template v-else>
              <el-button size="small" data-testid="issue-close" @click="openClose(i)">关闭…</el-button>
              <el-button v-if="status(i) === 'open'" size="small" type="primary" :loading="busyKey === i.key"
                data-testid="issue-convert" @click="act(i, 'convert')">转成待办</el-button>
            </template>
          </template>
        </div>
      </article>
      <p v-if="!S.busy && !list.length && !cat?.reason" class="muted" data-testid="issues-empty">
        {{ srcNotes.length ? '读到的来源里现在没有问题；上面列出的来源没读到，不代表没有。' : '现在没有。' }}
      </p>
    </el-card>

    <CloseIssueDialog v-model="closeOpen" :title="closing?.title ?? ''" :submitting="closeBusy" :error="closeErr" @submit="doClose" />
  </div>
</template>

<style scoped>
.issues { padding: 16px 0; max-width: 1040px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 12px; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
.why { margin: 4px 0 0; color: var(--cs-muted); font-size: var(--cs-type-body); max-width: 760px; }
.tabs { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 8px; }
.tabs button { border: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); padding: 6px 14px; border-radius: var(--cs-radius-sm); cursor: pointer; font-size: var(--cs-type-body); }
.tabs button.on { background: var(--cs-primary-soft); border-color: var(--cs-primary); color: var(--cs-primary); }
.badge { margin-left: 6px; background: var(--cs-primary); color: #fff; border-radius: 10px; padding: 0 7px; font-size: var(--cs-type-caption); }
.hint, .src-note, .muted { margin: 0 0 6px; color: var(--cs-muted); font-size: var(--cs-type-caption); }
.reason { margin: 0 0 8px; padding: 8px 10px; background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); color: var(--cs-muted); font-size: var(--cs-type-body); }
.bad { margin: 4px 0; color: var(--cs-danger); font-size: var(--cs-type-caption); }
.ok { margin: 4px 0 8px; color: var(--cs-success); font-size: var(--cs-type-caption); }
.row { display: flex; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--cs-border); }
.row:last-of-type { border-bottom: 0; }
.row.closed { opacity: 0.65; }
.title { display: flex; gap: 8px; align-items: center; color: var(--cs-text); font-size: var(--cs-type-body); font-weight: 600; }
.chip { font-weight: 400; font-size: var(--cs-type-caption); padding: 1px 8px; border-radius: 10px; background: var(--cs-surface-subtle); color: var(--cs-muted); border: 1px solid var(--cs-border); }
.sub { margin-top: 4px; color: var(--cs-muted); font-size: var(--cs-type-caption); }
.state { margin-top: 4px; font-size: var(--cs-type-caption); color: var(--cs-text); }
.state.conv { color: var(--cs-success); }
.actions { display: flex; gap: 6px; align-items: flex-start; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
@media (max-width: 640px) { .row { flex-direction: column; } .actions { justify-content: flex-start; } }
</style>
