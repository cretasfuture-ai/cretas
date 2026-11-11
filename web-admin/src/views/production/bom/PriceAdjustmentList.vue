<script setup lang="ts">
/**
 * BOM 调价建议 (D-S5-09, 2026-11-11): 采购价变动后系统为可改的配方版本生成的调价建议,
 * 这里看列表 / 详情, 批准 (把配方行价改成建议价) 或驳回 (必须填原因)。
 *
 * 谁能看、谁能批按权限配置, 不按岗位名:
 *   - 批准 / 驳回按钮: 有 approval_bom_price_adjustment 审批权才显示
 *     (系统设置 → 审批规则 → 谁能审批 里按工厂改; 后端同一权限码再拦一次)。
 *   - 价格字段看不到采购价的账号由后端返回 null, 这里显示「—」。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  approveBomPriceProposal,
  getBomPriceProposal,
  listBomPriceProposals,
  rejectBomPriceProposal,
  type BomPriceProposal,
  type BomPriceProposalStatus,
} from '@/api/bomPriceAdjustment';

const APPROVAL_CODE = 'approval_bom_price_adjustment';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const currentUserId = computed(() => authStore.user?.id);
const canApprove = computed(() => permissionStore.hasFullAccess(APPROVAL_CODE));

const status = ref<BomPriceProposalStatus | ''>('PENDING');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const rows = ref<BomPriceProposal[]>([]);
const loading = ref(false);
const loadError = ref('');

const STATUS_LABEL: Record<BomPriceProposalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
};
const STATUS_TAG: Record<BomPriceProposalStatus, 'warning' | 'success' | 'info'> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'info',
};
const SOURCE_LABEL: Record<string, string> = {
  PURCHASE_RECEIVE: '采购收货',
  MANUAL_CHECK: '手动核价',
};

function money(value?: number | null): string {
  return value === null || value === undefined ? '—' : `¥${Number(value).toFixed(4)}`;
}
function percent(value?: number | null): string {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}%`;
}
function subject(row: BomPriceProposal): string {
  const material = row.materialName || row.materialTypeId;
  const recipe = [row.productName, row.recipeCode].filter(Boolean).join(' ');
  return recipe ? `${material}（${recipe}）` : material;
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await listBomPriceProposals(factoryId.value, {
      status: status.value || undefined,
      page: page.value - 1,
      size: size.value,
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      total.value = res.data.totalElements;
    } else {
      rows.value = [];
      total.value = 0;
      loadError.value = res.message || '加载调价建议失败';
    }
  } catch (e) {
    rows.value = [];
    total.value = 0;
    const err = e as { message?: string } | undefined;
    loadError.value = err?.message || '加载调价建议失败';
  } finally {
    loading.value = false;
  }
}

watch(status, () => {
  page.value = 1;
  loadData();
});
onMounted(loadData);

// ---------------------------------------------------------------- 详情
const detailOpen = ref(false);
const detail = ref<BomPriceProposal | null>(null);
async function openDetail(row: BomPriceProposal) {
  detail.value = row;
  detailOpen.value = true;
  try {
    const res = await getBomPriceProposal(factoryId.value, row.id);
    if (res.success && res.data) detail.value = res.data;
  } catch {
    // 拦截器已提示; 抽屉里保留列表行数据
  }
}

// ---------------------------------------------------------------- 批准
/**
 * 看不到建议价 (采购价对本账号脱敏) 就不能批准: 既不知道批的是多少, 也传不出 expectedUnitPrice,
 * 后端就比不出「看完之后建议被新收货改过」—— 那是盲批 (独立审查 2026-11-11)。驳回不受影响。
 */
function priceVisible(row: BomPriceProposal): boolean {
  return row.proposedUnitPrice !== null && row.proposedUnitPrice !== undefined;
}

