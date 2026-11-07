<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';
import { nativePlayback } from '@/demo/f006NativeRuntime';
import { sandboxSession } from '@/demo/f006Sandbox';
import {
  pageAgentState as state, installPageAgent, startGlobalPageAgent,
  pauseGlobalPageAgent, continueGlobalPageAgent, nextGlobalPageModule, endGlobalPageAgent,
} from '@/ai/pageAgentRuntime';

const props = defineProps<{ bootstrapReady: boolean }>();
const router = useRouter();
const auth = useAuthStore();
const permissions = usePermissionStore();
const open = ref(false);
const prompt = ref('');
const answer = ref('');
const sandbox = ref(true);
const localError = ref('');
let dispose: (() => void) | undefined;
const toolbar = ref<HTMLElement>();
let resize: ResizeObserver | undefined;
const canUse = computed(() => Boolean(auth.factoryId === 'F006' && Number(auth.user?.id) === 1309 && auth.currentRole === 'factory_super_admin' && ['procurement', 'sales', 'warehouse', 'inventory', 'production'].some(module => permissions.canWrite(module as ModuleName))));
const canSandbox = computed(() => auth.factoryId === 'F006' && Number(auth.user?.id) === 1309 && auth.currentRole === 'factory_super_admin');
const hasTask = computed(() => state.active || state.scope !== null || state.phase === 'preparing' || state.phase === 'ending');
const scopeMatches = computed(() => !state.scope || state.scope.factoryId === auth.factoryId && state.scope.actorId === String(auth.user?.id) && (!state.scope.role || state.scope.role === auth.currentRole));
const waiting = computed(() => state.phase === 'awaiting-next');
const canContinue = computed(() => state.active && !state.uncertain && ['paused', 'needs-input', 'error'].includes(state.phase));
const busy = computed(() => state.businessPending || state.phase === 'preparing' || state.phase === 'ending');
const statusLabel = computed(() => ({ idle: '全局 AI 操作', preparing: '正在准备', planning: 'AI 正在查看页面', preview: '准备操作', acting: '正在操作原页面', paused: '已暂停', submitting: '正在提交并核对', 'awaiting-next': '已提交 · 等待下一模块', 'needs-input': '需要补充', complete: '本次操作结束', error: '已停止，请核对', ending: '正在结束' }[state.phase]));
const scopeLabel = computed(() => state.scope?.sandboxId || state.phase === 'preparing' ? '隔离演示' : '当前工厂业务');
const events = computed(() => [...state.events].reverse());
function showStart(): void { localError.value = ''; prompt.value = ''; sandbox.value = canSandbox.value || Boolean(sandboxSession.value); open.value = true; }
async function start(): Promise<void> {
  localError.value = '';
  try { await startGlobalPageAgent(prompt.value, canSandbox.value && sandbox.value); open.value = false; }
  catch (error) { localError.value = error instanceof Error ? error.message : '无法开始页面操作'; }
}
function continueTask(): void {
  continueGlobalPageAgent(answer.value);
  answer.value = '';
}
async function end(clear = Boolean(state.scope?.sandboxId)): Promise<void> {
  localError.value = '';
  try { await endGlobalPageAgent(clear); }
  catch (error) { localError.value = error instanceof Error ? error.message : '操作尚未结束，请核对后重试'; }
}
function sizeToolbar(): void {
  const active = hasTask.value && !nativePlayback.enabled;
  document.body.classList.toggle('global-page-agent-active', active);
  if (active && toolbar.value) document.body.style.setProperty('--page-agent-height', `${Math.ceil(toolbar.value.getBoundingClientRect().height)}px`);
  else document.body.style.removeProperty('--page-agent-height');
}
watch([hasTask, () => state.phase], sizeToolbar, { flush: 'post' });
onMounted(() => {
  dispose = installPageAgent(router);
  if (typeof ResizeObserver !== 'undefined') { resize = new ResizeObserver(sizeToolbar); if (toolbar.value) resize.observe(toolbar.value); }
  sizeToolbar();
});
onBeforeUnmount(() => { resize?.disconnect(); dispose?.(); document.body.classList.remove('global-page-agent-active'); document.body.style.removeProperty('--page-agent-height'); });
</script>

