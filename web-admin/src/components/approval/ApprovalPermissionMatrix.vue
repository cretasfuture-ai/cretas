<script setup lang="ts">
/**
 * 本厂「谁能审批」配置 (纪律排查 B1–B3 / B11 / B15, Steve 2026-11-10「审批人按权限」)。
 *
 * 每类审批一个专门的权限码, 默认授给改动前写死的那些岗位 (改价 / 退款默认跟财务 / 销售读写权限走)。
 * 这里按岗位改「授给 / 不授给 / 恢复默认」, 写的是本厂的权限覆盖, 只影响本厂。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import {
  getApprovalPermissions,
  setApprovalGrant,
  type ApprovalGrantCell,
  type ApprovalPermissionsView,
} from '@/api/approvalPermissions';

const props = defineProps<{ factoryId: string }>();

const loading = ref(false);
const saving = ref('');
const view = ref<ApprovalPermissionsView | null>(null);

/** 一线岗位: 授给它们审批权时多问一句 (防呆)。 */
const FRONTLINE = new Set([
  'operator', 'yield_operator', 'quality_inspector', 'team_leader', 'group_leader',
  'workshop_supervisor', 'warehouse_worker', 'production_clerk',
]);

const SOURCE_TEXT: Record<string, string> = {
  FACTORY: '本厂设置',
  PLATFORM: '平台默认',
  DEFAULT: '系统默认',
  ALWAYS: '超管恒有',
};

const rows = computed(() => view.value?.roles ?? []);

async function load() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    view.value = await getApprovalPermissions(props.factoryId);
  } catch (e) {
    handleCatchError(e, '读取审批权限失败');
  } finally {
    loading.value = false;
  }
}

function cell(approval: string, role: string): ApprovalGrantCell | undefined {
  return view.value?.grants?.[approval]?.[role];
}

/** 下拉当前值: 本厂改过显示「授给 / 不授给」, 否则「默认」。 */
function selectValue(approval: string, role: string): string {
  const c = cell(approval, role);
  if (!c || c.source !== 'FACTORY') return 'default';
  return c.granted ? 'grant' : 'deny';
}

async function change(approval: string, approvalLabel: string, role: string, roleLabel: string, value: string) {
  if (value === 'grant' && FRONTLINE.has(role)) {
    try {
      await ElMessageBox.confirm(
        `「${roleLabel}」是一线岗位，确认要让他们可以做「${approvalLabel}」吗？`,
        '确认授给一线岗位',
        { type: 'warning', confirmButtonText: '确认授给', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
  }
  const key = `${approval}:${role}`;
  saving.value = key;
  try {
    await setApprovalGrant(props.factoryId, role, approval, value === 'default' ? null : value === 'grant');
    ElMessage.success(`已保存：${roleLabel} · ${approvalLabel}`);
    await load();
  } catch (e) {
    handleCatchError(e, '保存审批权限失败');
  } finally {
    saving.value = '';
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="approval-permission-matrix" data-testid="approval-permission-matrix">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="每类审批可以授给哪些岗位。「默认」= 系统原来的名单；改成「授给 / 不授给」只影响本厂。"
      class="approval-permission-matrix__tip"
    />
    <el-table v-if="view" :data="rows" border size="small" max-height="520">
      <el-table-column label="岗位" prop="label" width="140" fixed />
      <el-table-column v-for="a in view.approvals" :key="a.code" :label="a.label" min-width="150">
        <template #default="{ row }">
          <div class="approval-permission-matrix__cell">
            <el-tag v-if="cell(a.code, row.code)?.source === 'ALWAYS'" type="success" size="small">超管恒有</el-tag>
            <template v-else>
              <el-select
                :model-value="selectValue(a.code, row.code)"
                size="small"
                style="width: 100px"
                :loading="saving === `${a.code}:${row.code}`"
                :data-testid="`approval-grant-${a.code}-${row.code}`"
                @change="(v: string) => change(a.code, a.label, row.code, row.label, v)"
              >
                <el-option label="默认" value="default" />
                <el-option label="授给" value="grant" />
                <el-option label="不授给" value="deny" />
              </el-select>
              <el-tag
                :type="cell(a.code, row.code)?.granted ? 'success' : 'info'"
                size="small"
                :title="SOURCE_TEXT[cell(a.code, row.code)?.source || 'DEFAULT']"
              >{{ cell(a.code, row.code)?.granted ? '可审批' : '不可' }}</el-tag>
            </template>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.approval-permission-matrix__tip {
  margin-bottom: 10px;
}
.approval-permission-matrix__cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
