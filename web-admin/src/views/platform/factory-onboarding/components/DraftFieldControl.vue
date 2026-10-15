<template>
  <div
    class="draft-input"
    :class="{
      'draft-input--stacked': layout === 'stacked' || bare || spec?.type === 'STRUCTURE',
      'draft-input--structure': spec?.type === 'STRUCTURE',
      'draft-input--error': isErrorHere,
    }"
    :data-testid="`draft-input-${absPath}`"
    :data-error="isErrorHere ? 'true' : undefined"
  >
    <div v-if="!bare" class="draft-input-label">
      <strong>{{ input.label }}</strong>
      <small v-if="shownReason" data-testid="draft-input-reason">{{ shownReason }}</small>
      <small v-if="shownConstraint" class="draft-constraint" data-testid="draft-input-constraint">要求：{{ shownConstraint }}</small>
      <small v-if="spec && bound" class="draft-constraint">{{ bound }}</small>
    </div>

    <div class="draft-input-control">
      <span v-if="!spec" class="draft-field-error">格式与页面不一致，无法填写</span>
      <span v-else-if="emptied" class="draft-emptied">已选择留空（将发送空值）</span>

      <DraftStructureEditor
        v-else-if="spec.type === 'STRUCTURE'"
        :input="input"
        :spec="spec"
        :state="structState"
        :abs-path="absPath"
        :error-path="errorPath"
        :error-message="errorMessage"
        @update="(s: DraftStructState) => emit('update', { kind: 'value', value: s })"
      />

      <el-radio-group
        v-else-if="spec.type === 'BOOLEAN'"
        :model-value="value"
        @update:model-value="(v: unknown) => setValue(typeof v === 'boolean' ? v : undefined)"
      >
        <el-radio-button :value="true">是</el-radio-button>
        <el-radio-button :value="false">否</el-radio-button>
      </el-radio-group>

      <el-select
        v-else-if="spec.type === 'ENUM' || spec.type === 'MULTI_ENUM'"
        :model-value="value"
        :multiple="spec.type === 'MULTI_ENUM'"
        :multiple-limit="spec.type === 'MULTI_ENUM' && spec.max !== null ? spec.max : 0"
        filterable
        clearable
        placeholder="请选择"
        @update:model-value="setSelect"
      >
        <el-option v-for="o in spec.options" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>

      <!-- ⛔ 不用 el-input-number: 它失焦时把越界值静默改成 min/max(用户填 1 变成 2 且不说为什么)。
           这里原样保留用户输入, 越界就在旁边说原因、不算填完。 -->
      <el-input
        v-else-if="spec.type === 'INTEGER' || spec.type === 'DECIMAL'"
        class="draft-number-input"
        :model-value="numberText"
        :inputmode="spec.type === 'INTEGER' ? 'numeric' : 'decimal'"
        :placeholder="spec.type === 'INTEGER' ? '填整数' : '填数字'"
        @update:model-value="setNumber"
      />

      <el-date-picker
        v-else-if="spec.type === 'DATE'"
        :model-value="value"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        @update:model-value="(v: unknown) => setValue(typeof v === 'string' && v ? v : undefined)"
      />

      <el-input
        v-else
        :model-value="typeof value === 'string' ? value : ''"
        :maxlength="spec.maxLength ?? undefined"
        :show-word-limit="spec.maxLength !== null"
        placeholder="请填写"
        @update:model-value="(v: string) => setValue(v)"
      />

      <small v-if="problem" class="draft-field-error" data-testid="draft-input-problem">{{ problem }}</small>
      <small v-if="isErrorHere" class="draft-field-error" data-testid="draft-input-server-error">{{ errorMessage }}</small>
      <el-checkbox
        v-if="spec && spec.nullable"
        :model-value="emptied"
        @update:model-value="(v: unknown) => setEmptied(v === true)"
      >明确留空</el-checkbox>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 一个待填项(顶层或结构里的子字段)的控件 + 边界 + 就地原因。顶层和嵌套共用这一份实现和校验,
 * 免得两处各写一套而漂开。STRUCTURE 交给 DraftStructureEditor(它的每个子字段又回到这里)。
 */
import { computed, ref } from 'vue'
import type { DraftRequiredInput } from '@/api/factoryBlueprintDraftApi'
import {
  draftAnswerProblem,
  draftBoundText,
  draftConstraintText,
  draftSubFieldReason,
  draftStructFromValue,
  type DraftAnswer,
  type DraftInputSpec,
  type DraftStructState,
} from '../factoryBlueprintDrafts'
import DraftStructureEditor from './DraftStructureEditor.vue'