<template>
  <Teleport to="body" :disabled="!hasTask">
  <section v-if="(canUse || hasTask) && !nativePlayback.enabled" ref="toolbar" class="global-ai-control" :class="{ 'is-active': hasTask }" data-ai-page-agent data-testid="global-ai-control" :data-agent-phase="state.phase" :data-agent-step="state.moduleStep" :data-agent-pending="state.businessPending" :aria-busy="busy">
    <template v-if="!scopeMatches">
      <strong>账号或工厂已变化，原AI任务已停止</strong>
      <el-button size="small" :disabled="state.businessPending" @click="end(false)">交还手动操作</el-button>
    </template>
    <template v-else-if="!hasTask">
      <strong>全局 AI 操作</strong>
      <span class="idle-hint">在原页面逐步填写和提交，每次提交后等待“下一模块”</span>
      <el-button type="primary" size="small" :disabled="!props.bootstrapReady" data-testid="global-ai-open" @click="showStart">开始</el-button>
    </template>
    <template v-else>
      <div class="agent-status" role="status" aria-live="polite">
        <strong>{{ statusLabel }}</strong>
        <span class="agent-scope">{{ auth.factoryId }} · {{ scopeLabel }} · 第{{ state.moduleStep }}模块</span>
        <span class="agent-stage">{{ localError || state.error || state.stage }}<template v-if="state.remaining"> · {{ state.remaining }}秒</template></span>
      </div>
      <div class="agent-buttons">
        <el-button v-if="canContinue" type="primary" size="small" data-testid="global-ai-continue" @click="continueTask">继续</el-button>
        <el-button v-else size="small" :disabled="!state.active || busy || waiting || state.phase === 'complete'" data-testid="global-ai-pause" @click="pauseGlobalPageAgent">暂停</el-button>
        <el-button type="success" size="small" :disabled="!waiting || busy || state.uncertain" data-testid="global-ai-next" @click="nextGlobalPageModule">下一模块</el-button>
        <el-popover placement="bottom-end" :width="520" trigger="click" popper-class="global-ai-event-popover" :teleported="false">
          <template #reference><el-button size="small" data-testid="global-ai-history">操作记录 {{ state.events.length }}</el-button></template>
          <div data-ai-page-agent class="agent-history" aria-label="AI实际操作记录">
            <p class="history-goal">任务：{{ state.goal }}</p>
            <ol>
              <li v-for="(event, index) in events" :key="event.at + index">
                <time>{{ new Date(event.at).toLocaleTimeString('zh-CN', { hour12: false }) }}</time>
                <span>{{ event.text }}</span>
                <small v-if="event.before !== undefined || event.after !== undefined">{{ event.before || '空' }} → {{ event.after || '空' }}</small>
              </li>
            </ol>
            <p v-if="!events.length">开始操作后，这里显示实际填写、点击、提交及核对记录。</p>
          </div>
        </el-popover>
        <el-button v-if="!state.uncertain && (state.phase === 'error' || state.phase === 'needs-input' || state.phase === 'paused')" size="small" :disabled="busy" @click="end(false)">交还手动操作</el-button>
        <el-button type="warning" size="small" :loading="state.phase === 'ending'" :disabled="busy" data-testid="global-ai-end" @click="end()">{{ state.scope?.sandboxId ? '结束演示并清空' : '结束操作' }}</el-button>
      </div>
      <div v-if="state.phase === 'needs-input' || state.phase === 'paused'" class="agent-answer">
        <el-input v-model="answer" aria-label="补充本次AI任务" placeholder="可补充供应商、产品、数量等信息，再点击继续" :maxlength="3000" @keyup.enter="continueTask" />
      </div>
    </template>
    <el-dialog v-model="open" title="让 AI 操作原业务页面" width="min(620px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="state.phase !== 'preparing'" :show-close="state.phase !== 'preparing'" data-ai-page-agent append-to-body>
      <div data-ai-page-agent>
        <p class="dialog-intro">说明本次要办的业务。AI 会跳转到原页面，逐项填写并提交；每次提交后停留展示，只有点击“下一模块”才继续。</p>
        <el-input v-model="prompt" type="textarea" :rows="5" :maxlength="3000" show-word-limit aria-label="全局AI操作任务" placeholder="例如：向指定供应商采购指定物料，填写数量、单价，保存采购草稿后停留。也可以说明后续的销售或生产操作。" :disabled="state.phase === 'preparing'" />
        <el-checkbox v-if="canSandbox" v-model="sandbox" disabled>使用可清空的 F006 隔离演示数据</el-checkbox>
        <p class="scope-hint">本轮业务数据位于隔离沙盒，结束演示后清空。当前先在 f006_admin 试用原页面操作。</p>
        <el-alert v-if="localError" :title="localError" type="error" :closable="false" />
        <p v-if="state.phase === 'preparing'" role="status">{{ state.stage }}</p>
      </div>
      <template #footer>
        <el-button :disabled="state.phase === 'preparing'" @click="open = false">取消</el-button>
        <el-button type="primary" :disabled="!prompt.trim() || !props.bootstrapReady" :loading="state.phase === 'preparing'" data-testid="global-ai-start" @click="start">开始执行</el-button>
      </template>
    </el-dialog>
  </section>
  </Teleport>