async function handleApprove(row: BomPriceProposal) {
  if (!canApprove.value || row.status !== 'PENDING' || !currentUserId.value || !priceVisible(row)) return;
  try {
    await ElMessageBox.confirm(
      `把配方行单价从 ${money(row.currentUnitPrice)} 改为 ${money(row.proposedUnitPrice)}（${percent(row.deltaPercent)}），`
        + `影响 ${row.affectedProductCount} 个产品的标准成本。`,
      `批准调价 — ${subject(row)}`,
      { type: 'warning', confirmButtonText: '确认批准', cancelButtonText: '取消' },
    );
    await approveBomPriceProposal(factoryId.value, row.id, {
      approverId: currentUserId.value,
      // 传审批人看到的建议价: 看完之后建议被新收货更新过时后端 409, 不会悄悄批成新价
      expectedUnitPrice: row.proposedUnitPrice,
    });
    ElMessage.success('已批准，配方行单价已更新');
    detailOpen.value = false;
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage({ message: '批准失败', type: 'error', duration: 0, showClose: true });
    }
    if (err?.status === 409) loadData();
  }
}

// ---------------------------------------------------------------- 驳回 (原因必填, 标准原因 + 其他)
const REJECT_REASONS = [
  '供应商临时涨价，预计下批回落',
  '收货单价录入有误',
  '暂不调整配方标准成本',
  '其他',
] as const;
const rejectOpen = ref(false);
const rejectTarget = ref<BomPriceProposal | null>(null);
const rejectChoice = ref('');
const rejectOther = ref('');
const rejecting = ref(false);
const rejectReason = computed(() =>
  rejectChoice.value === '其他' ? rejectOther.value.trim() : rejectChoice.value,
);
const rejectInvalid = computed(() => !rejectReason.value || rejectReason.value.length > 500);

function openReject(row: BomPriceProposal) {
  if (!canApprove.value || row.status !== 'PENDING') return;
  rejectTarget.value = row;
  rejectChoice.value = '';
  rejectOther.value = '';
  rejectOpen.value = true;
}

async function submitReject() {
  const row = rejectTarget.value;
  if (!row || rejectInvalid.value) return;
  rejecting.value = true;
  try {
    await rejectBomPriceProposal(factoryId.value, row.id, rejectReason.value);
    ElMessage.success('已驳回，配方行单价未改动');
    rejectOpen.value = false;
    detailOpen.value = false;
    loadData();
  } catch (e) {
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && err.status !== 400 && !err.actionHint)) {
      ElMessage({ message: '驳回失败', type: 'error', duration: 0, showClose: true });
    }
    if (err?.status === 409) {
      rejectOpen.value = false;
      loadData();
    }
  } finally {
    rejecting.value = false;
  }
}
</script>

