<script setup lang="ts">
/**
 * 个体抽屉 —— 这头牛(猪/鸭…)是谁、父母是谁、它去了哪些批次/成品/出货、同父后代与后代。
 * 「从一块好肉倒推到公牛、再看这头公牛的其它后代」是选种的最小闭环。
 *
 * 数据: `GET /{factoryId}/source-individuals/{id}/detail` (#807)。
 * 信息架构照手机端 `screens/shared/SourceIndividualDetailScreen.tsx`。
 *
 * ⛔ 三态分开: 加载中 / 出错(原话) / 有数据 —— 不留一张空档案让人以为「这头没有系谱」。
 * ⛔ 「未登记」「档案已不可查」「确实没有」三种「没有」分开说, 不显示假值。
 * 系谱 / 后代 / 同父后代里的行可点 → 抽屉内换 id (带一个返回栈, 能一层层退回来)。
 *
 * 「编辑」: `source-individuals/index.vue` 不支持 `?edit=<id>` 直接开编辑抽屉 (它没有读
 * 任何 query), 所以这里跳列表并带 `?keyword=<individualCode>` —— 列表按档案编号过滤到
 * 这一头, 用户点行上的「编辑」。列表那一侧读 `?keyword=` 是本次加的最小改动。
 */
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, ArrowRight, Edit } from '@element-plus/icons-vue';
import {
  getSourceIndividualDetail,
  type SourceIndividualDetail,
  type SourceIndividualSummary,
} from '@/api/sourceIndividual';
import type { LineageNode } from '@/api/traceability';
import { dnaStatusLabel, dnaStatusTag, joinPresent } from './traceDisplay';
import LineageNodeRow from './LineageNodeRow.vue';

const props = defineProps<{
  individualId: string | null;
  factoryId: string;
}>();

const visible = defineModel<boolean>({ default: false });
const router = useRouter();

const currentId = ref<string | null>(null);
/** 抽屉内换 id 时压栈, 「返回」弹栈 —— 从后代点进去还能回到父本。 */
const stack = ref<string[]>([]);
const detail = ref<SourceIndividualDetail | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

watch(
  () => [visible.value, props.individualId] as const,
  ([open, id]) => {
    if (!open || !id) return;
    // 每次从外面打开都是一条新的路径: 清栈、从 props 给的 id 开始。
    stack.value = [];
    if (currentId.value === id) {
      // 同一头再点一次: id 没变, 下面那个 watcher 不会响 —— 直接重取, 让上次的错误态能被刷掉。
      void load(id);
    } else {
      currentId.value = id;
    }
  },
  { immediate: true }
);

watch(currentId, (id) => {
  if (id && visible.value) void load(id);
});

async function load(id: string) {
  if (!props.factoryId) {
    error.value = '当前账号没有工厂信息，无法读取个体档案';
    detail.value = null;
    return;
  }
  loading.value = true;
  error.value = null;
  detail.value = null;
  try {
    const res = await getSourceIndividualDetail(props.factoryId, id);
    if (res.success && res.data) {
      detail.value = res.data;
    } else {
      error.value = res.message || '个体档案读取失败';
    }
  } catch (e) {
    error.value = String((e as Error)?.message || '个体档案读取失败');
  } finally {
    loading.value = false;
  }
}

function retry() {
  if (currentId.value) void load(currentId.value);
}

/** 抽屉内换 id (系谱 / 后代 / 同父后代 的行)。 */
function pushIndividual(id: string) {
  if (!id || id === currentId.value) return;
  if (currentId.value) stack.value.push(currentId.value);
  currentId.value = id;
}

function goBack() {
  const prev = stack.value.pop();
  if (prev) currentId.value = prev;
}

function goEdit() {
  const code = detail.value?.individual.individualCode;
  if (!code) return;
  visible.value = false;
  void router.push({ path: '/warehouse/source-individuals', query: { keyword: code } });
}

const individual = computed(() => detail.value?.individual ?? null);
const title = computed(() => individual.value?.individualCode || '个体档案');
const identityLine = computed(() =>
  individual.value ? joinPresent([individual.value.species, individual.value.breed, individual.value.sex]) : null
);

/** 耳标末 4 位 —— 列表行上够用, 完整耳标在头部。⚠️ 耳标本身 ≤4 位就整串给。 */
function tagTail(tagNumber?: string | null): string | null {
  if (!tagNumber) return null;
  const trimmed = tagNumber.trim();
  return trimmed.length <= 4 ? trimmed : trimmed.slice(-4);
}

function summaryLine(s: SourceIndividualSummary): string | null {
  const tail = tagTail(s.tagNumber);
  return joinPresent([tail ? `耳标末四位 ${tail}` : null, s.species, s.breed, s.sex]);
}

const hasDownstream = computed(() => {
  const d = detail.value?.downstream;
  return !!d && d.productionBatches.length + d.finishedBatches.length + d.shipments.length > 0;
});

