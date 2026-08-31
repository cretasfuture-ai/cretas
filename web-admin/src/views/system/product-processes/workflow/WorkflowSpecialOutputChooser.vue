<template>
  <el-dialog
    :model-value="visible"
    title="添加特殊产出"
    width="520px"
    append-to-body
    data-testid="special-output-chooser"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <p class="chooser-lead">
      选择一类特殊产出，确认后直接进入该类的配置，不会先生成一个空壳 Cell。
    </p>
    <ul class="chooser-list">
      <li v-for="option in options" :key="option.kind">
        <button
          type="button"
          class="chooser-option"
          :class="{ 'is-disabled': !option.available }"
          :disabled="!option.available"
          :data-testid="`special-output-${option.kind}`"
          :aria-disabled="!option.available"
          @click="option.available && emit('choose', option.kind)"
        >
          <span class="chooser-option__badge" aria-hidden="true">{{ option.badge }}</span>
          <span class="chooser-option__text">
            <span class="chooser-option__title">{{ option.title }}</span>
            <span class="chooser-option__desc">{{ option.description }}</span>
            <!--
              ⛔ 不可用时必须说出**具体缺口**, 不是一句「暂不支持」。
              交接 §7: 不良品没有后端权威持久化/运行契约时 fail closed, 并记录准确缺口。
            -->
            <span
              v-if="!option.available"
              class="chooser-option__blocked"
              :data-testid="`special-output-${option.kind}-blocked`"
            >{{ option.blockedReason }}</span>
          </span>
        </button>
      </li>
    </ul>
    <template #footer>
      <el-button data-testid="special-output-cancel" @click="emit('update:visible', false)">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { SpecialOutputKind } from './workflowSpecialOutputs';
import { SPECIAL_OUTPUT_OPTIONS } from './workflowSpecialOutputs';

defineProps<{ visible: boolean }>();
const emit = defineEmits<{
  'update:visible': [visible: boolean];
  choose: [kind: SpecialOutputKind];
}>();

const options = SPECIAL_OUTPUT_OPTIONS;
</script>

<style scoped>
.chooser-lead { margin: 0 0 12px; color: #667085; font-size: 13px; line-height: 1.6; }
.chooser-list { display: flex; flex-direction: column; gap: 10px; margin: 0; padding: 0; list-style: none; }
.chooser-option {
  display: flex; align-items: flex-start; gap: 12px; width: 100%;
  padding: 12px; border: 1px solid #dfe6f2; border-radius: 10px; background: #fff;
  cursor: pointer; text-align: left;
}
.chooser-option:hover:not(.is-disabled) { border-color: #409eff; background: #f5faff; }
.chooser-option:focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; }
.chooser-option.is-disabled { cursor: not-allowed; background: #f7f8fa; border-style: dashed; }
.chooser-option__badge {
  flex: none; display: grid; place-items: center; width: 30px; height: 30px;
  border-radius: 8px; background: #eef4ff; color: #1b65a8; font-weight: 700;
}
.chooser-option__text { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.chooser-option__title { color: #1f2d3d; font-size: 14px; font-weight: 700; }
.chooser-option__desc { color: #667085; font-size: 12px; line-height: 1.55; }
.chooser-option__blocked {
  margin-top: 4px; padding: 6px 8px; border-radius: 6px;
  background: #fff4e6; color: #b5590a; font-size: 12px; line-height: 1.55;
}
</style>
