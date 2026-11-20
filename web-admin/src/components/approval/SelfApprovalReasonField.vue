<script setup lang="ts">
/**
 * 本人审批原因输入 (纪律排查 B1–B3 / B11 / B15)。
 * 只在「当前登录人就是提交人」时由父组件渲染; v-model 是拼好的最终原因 (提交前用 validateSelfApprovalReason 拦空)。
 * 防呆 Rule 3: 常见原因下拉, 选「其他」才必须补充文字。
 */
import { ref, watch } from 'vue';
import { SELF_APPROVAL_PRESET_REASONS, SELF_APPROVAL_REASON_MAX, composeSelfApprovalReason } from '@/utils/selfApproval';

defineProps<{
  /** 对话框里这一步叫什么, 例如「审批」「登记付款」, 用于提示文案。 */
  actionLabel?: string;
}>();

const reason = defineModel<string>({ default: '' });

const selected = ref<string>(SELF_APPROVAL_PRESET_REASONS[0]);
const extra = ref('');

watch([selected, extra], () => {
  reason.value = composeSelfApprovalReason(selected.value, extra.value);
}, { immediate: true });
</script>

<template>
  <div class="self-approval-field" data-testid="self-approval-reason">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      :title="`这是你自己提交的单，正在本人${actionLabel || '审批'}`"
      class="self-approval-field__alert"
    >
      <template #default>
        只有本厂在「工厂配置」里打开了「允许本人审批」才能办理；必须写明原因，系统会在审批记录上标注为本人审批。
        没有打开时请换另一位有权限的同事处理。
      </template>
    </el-alert>
    <div class="self-approval-field__label">
      本人{{ actionLabel || '审批' }}原因<span class="self-approval-field__required">*</span>
    </div>
    <el-select v-model="selected" style="width: 100%; margin-bottom: 8px" data-testid="self-approval-reason-select">
      <el-option v-for="r in SELF_APPROVAL_PRESET_REASONS" :key="r" :label="r" :value="r" />
    </el-select>
    <el-input
      v-model="extra"
      type="textarea"
      :rows="2"
      :maxlength="SELF_APPROVAL_REASON_MAX - 60"
      show-word-limit
      :placeholder="selected === '其他' ? '请写明原因（必填）' : '可选：补充说明'"
      data-testid="self-approval-reason-extra"
    />
  </div>
</template>

<style scoped>
.self-approval-field {
  margin-top: 12px;
}
.self-approval-field__alert {
  margin-bottom: 10px;
}
.self-approval-field__label {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
}
.self-approval-field__required {
  color: #f56c6c;
  margin-left: 2px;
}
</style>
