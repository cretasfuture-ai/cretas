<script setup lang="ts">
/**
 * 审批流的**线性步骤列表**编辑器 —— 业务入口（系统管理 → 审批链配置）用它替代自由画布。
 *
 * ## 为什么不是画布
 *
 * 2026-09-01 在真客户租户 LIUSHANMEN 上，画布把 `edges_json` 保存成了 `"[]"`，
 * 该审批链从此路由不到任何节点（新单 422 / 在途单 409），且产品内没有恢复入口。
 * 加了保存闸之后事故不再重演，但代价是**只要一条链有边就再也改不动**
 * （画布读出 0 条 → 保存被闸拒绝）。
 *
 * ⇒ 本组件里**不存在「连线」这个概念**：顺序由数组下标决定，
 *   引擎要的图由 {@link stepsToGraph} 确定性生成。结构上不可能丢线。
 *
 * ## 这一层自己扛的两条正确性
 *
 * 1. 分支条件不由用户填 —— `stepsToGraph` 为每个审批步骤生成带 `#decision` 条件的
 *    通过/驳回两条出边。⛔ 用户填不出「空条件」，也就撞不上「空条件=永真取第一条」
 *    那个让「点通过走拒绝」的坑（缺陷 29）。
 * 2. 每一步都有驳回出路 —— 由 `stepsToGraph` 保证，出口自检 `assertGeneratedGraphIsSound` 兜底。
 *
 * 本组件只负责**编辑步骤数组**，不碰接口、不碰版本语义 —— 那些留在 index.vue。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  ApprovalDirectory,
  ApprovalRoleDirectoryItem,
  ApprovalUserDirectoryItem,
} from '@/api/approvalWorkflow'
import type { ApprovalStep, StepType } from '../lib/stepGraph'

const props = defineProps<{
  steps: ApprovalStep[]
  directory: ApprovalDirectory | null
  directoryLoading?: boolean
  directoryError?: string
  readOnly?: boolean
  /** 决策类型的中文名，只用于抬头显示（防呆 Rule 2：上下文必带身份信息）。 */
  decisionLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:steps', steps: ApprovalStep[]): void
}>()

const roles = computed<ApprovalRoleDirectoryItem[]>(() => props.directory?.roles ?? [])
const users = computed<ApprovalUserDirectoryItem[]>(() => props.directory?.users ?? [])

function userLabel(u: ApprovalUserDirectoryItem): string {
  const name = u.fullName || u.realName || u.username
  const dept = u.departmentDisplayName || u.department
  return dept ? `${name}（${dept}）` : name
}

/** ⚠️ 只用于新建步骤的 id。⛔ 不用 Date.now()——同一 tick 连点两次会撞号。 */
let seq = 0
function newStepId(): string {
  seq += 1
  return `step_${seq}_${Math.random().toString(36).slice(2, 8)}`
}

const approvalStepCount = computed(
  () => props.steps.filter(s => s.type === 'approval').length,
)

function commit(next: ApprovalStep[]) {
  emit('update:steps', next)
}

function patchStep(index: number, patch: Partial<ApprovalStep>) {
  const next = props.steps.map((s, i) => (i === index ? { ...s, ...patch } : s))
  commit(next)
}

function patchApprover(index: number, patch: Partial<ApprovalStep['approver']>) {
  const target = props.steps[index]
  if (!target) return
  patchStep(index, { approver: { ...target.approver, ...patch } })
}

function addStep(type: StepType) {
  const ordinal = props.steps.filter(s => s.type === type).length + 1
  commit([
    ...props.steps,
    {
      id: newStepId(),
      type,
      label: type === 'cc' ? `抄送${ordinal}` : `第 ${approvalStepCount.value + 1} 级审批`,
      approver: { roles: [], userIds: [] },
      mode: 'or',
      emptyAction: 'pass',
    },
  ])
}

/**
 * 删除的防呆：审批流必须至少有一个审批步骤，否则 `stepsToGraph` 会抛。
 * ⛔ 不做成「删完再报错」——那是事后报错，不是事前阻止（本仓 fool-proof-design Rule 1）。
 */
function canRemove(index: number): boolean {
  const target = props.steps[index]
  if (!target) return false
  if (target.type === 'cc') return true
  return approvalStepCount.value > 1
}

function removeStep(index: number) {
  if (!canRemove(index)) {
    ElMessage.warning('审批流至少要保留一个审批步骤')
    return
  }
  commit(props.steps.filter((_, i) => i !== index))
}

function move(index: number, delta: -1 | 1) {
  const to = index + delta
  if (to < 0 || to >= props.steps.length) return
  const next = [...props.steps]
  const [moved] = next.splice(index, 1)
  next.splice(to, 0, moved)
  commit(next)
}

/**
 * 「这一步谁来审」的人话摘要。空审批人不是配置错误（可以配成"自动通过"），
 * 但必须**当场说清后果**，⛔ 不要留一个空着的框让用户以为配好了。
 */
