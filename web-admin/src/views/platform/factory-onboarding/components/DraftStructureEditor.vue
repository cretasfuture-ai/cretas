<template>
  <div class="draft-structure" :data-testid="`draft-structure-${absPath}`">
    <!-- LIST: 若干行同形卡片; VALUES: 若干个单值。两者共用行数边界、加行 / 删行 -->
    <template v-if="state.kind !== 'OBJECT'">
      <p class="draft-structure-bound" data-testid="draft-structure-bound">{{ rowBound }}</p>
      <p v-if="state.kind === 'VALUES' && valueRule" class="draft-structure-bound" data-testid="draft-values-rule">
        每个{{ spec.itemLabel ?? '值' }}：{{ valueRule }}
      </p>

      <p v-if="state.rows.length === 0" class="draft-structure-empty" data-testid="draft-structure-empty">
        还没有{{ spec.itemLabel ?? '内容' }}，点「添加一行」开始填写。
      </p>

      <template v-if="state.kind === 'VALUES'">
        <div
          v-for="(row, index) in state.rows"
          :key="row.id"
          class="draft-structure-value"
          :data-testid="`draft-value-${absPath}[${index}]`"
        >
          <span class="draft-structure-value-title">{{ draftStructRowTitle(spec, index) }}</span>
          <DraftFieldControl
            v-if="valueField"
            class="draft-structure-value-control"
            :input="valueField.input"
            :spec="valueField.spec"
            :answer="row.answers[valueField.input.path]"
            :abs-path="`${absPath}[${index}]`"
            :error-path="errorPath"
            :error-message="errorMessage"
            layout="stacked"
            bare
            @update="(a: DraftAnswer | undefined) => emit('update', draftStructSetAnswer(state, row.id, valueField!.input.path, a))"
          />
          <el-button
            size="small"
            text
            type="danger"
            :disabled="!canRemove.ok"
            :data-testid="`draft-row-remove-${absPath}[${index}]`"
            @click="emit('update', draftStructRemoveRow(spec, state, row.id))"
          >删除</el-button>
        </div>
      </template>

      <section
        v-for="(row, index) in (state.kind === 'LIST' ? state.rows : [])"
        :key="row.id"
        class="draft-structure-row"
        :data-testid="`draft-row-card-${absPath}[${index}]`"
      >
        <header class="draft-structure-row-head">
          <strong>{{ draftStructRowTitle(spec, index) }}</strong>
          <el-button
            size="small"
            text
            type="danger"
            :disabled="!canRemove.ok"
            :data-testid="`draft-row-remove-${absPath}[${index}]`"
            @click="emit('update', draftStructRemoveRow(spec, state, row.id))"
          >删除</el-button>
        </header>
        <div class="draft-structure-fields">
          <DraftFieldControl
            v-for="field in fields"
            :key="field.input.path"
            :input="field.input"
            :spec="field.spec"
            :answer="row.answers[field.input.path]"
            :abs-path="`${absPath}[${index}].${field.input.path}`"
            :error-path="errorPath"
            :error-message="errorMessage"
            layout="stacked"
            :parent-reason="input.reason"
            @update="(a: DraftAnswer | undefined) => emit('update', draftStructSetAnswer(state, row.id, field.input.path, a))"
          />
        </div>
      </section>

      <div class="draft-structure-actions">
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="!canAdd.ok"
          :data-testid="`draft-row-add-${absPath}`"
          @click="emit('update', draftStructAddRow(spec, state))"
        >添加一行</el-button>
        <small v-if="!canAdd.ok" class="draft-structure-reason" data-testid="draft-row-add-reason">{{ canAdd.reason }}</small>
        <small v-if="state.rows.length > 0 && !canRemove.ok" class="draft-structure-reason"
               data-testid="draft-row-remove-reason">不能再删：{{ canRemove.reason }}</small>
      </div>
    </template>

    <!-- OBJECT: 一个带边框的分组 -->
    <fieldset v-else class="draft-structure-object">
      <legend>{{ input.label }}</legend>
      <div class="draft-structure-fields">
        <DraftFieldControl
          v-for="field in fields"
          :key="field.input.path"
          :input="field.input"
          :spec="field.spec"
          :answer="state.row.answers[field.input.path]"
          :abs-path="`${absPath}.${field.input.path}`"
          :error-path="errorPath"
          :error-message="errorMessage"
          layout="stacked"
          :parent-reason="input.reason"
          @update="(a: DraftAnswer | undefined) => emit('update', draftStructSetAnswer(state, null, field.input.path, a))"
        />
      </div>
    </fieldset>
  </div>