/** 「去了哪」按链的方向排: 原料批次 → 生产批次 → 成品 → 出货。只列真有内容的段。 */
const whereaboutGroups = computed(() => {
  const d = detail.value?.downstream;
  if (!d) return [] as Array<{ key: string; label: string; nodes: LineageNode[] }>;
  return [
    { key: 'production', label: '生产批次', nodes: d.productionBatches },
    { key: 'finished', label: '成品批次', nodes: d.finishedBatches },
    { key: 'shipments', label: '出货', nodes: d.shipments },
  ].filter((g) => g.nodes.length > 0);
});
</script>

<template>
  <el-drawer v-model="visible" size="560px" class="individual-drawer" data-testid="individual-drawer">
    <template #header>
      <div class="drawer-header">
        <el-button v-if="stack.length > 0" text :icon="ArrowLeft" data-testid="drawer-back" @click="goBack">返回</el-button>
        <span class="drawer-header__title" data-testid="drawer-title">{{ title }}</span>
        <el-button
          v-if="individual"
          size="small"
          :icon="Edit"
          data-testid="drawer-edit"
          @click="goEdit"
        >
          编辑
        </el-button>
      </div>
    </template>

    <el-skeleton v-if="loading" :rows="8" animated data-testid="drawer-loading" />

    <div v-else-if="error" data-testid="drawer-error">
      <el-alert type="error" :title="error" :closable="false" show-icon />
      <el-button class="retry" @click="retry">重试</el-button>
    </div>

    <template v-else-if="detail && individual">
      <!-- 头部: 编码、耳标、物种·品种·性别、出生、来源场、DNA -->
      <section class="drawer-section" data-testid="drawer-identity">
        <div class="identity-row">
          <h3 class="identity-code">{{ individual.individualCode }}</h3>
          <el-tag
            v-if="dnaStatusLabel(individual.dnaVerificationStatus)"
            :type="dnaStatusTag(individual.dnaVerificationStatus)"
            size="small"
            round
            data-testid="drawer-dna"
          >
            {{ dnaStatusLabel(individual.dnaVerificationStatus) }}
          </el-tag>
        </div>
        <p v-if="individual.tagNumber" class="meta">耳标：{{ individual.tagNumber }}</p>
        <p v-if="identityLine" class="meta">{{ identityLine }}</p>
        <p v-if="individual.birthDate" class="meta">出生：{{ individual.birthDate }}</p>
        <p v-if="individual.originFarm" class="meta">来源场：{{ individual.originFarm }}</p>
      </section>

      <!-- 系谱 -->
      <section class="drawer-section">
        <h4 class="section-title">系谱</h4>
        <template v-for="parent in [
          { key: 'sire', label: '父本', summary: detail.sire, registeredId: individual.sireIndividualId },
          { key: 'dam', label: '母本', summary: detail.dam, registeredId: individual.damIndividualId },
        ]" :key="parent.key">
          <div
            v-if="parent.summary"
            class="row is-clickable"
            role="button"
            tabindex="0"
            :data-testid="`drawer-${parent.key}`"
            @click="pushIndividual(parent.summary.id)"
            @keydown.enter.prevent="pushIndividual(parent.summary.id)"
          >
            <div class="row__body">
              <div class="row__title">{{ parent.label }} <span class="mono">{{ parent.summary.individualCode }}</span></div>
              <div v-if="summaryLine(parent.summary)" class="row__line">{{ summaryLine(parent.summary) }}</div>
            </div>
            <el-tag
              v-if="dnaStatusLabel(parent.summary.dnaVerificationStatus)"
              :type="dnaStatusTag(parent.summary.dnaVerificationStatus)"
              size="small"
              round
            >
              {{ dnaStatusLabel(parent.summary.dnaVerificationStatus) }}
            </el-tag>
            <el-icon class="row__chevron"><ArrowRight /></el-icon>
          </div>
          <div v-else class="row" :data-testid="`drawer-${parent.key}`">
            <div class="row__body">
              <div class="row__title">{{ parent.label }}</div>
              <!-- 登记过 id 而档案查不到 = 被删了; 两个都没有 = 没登记。⛔ 两句话不合并 -->
              <div v-if="parent.registeredId" class="row__line is-error">档案已不可查</div>
              <div v-else class="row__line">未登记</div>
            </div>
          </div>
        </template>
      </section>

      <!-- 去了哪: 原料批次 → 生产批次 → 成品 → 出货 -->
      <section class="drawer-section">
        <h4 class="section-title">去了哪</h4>
        <el-timeline class="whereabouts">
          <el-timeline-item type="primary" placement="top" timestamp="原料批次" data-testid="drawer-batches">
            <p v-if="detail.batches.length === 0" class="muted" data-testid="drawer-batches-empty">
              尚未挂到任何原料批次
            </p>
            <div v-for="b in detail.batches" :key="b.id" class="row">
              <div class="row__body">
                <div class="row__title mono">{{ b.batchNumber }}</div>
                <div v-if="joinPresent([b.materialName, b.quantity != null ? `${b.quantity}${b.unit ?? ''}` : null])" class="row__line">
                  {{ joinPresent([b.materialName, b.quantity != null ? `${b.quantity}${b.unit ?? ''}` : null]) }}
                </div>
                <div v-if="b.receiptDate" class="row__line">入库：{{ b.receiptDate }}</div>
              </div>
            </div>
            <p v-if="detail.batches.length > 0 && !hasDownstream" class="muted" data-testid="drawer-not-in-production">
              还没进入生产
            </p>
          </el-timeline-item>
          <el-timeline-item
            v-for="g in whereaboutGroups"
            :key="g.key"
            type="primary"
            hollow
            placement="top"
            :timestamp="g.label"
            :data-testid="`drawer-group-${g.key}`"
          >
            <LineageNodeRow
              v-for="node in g.nodes"
              :key="`${node.nodeType}#${node.nodeId || node.batchNumber || 'unnamed'}`"
              :node="node"
            />
          </el-timeline-item>
        </el-timeline>
      </section>

      <!-- 同父后代 -->
      <section class="drawer-section">
        <h4 class="section-title">同父后代</h4>
        <p v-if="detail.siblingsBySire.length === 0" class="muted" data-testid="drawer-siblings-empty">
          {{ individual.sireIndividualId ? '同一父本下没有其它后代' : '未登记父本，无法查同父后代' }}
        </p>
        <div
          v-for="s in detail.siblingsBySire"
          :key="s.id"
          class="row is-clickable"
          role="button"
          tabindex="0"
          :data-testid="`drawer-sibling-${s.id}`"
          @click="pushIndividual(s.id)"
          @keydown.enter.prevent="pushIndividual(s.id)"
        >
          <div class="row__body">
            <div class="row__title mono">{{ s.individualCode }}</div>
            <div v-if="summaryLine(s)" class="row__line">{{ summaryLine(s) }}</div>
          </div>
          <el-tag v-if="dnaStatusLabel(s.dnaVerificationStatus)" :type="dnaStatusTag(s.dnaVerificationStatus)" size="small" round>
            {{ dnaStatusLabel(s.dnaVerificationStatus) }}
          </el-tag>
          <el-icon class="row__chevron"><ArrowRight /></el-icon>
        </div>
      </section>

      <!-- 后代 -->
      <section class="drawer-section">
        <h4 class="section-title">后代</h4>
        <p v-if="detail.offspring.length === 0" class="muted" data-testid="drawer-offspring-empty">暂无后代记录</p>
        <div
          v-for="s in detail.offspring"
          :key="s.id"
          class="row is-clickable"
          role="button"
          tabindex="0"
          :data-testid="`drawer-offspring-${s.id}`"
          @click="pushIndividual(s.id)"
          @keydown.enter.prevent="pushIndividual(s.id)"
        >
          <div class="row__body">
            <div class="row__title mono">{{ s.individualCode }}</div>
            <div v-if="summaryLine(s)" class="row__line">{{ summaryLine(s) }}</div>
          </div>
          <el-tag v-if="dnaStatusLabel(s.dnaVerificationStatus)" :type="dnaStatusTag(s.dnaVerificationStatus)" size="small" round>
            {{ dnaStatusLabel(s.dnaVerificationStatus) }}
          </el-tag>
          <el-icon class="row__chevron"><ArrowRight /></el-icon>
        </div>
      </section>
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.drawer-header {
  display: flex;
  align-items: center;
  gap: 8px;

  &__title {
    flex: 1;
    font-weight: 600;
    font-size: var(--font-size-md);
    color: var(--el-text-color-primary);
    font-family: var(--font-family-mono);
  }
}

.drawer-section {
  padding-bottom: 16px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-light);

  &:last-child {
    border-bottom: none;
  }
}

.section-title {
  margin: 0 0 8px;
  font-size: var(--font-size-base);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.identity-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.identity-code {
  margin: 0;
  font-size: var(--font-size-xl);
  font-family: var(--font-family-mono);
  color: var(--el-text-color-primary);
}

.meta {
  margin: 4px 0 0;
  color: var(--el-text-color-regular);
}

.muted {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.mono {
  font-family: var(--font-family-mono);
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  min-height: 48px;

  &.is-clickable {
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover,
    &:focus-visible {
      background-color: var(--el-color-primary-light-9);
      outline: none;
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  &__line {
    color: var(--el-text-color-regular);

    &.is-error {
      color: var(--el-color-danger);
    }
  }

  &__chevron {
    flex: none;
    color: var(--el-text-color-secondary);
  }
}

.retry {
  margin-top: 12px;
}

.whereabouts {
  padding-left: 4px;
}
</style>