function approverSummary(step: ApprovalStep): string {
  if (step.type === 'cc') {
    const n = step.approver.roles.length + step.approver.userIds.length
    return n === 0 ? '未选抄送对象，这一步不会通知任何人' : `抄送 ${n} 个对象`
  }
  const roleNames = step.approver.roles
    .map(r => roles.value.find(x => x.name === r)?.displayName ?? r)
  const userNames = step.approver.userIds
    .map(id => {
      const u = users.value.find(x => String(x.id) === String(id))
      return u ? (u.fullName || u.realName || u.username) : id
    })
  const all = [...roleNames, ...userNames]
  if (all.length === 0) {
    return step.emptyAction === 'block'
      ? '⚠️ 没有审批人，单子会停在这一步，谁也推不动'
      : '⚠️ 没有审批人，这一步会自动通过'
  }
  const who = all.join('、')
  if (step.approver.userIds.length > 0 && step.approver.roles.length > 0) {
    return `${who} —— 指定人员优先于角色`
  }
  return step.mode === 'and' ? `${who} —— 全部通过才算通过` : `${who} —— 任一人通过即可`
}
</script>

<template>
  <div class="step-list-editor">
    <header class="sl-head">
      <div>
        <h4>审批步骤</h4>
        <p>
          从上到下依次审批。<strong>通过</strong>走下一步，<strong>驳回</strong>直接结束 ——
          这两条路由系统自动生成，不需要你配。
        </p>
      </div>
      <div class="sl-head-meta">
        <el-tag v-if="decisionLabel" type="info" effect="plain">{{ decisionLabel }}</el-tag>
        <span>{{ approvalStepCount }} 级审批</span>
      </div>
    </header>

    <el-alert
      v-if="directoryError"
      type="error"
      :closable="false"
      show-icon
      title="审批人目录没加载出来"
      :description="`${directoryError} —— 现在配的审批人可能不全，请刷新后再配。`"
      class="sl-alert"
    />

    <div class="sl-flow">
      <!-- 固定的起点：让用户看见整条链，但改不动它（结构节点不是用户的配置项） -->
      <div class="sl-terminal sl-start">
        <span class="sl-terminal-dot" />
        <div>
          <strong>发起</strong>
          <span>提交人提交后进入第 1 级审批</span>
        </div>
      </div>

      <div v-for="(step, index) in steps" :key="step.id" class="sl-step">
        <div class="sl-rail">
          <span class="sl-index">{{ index + 1 }}</span>
          <span v-if="index < steps.length - 1" class="sl-line" />
        </div>

        <el-card shadow="never" class="sl-card" :class="{ cc: step.type === 'cc' }">
          <div class="sl-card-head">
            <el-input
              :model-value="step.label"
              :disabled="readOnly"
              class="sl-label-input"
              placeholder="给这一步起个中文名，如：财务审批"
              maxlength="30"
              show-word-limit
              @update:model-value="(v: string) => patchStep(index, { label: v })"
            />
            <el-tag :type="step.type === 'cc' ? 'info' : 'primary'" effect="plain">
              {{ step.type === 'cc' ? '抄送' : '审批' }}
            </el-tag>
            <div class="sl-card-actions">
              <el-button text :disabled="readOnly || index === 0" @click="move(index, -1)">
                上移
              </el-button>
              <el-button
                text
                :disabled="readOnly || index === steps.length - 1"
                @click="move(index, 1)"
              >
                下移
              </el-button>
              <el-button
                text
                type="danger"
                :disabled="readOnly || !canRemove(index)"
                @click="removeStep(index)"
              >
                删除
              </el-button>
            </div>
          </div>

          <el-form label-position="top" class="sl-form">
            <div class="sl-form-row">
              <el-form-item :label="step.type === 'cc' ? '抄送角色' : '审批角色'">
                <el-select
                  :model-value="step.approver.roles"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  collapse-tags-tooltip
                  :disabled="readOnly || directoryLoading"
                  :loading="directoryLoading"
                  placeholder="选择角色，如 财务主管"
                  class="sl-select"
                  @update:model-value="(v: string[]) => patchApprover(index, { roles: v })"
                >
                  <el-option
                    v-for="r in roles"
                    :key="r.name"
                    :label="r.department ? `${r.displayName}（${r.department}）` : r.displayName"
                    :value="r.name"
                  />
                </el-select>
              </el-form-item>

              <el-form-item :label="step.type === 'cc' ? '抄送指定人员' : '指定审批人'">
                <el-select
                  :model-value="step.approver.userIds"
                  multiple
                  filterable
                  clearable
                  collapse-tags
                  collapse-tags-tooltip
                  :disabled="readOnly || directoryLoading"
                  :loading="directoryLoading"
                  placeholder="不选则按角色决定"
                  class="sl-select"
                  @update:model-value="(v: string[]) => patchApprover(index, { userIds: v.map(String) })"
                >
                  <el-option
                    v-for="u in users"
                    :key="u.id"
                    :label="userLabel(u)"
                    :value="String(u.id)"
                  />
                </el-select>
              </el-form-item>
            </div>

            <div v-if="step.type === 'approval'" class="sl-form-row">
              <el-form-item label="多人时怎么算通过">
                <el-radio-group
                  :model-value="step.mode ?? 'or'"
                  :disabled="readOnly"
                  @update:model-value="(v: 'or' | 'and') => patchStep(index, { mode: v })"
                >
                  <el-radio-button value="or">任一人通过即可</el-radio-button>
                  <el-radio-button value="and">全部通过才算</el-radio-button>
                </el-radio-group>
              </el-form-item>

              <el-form-item label="没有审批人时">
                <el-radio-group
                  :model-value="step.emptyAction ?? 'pass'"
                  :disabled="readOnly"
                  @update:model-value="(v: 'pass' | 'block') => patchStep(index, { emptyAction: v })"
                >
                  <el-radio-button value="pass">自动通过</el-radio-button>
                  <el-radio-button value="block">停在这一步</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </div>

            <div v-if="step.type === 'approval'" class="sl-form-row">
              <el-form-item label="超时多久没人审（小时，留空=不限时）">
                <el-input-number
                  :model-value="step.timeoutMinutes == null ? undefined : step.timeoutMinutes / 60"
                  :min="1"
                  :max="720"
                  :disabled="readOnly"
                  controls-position="right"
                  class="sl-number"
                  @update:model-value="(v: number | undefined) =>
                    patchStep(index, { timeoutMinutes: v == null ? undefined : Math.round(v * 60) })"
                />
              </el-form-item>

              <el-form-item label="超时转派给谁">
                <el-select
                  :model-value="step.delegateUserId ?? ''"
                  filterable
                  clearable
                  :disabled="readOnly || directoryLoading || step.timeoutMinutes == null"
                  placeholder="先设超时时间才能选"
                  class="sl-select"
                  @update:model-value="(v: string) => {
                    const u = users.find(x => String(x.id) === String(v))
                    patchStep(index, {
                      delegateUserId: v || undefined,
                      delegateUserLabel: u ? userLabel(u) : undefined,
                    })
                  }"
                >
                  <el-option
                    v-for="u in users"
                    :key="u.id"
                    :label="userLabel(u)"
                    :value="String(u.id)"
                  />
                </el-select>
              </el-form-item>
            </div>
          </el-form>

          <p class="sl-summary" :class="{ warn: approverSummary(step).startsWith('⚠️') }">
            {{ approverSummary(step) }}
          </p>
        </el-card>
      </div>

      <div class="sl-add-row">
        <el-button :disabled="readOnly" @click="addStep('approval')">+ 加一级审批</el-button>
        <el-button :disabled="readOnly" @click="addStep('cc')">+ 加一个抄送</el-button>
      </div>

      <div class="sl-terminal sl-end">
        <span class="sl-terminal-dot pass" />
        <div>
          <strong>通过</strong>
          <span>走完全部审批步骤后结束</span>
        </div>
      </div>
      <div class="sl-terminal sl-end">
        <span class="sl-terminal-dot reject" />
        <div>
          <strong>驳回</strong>
          <span>任意一步驳回都直接结束，不会卡住</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.step-list-editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 24px 32px;
  overflow-y: auto;
  height: 100%;
  background: var(--el-bg-color-page, #f5f7fa);
}