</template>

<script setup lang="ts">
/**
 * 结构化待填项(STRUCTURE)的表单: LIST 是若干行卡片(加行 / 删行受 minItems / maxItems 约束,
 * 边界先显示、不可操作时说原因), OBJECT 是一个分组。子字段一律交给 DraftFieldControl,
 * 子字段若又是 STRUCTURE 会再回到这里(递归)。状态不可变更新, 每次改动向上抛整个新状态。
 */
import { computed } from 'vue'
import type { DraftRequiredInput } from '@/api/factoryBlueprintDraftApi'
import {
  draftBoundText,
  draftConstraintText,
  draftInputSpec,
  draftRowBoundText,
  draftStructAddRow,
  draftStructCanAddRow,
  draftStructCanRemoveRow,
  draftStructRemoveRow,
  draftStructRowTitle,
  draftStructSetAnswer,
  type DraftAnswer,
  type DraftInputSpec,
  type DraftStructState,
} from '../factoryBlueprintDrafts'
import DraftFieldControl from './DraftFieldControl.vue'

const props = defineProps<{
  input: DraftRequiredInput
  spec: DraftInputSpec
  state: DraftStructState
  absPath: string
  errorPath: string | null
  errorMessage: string
}>()

const emit = defineEmits<{ (e: 'update', state: DraftStructState): void }>()

const fields = computed(() => props.spec.itemFields.map((input) => {
  const r = draftInputSpec(input)
  return { input, spec: r.ok ? r.spec : null }
}))
const rowBound = computed(() => draftRowBoundText(props.spec))
/** VALUES 唯一的那个子字段(描述「一个值」) */
const valueField = computed(() => (props.spec.structureKind === 'VALUES' ? fields.value[0] ?? null : null))
/** VALUES 每个值的要求只在列表顶部说一次, 不在每一行重复 */
const valueRule = computed(() => {
  const f = valueField.value
  if (!f) return ''
  return [draftConstraintText(f.input.constraint, f.spec?.options), f.spec ? draftBoundText(f.spec) : ''].filter(Boolean).join('；')
})
const canAdd = computed(() => draftStructCanAddRow(props.spec, props.state))
const canRemove = computed(() => draftStructCanRemoveRow(props.spec, props.state))
</script>

<style scoped>
.draft-structure { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.draft-structure-bound { margin: 0; font-size: 12px; color: var(--el-color-warning-dark-2); }
.draft-structure-empty { margin: 0; font-size: 12px; color: var(--el-text-color-secondary); }
.draft-structure-row,
.draft-structure-object {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 8px 12px 12px;
  margin: 0;
  background: var(--el-bg-color);
  min-width: 0;
}
.draft-structure-row-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.draft-structure-object legend { padding: 0 4px; font-weight: 600; }
.draft-structure-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
/* 嵌套结构独占一整行, 缩进一级 */
.draft-structure-fields > :deep(.draft-input--structure) { grid-column: 1 / -1; padding-left: 16px; }
.draft-structure-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.draft-structure-value { display: grid; grid-template-columns: 72px minmax(0, 1fr) auto; align-items: start; gap: 8px; }
.draft-structure-value-title { padding-top: 14px; font-size: 13px; color: var(--el-text-color-regular); }
.draft-structure-value > :deep(.draft-input) { border-top: none; padding: 4px 0; }
.draft-structure-reason { color: var(--el-text-color-secondary); }
@media (max-width: 900px) {
  .draft-structure-fields { grid-template-columns: minmax(0, 1fr); }
}
</style>
