<script setup lang="ts">
/**
 * 工厂审批规则 (纪律排查 B1–B3 / B11 / B15): 「允许本人审批」开关 + 改价审批阈值。
 *
 * 读写同一份 factory_settings (canvas-factory-config/settings, 部分更新 + version 乐观锁),
 * 平台配置中心的「工厂设置」面板改的也是这几列。后端只许工厂超管 / 权限管理员改。
 */
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { getSettings, updateSettings } from '@/api/factoryConfigHub';
import { handleCatchError } from '@/utils/errorToast';

const props = defineProps<{ factoryId: string }>();

const loading = ref(false);
const saving = ref(false);
const form = reactive({
  allowSelfApproval: false,
  priceDecreaseApprovalPct: 10,
  priceIncreaseApprovalPct: 20,
  version: 0,
});

async function load() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const res = await getSettings(props.factoryId);
    if (res.success && res.data) {
      form.allowSelfApproval = res.data.allowSelfApproval === true;
      form.priceDecreaseApprovalPct = Number(res.data.priceDecreaseApprovalPct ?? 10);
      form.priceIncreaseApprovalPct = Number(res.data.priceIncreaseApprovalPct ?? 20);
      form.version = res.data.version ?? 0;
    }
  } catch (e) {
    handleCatchError(e, '读取审批规则失败');
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (form.allowSelfApproval) {
    try {
      await ElMessageBox.confirm(
        '开启后，报工撤回、盘点差异、不合格品处置/特批、改价、退款都可以由提交人自己审批（每次必须写原因，审批记录标注为本人审批）。只有一个人能审批的小厂才建议开启。',
        '确认允许本人审批？',
        { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
  }
  saving.value = true;
  try {
    const res = await updateSettings(props.factoryId, {
      version: form.version,
      allowSelfApproval: form.allowSelfApproval,
      priceDecreaseApprovalPct: form.priceDecreaseApprovalPct,
      priceIncreaseApprovalPct: form.priceIncreaseApprovalPct,
    });
    if (res.success) {
      ElMessage.success('审批规则已保存');
      await load();
    }
  } catch (e) {
    handleCatchError(e, '保存审批规则失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="approval-policy" data-testid="approval-policy-settings">
    <el-form label-width="140px">
      <el-form-item label="允许本人审批">
        <div class="approval-policy__row">
          <el-switch v-model="form.allowSelfApproval" data-testid="allow-self-approval-switch" />
          <el-text type="info" size="small">
            关闭（默认）：审批人不能是提交人（四眼）。开启：报工撤回、盘点差异、不合格品处置/特批、改价、退款可由提交人自己审批，每次必须写原因并留痕。
          </el-text>
        </div>
      </el-form-item>
      <el-form-item label="改价超过多少要审批">
        <div class="approval-policy__row">
          <span>降价超过</span>
          <el-input-number v-model="form.priceDecreaseApprovalPct" :min="0.01" :max="999.99" :precision="2" :step="1"
            controls-position="right" style="width: 130px" data-testid="price-decrease-pct" />
          <span>% 或 涨价超过</span>
          <el-input-number v-model="form.priceIncreaseApprovalPct" :min="0.01" :max="999.99" :precision="2" :step="1"
            controls-position="right" style="width: 130px" data-testid="price-increase-pct" />
          <span>%（默认 10% / 20%）</span>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" data-testid="approval-policy-save" @click="save">保存审批规则</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.approval-policy__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  line-height: 1.5;
}
</style>