.sl-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.sl-head h4 {
  margin: 0 0 4px;
  font-size: 16px;
}

.sl-head p {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.sl-head-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.sl-alert {
  margin: 0;
}

.sl-flow {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 940px;
  width: 100%;
}

.sl-terminal {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--el-bg-color, #fff);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}

.sl-terminal div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sl-terminal strong {
  font-size: 14px;
}

.sl-terminal span:last-child {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sl-terminal-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--el-color-info);
  flex: none;
}

.sl-terminal-dot.pass { background: var(--el-color-success); }
.sl-terminal-dot.reject { background: var(--el-color-danger); }

.sl-step {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.sl-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 28px;
  flex: none;
  padding-top: 14px;
}

.sl-index {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}

.sl-line {
  flex: 1;
  width: 2px;
  background: var(--el-border-color);
  margin-top: 6px;
}

.sl-card {
  flex: 1;
  border-radius: 8px;
}

.sl-card.cc {
  background: var(--el-fill-color-lighter);
}

.sl-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.sl-label-input {
  width: 260px;
}

.sl-card-actions {
  margin-left: auto;
  display: flex;
  gap: 2px;
}

.sl-form {
  margin-top: 4px;
}

.sl-form-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.sl-form-row > .el-form-item {
  flex: 1 1 260px;
  margin-bottom: 12px;
}

.sl-select { width: 100%; }
.sl-number { width: 100%; }

.sl-summary {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sl-summary.warn {
  color: var(--el-color-warning);
}

.sl-add-row {
  display: flex;
  gap: 10px;
  padding-left: 40px;
}
</style>