</template>

<style scoped>
.global-ai-control { display:flex; align-items:center; flex-wrap:wrap; gap:8px 16px; padding:10px 20px; background:var(--el-fill-color-light,#f5f7fa); border-bottom:1px solid var(--el-border-color-light,#e4e7ed); font-size:13px; }
.global-ai-control.is-active { position:fixed; top:0; left:0; right:0; z-index:10000; box-sizing:border-box; }
.global-ai-control>strong { color:var(--el-color-primary,#409eff); white-space:nowrap; }
.idle-hint { color:var(--el-text-color-secondary,#909399); flex:1; }
.agent-status { display:flex; flex:1; align-items:center; flex-wrap:wrap; gap:6px 12px; min-width:220px; }
.agent-scope { color:var(--el-text-color-secondary,#909399); font-size:12px; }
.agent-stage { flex-basis:100%; line-height:1.5; overflow-wrap:anywhere; }
.agent-buttons { display:flex; gap:6px; flex-wrap:wrap; align-items:center; }
.agent-buttons :deep(.el-button + .el-button) { margin-left:0; }
.agent-answer { flex-basis:100%; max-width:800px; }
.agent-history { max-height:400px; overflow:auto; font-size:13px; }
.history-goal { margin:0 0 12px; white-space:pre-wrap; overflow-wrap:anywhere; }
.agent-history ol { margin:0; padding-left:20px; }
.agent-history li { margin:0 0 12px; }
.agent-history time { color:var(--el-text-color-secondary,#909399); margin-right:8px; }
.agent-history small { display:block; margin-top:4px; color:var(--el-text-color-secondary,#909399); overflow-wrap:anywhere; }
.dialog-intro,.scope-hint { color:var(--el-text-color-regular,#606266); line-height:1.6; }
.scope-hint { font-size:12px; }
@media(max-width:800px) { .global-ai-control { padding:10px 12px; } .agent-buttons { width:100%; } }
</style>

<style>
body.global-page-agent-active { padding-top:var(--page-agent-height,80px); }
body.global-page-agent-active .app-sidebar { top:var(--page-agent-height,80px); height:calc(100vh - var(--page-agent-height,80px)); }
body.global-page-agent-active .app-header { top:var(--page-agent-height,80px); }
body.global-page-agent-active .el-dialog.is-fullscreen { margin-top:var(--page-agent-height,80px); height:calc(100vh - var(--page-agent-height,80px)); }
body.global-page-agent-active .el-drawer { top:var(--page-agent-height,80px); height:calc(100vh - var(--page-agent-height,80px)); }
.page-agent-current-control { outline:3px solid var(--el-color-primary,#409eff)!important; outline-offset:4px; }
.global-ai-event-popover { max-width:calc(100vw - 32px); }
@media(prefers-reduced-motion:reduce) { .page-agent-current-control { scroll-behavior:auto; } }
</style>