<template>
  <div class="page-wrapper">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>BOM 调价建议</span>
          <div class="toolbar">
            <el-radio-group v-model="status" size="small" data-testid="bpa-status-filter">
              <el-radio-button value="PENDING">待审批</el-radio-button>
              <el-radio-button value="APPROVED">已批准</el-radio-button>
              <el-radio-button value="REJECTED">已驳回</el-radio-button>
              <el-radio-button value="">全部</el-radio-button>
            </el-radio-group>
            <el-button :icon="Refresh" size="small" @click="loadData">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="!canApprove"
        type="info"
        :closable="false"
        show-icon
        class="hint"
        data-testid="bpa-no-approve-hint"
        title="你可以查看调价建议，但没有批准 / 驳回权限。需要审批请联系管理员在「系统设置 → 审批规则 → 谁能审批」开通「BOM调价审批」。"
      />
      <el-alert v-if="loadError" type="error" :closable="false" show-icon class="hint" :title="loadError" />

      <el-table v-loading="loading" :data="rows" stripe data-testid="bpa-table" empty-text="没有符合条件的调价建议">
        <el-table-column label="物料" min-width="150">
          <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
        </el-table-column>
        <el-table-column label="配方 / 产品" min-width="180">
          <template #default="{ row }">
            <div>{{ row.productName || row.productTypeId || '—' }}</div>
            <div class="sub">{{ row.recipeCode || row.recipeId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="当前单价" width="110" align="right">
          <template #default="{ row }">{{ money(row.currentUnitPrice) }}</template>
        </el-table-column>
        <el-table-column label="建议单价" width="110" align="right">
          <template #default="{ row }">{{ money(row.proposedUnitPrice) }}</template>
        </el-table-column>
        <el-table-column label="变化" width="90" align="right">
          <template #default="{ row }">{{ percent(row.deltaPercent) }}</template>
        </el-table-column>
        <el-table-column label="影响产品" prop="affectedProductCount" width="90" align="center" />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ SOURCE_LABEL[row.sourceType] || row.sourceType }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status as BomPriceProposalStatus]" size="small">
              {{ STATUS_LABEL[row.status as BomPriceProposalStatus] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成时间" prop="createdAt" width="160" />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <template v-if="canApprove && row.status === 'PENDING'">
              <el-tooltip :disabled="priceVisible(row)" content="你看不到采购价，无法核对建议价，不能批准；可驳回或请能看到采购价的人审批" placement="top">
                <span>
                  <el-button link type="success" size="small" data-testid="bpa-approve" :disabled="!priceVisible(row)" @click="handleApprove(row)">批准</el-button>
                </span>
              </el-tooltip>
              <el-button link type="danger" size="small" data-testid="bpa-reject" @click="openReject(row)">驳回</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="loadData"
      />
    </el-card>

    <el-drawer v-model="detailOpen" :title="detail ? `调价建议 — ${subject(detail)}` : '调价建议'" size="440px">
      <el-descriptions v-if="detail" :column="1" border data-testid="bpa-detail">
        <el-descriptions-item label="状态">{{ STATUS_LABEL[detail.status] || detail.status }}</el-descriptions-item>
        <el-descriptions-item label="物料">{{ detail.materialName || detail.materialTypeId }}</el-descriptions-item>
        <el-descriptions-item label="产品">{{ detail.productName || detail.productTypeId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="配方编码">{{ detail.recipeCode || detail.recipeId }}</el-descriptions-item>
        <el-descriptions-item label="当前单价">{{ money(detail.currentUnitPrice) }}</el-descriptions-item>
        <el-descriptions-item label="建议单价">{{ money(detail.proposedUnitPrice) }}</el-descriptions-item>
        <el-descriptions-item label="单价变化">{{ money(detail.deltaAmount) }}（{{ percent(detail.deltaPercent) }}）</el-descriptions-item>
        <el-descriptions-item label="影响产品数">{{ detail.affectedProductCount }}</el-descriptions-item>
        <el-descriptions-item label="来源">
          {{ SOURCE_LABEL[detail.sourceType] || detail.sourceType }}
          <span v-if="detail.sourceReceiveRecordId" class="sub">（收货单 {{ detail.sourceReceiveRecordId }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="生成时间">{{ detail.createdAt || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.status !== 'PENDING'" :label="detail.status === 'REJECTED' ? '驳回时间' : '批准时间'">
          {{ detail.approvedAt || '—' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.status !== 'PENDING'" :label="detail.status === 'REJECTED' ? '驳回原因' : '审批意见'">
          <span data-testid="bpa-detail-comment">{{ detail.approvalComment || '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="detail && canApprove && detail.status === 'PENDING'" class="drawer-actions">
        <el-button type="success" :disabled="!priceVisible(detail)" @click="handleApprove(detail)">批准</el-button>
        <el-button type="danger" @click="openReject(detail)">驳回</el-button>
      </div>
    </el-drawer>

    <el-dialog
      v-model="rejectOpen"
      :title="rejectTarget ? `驳回调价 — ${subject(rejectTarget)}` : '驳回调价'"
      width="480px"
      data-testid="bpa-reject-dialog"
    >
      <p v-if="rejectTarget" class="reject-context">
        建议把单价从 {{ money(rejectTarget.currentUnitPrice) }} 改为 {{ money(rejectTarget.proposedUnitPrice) }}。
        驳回后配方行单价保持不变。
      </p>
      <el-form label-position="top">
        <el-form-item label="驳回原因（必填）" required>
          <el-select v-model="rejectChoice" placeholder="请选择驳回原因" style="width: 100%" data-testid="bpa-reject-reason">
            <el-option v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectChoice === '其他'" label="具体原因（必填）" required>
          <el-input
            v-model="rejectOther"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请写明为什么不调价"
            data-testid="bpa-reject-other"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectOpen = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="rejectInvalid"
          :loading="rejecting"
          data-testid="bpa-reject-submit"
          @click="submitReject"
        >确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.hint {
  margin-bottom: 12px;
}
.sub {
  color: #909399;
  font-size: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.drawer-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
.reject-context {
  margin: 0 0 12px;
  color: #606266;
}
</style>