const props = withDefaults(defineProps<{
  input: DraftRequiredInput
  /** null = 契约错误(规格读不出来) */
  spec: DraftInputSpec | null
  answer: DraftAnswer | undefined
  /** 这个字段在请求里的绝对路径(与后端报错同语法, 如 positions[1].minimumHeadcount) */
  absPath: string
  errorPath: string | null
  errorMessage: string
  /** wide = 左说明右控件(顶层); stacked = 说明在上(结构里的子字段) */
  layout?: 'wide' | 'stacked'
  /** 只要控件和就地原因, 不显示说明(VALUES 的每一行: 要求已在列表顶部说过) */
  bare?: boolean
  /** 外层结构的原因; 子字段原因和它相同就不重复显示 */
  parentReason?: string | null
}>(), { layout: 'wide', bare: false, parentReason: null })

const emit = defineEmits<{ (e: 'update', answer: DraftAnswer | undefined): void }>()

const bound = computed(() => (props.spec ? draftBoundText(props.spec) : ''))
const shownReason = computed(() => draftSubFieldReason(props.input.reason, props.parentReason))
/** 有选项的字段: 要求里的原值(FREEZER…)换成中文选项名 */
const shownConstraint = computed(() => draftConstraintText(props.input.constraint, props.spec?.options))
const emptied = computed(() => props.answer?.kind === 'empty')
const value = computed(() => (props.answer?.kind === 'value' ? props.answer.value : undefined))
const isErrorHere = computed(() => !!props.errorPath && props.errorPath === props.absPath)

const structState = computed<DraftStructState>(() => {
  const v = value.value as DraftStructState | undefined
  return v && (v.kind === 'LIST' || v.kind === 'VALUES' || v.kind === 'OBJECT') ? v : draftStructFromValue(props.spec!, null)
})

/** 已填但不合规格的原因 + 后端要求原文; 没填不提示(免得一打开满屏红字)。结构自己的缺口由编辑器逐处显示 */
const problem = computed(() => {
  if (!props.spec || !props.answer || props.spec.type === 'STRUCTURE') return null
  const base = draftAnswerProblem(props.spec, props.answer)
  return base && shownConstraint.value ? `${base}（要求：${shownConstraint.value}）` : base
})

/** 数字框里用户敲的原文(⛔ 不改写成 min/max, 不四舍五入) */
const rawNumber = ref<string | null>(null)
const numberText = computed(() => {
  if (rawNumber.value !== null) return rawNumber.value
  const v = value.value
  return typeof v === 'number' || typeof v === 'string' ? String(v) : ''
})

function setValue(v: unknown) {
  emit('update', v === undefined || v === null || v === '' ? undefined : { kind: 'value', value: v })
}

function setSelect(v: unknown) {
  if (Array.isArray(v)) setValue(v.length ? v : undefined)
  else setValue(v === '' ? undefined : v)
}

/** 能读成数按数记(越界由校验说原因、不算填完), 读不成数按原文记(提示「请填数字」) */
function setNumber(text: string) {
  rawNumber.value = text
  const t = text.trim()
  if (!t) {
    setValue(undefined)
    return
  }
  setValue(/^[-+]?(?:\d+\.?\d*|\.\d+)$/.test(t) ? Number(t) : t)
}

function setEmptied(on: boolean) {
  if (on) {
    emit('update', { kind: 'empty' })
  } else if (props.spec?.type === 'STRUCTURE') {
    emit('update', { kind: 'value', value: draftStructFromValue(props.spec, null) })
  } else {
    emit('update', undefined)
  }
}
</script>

<style scoped>
.draft-input {
  display: grid;
  grid-template-columns: minmax(200px, 1fr) minmax(220px, 1fr);
  gap: 12px;
  padding: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.draft-input--stacked { grid-template-columns: minmax(0, 1fr); gap: 6px; }
.draft-input--error {
  background: var(--el-color-danger-light-9);
  outline: 1px solid var(--el-color-danger-light-5);
  border-radius: 4px;
}
.draft-input-label { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.draft-input-label small { color: var(--el-text-color-secondary); }
.draft-constraint { color: var(--el-color-warning-dark-2) !important; }
.draft-input-control { display: flex; flex-direction: column; gap: 6px; align-items: stretch; min-width: 0; }
.draft-field-error { color: var(--el-color-danger); }
.draft-emptied { color: var(--el-text-color-secondary); }
</style>
