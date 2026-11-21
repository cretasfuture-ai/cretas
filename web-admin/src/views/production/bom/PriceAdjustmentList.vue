<script setup lang="ts">
import { CompatDescriptions, CompatDescriptionsItem, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

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
      { type: 'warning', confirmButtonText: t('production.bom_priceadjustmentlist.confirmbuttontext_38034be43042'), cancelButtonText: t('production.bom_priceadjustmentlist.cancelbuttontext_2cd0f3be8738') },
    );
    await approveBomPriceProposal(factoryId.value, row.id, {
      approverId: currentUserId.value,
      // 传审批人看到的建议价: 看完之后建议被新收货更新过时后端 409, 不会悄悄批成新价
      expectedUnitPrice: row.proposedUnitPrice,
    });
    ElMessage.success(t('production.bom_priceadjustmentlist.message_46b0ca7c8729'));
    detailOpen.value = false;
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage({ message: t('production.bom_priceadjustmentlist.message_c598ebcb5f6e'), type: 'error', duration: 0, showClose: true });
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
    ElMessage.success(t('production.bom_priceadjustmentlist.message_5ab92e90127b'));
    rejectOpen.value = false;
    detailOpen.value = false;
    loadData();
  } catch (e) {
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && err.status !== 400 && !err.actionHint)) {
      ElMessage({ message: t('production.bom_priceadjustmentlist.message_188fc644b59d'), type: 'error', duration: 0, showClose: true });
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
    <UCard>
      <template #header>
        <div class="card-header">
          <span>{{ t('production.bom_priceadjustmentlist.text_83e567748167') }}</span>
          <div class="toolbar">
            <el-radio-group v-model="status" size="small" data-testid="bpa-status-filter">
              <el-radio-button value="PENDING">{{ t('production.bom_priceadjustmentlist.text_dcb49342a5e5') }}</el-radio-button>
              <el-radio-button value="APPROVED">{{ t('production.bom_priceadjustmentlist.text_b42cb548c1d4') }}</el-radio-button>
              <el-radio-button value="REJECTED">{{ t('production.bom_priceadjustmentlist.text_4dbcdec8693b') }}</el-radio-button>
              <el-radio-button value="">{{ t('production.bom_priceadjustmentlist.text_5c55a67935af') }}</el-radio-button>
            </el-radio-group>
            <el-button :icon="Refresh" size="small" @click="loadData">{{ t('production.bom_priceadjustmentlist.action_aee887434131') }}</el-button>
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
        :title="t('production.bom_priceadjustmentlist.title_99ffddaf91d7')"
      />
      <el-alert v-if="loadError" type="error" :closable="false" show-icon class="hint" :title="loadError" />

      <CompatTable v-loading="loading" :data="rows" stripe data-testid="bpa-table" :empty-text="t('production.bom_priceadjustmentlist.empty_text_ab1ccacff015')">
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_6446454e8e36')" min-width="150">
          <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_80f994d0432d')" min-width="180">
          <template #default="{ row }">
            <div>{{ row.productName || row.productTypeId || '—' }}</div>
            <div class="sub">{{ row.recipeCode || row.recipeId }}</div>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_f7615229414c')" width="110" align="right">
          <template #default="{ row }">{{ money(row.currentUnitPrice) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_3a782343c11d')" width="110" align="right">
          <template #default="{ row }">{{ money(row.proposedUnitPrice) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_e329c44530ee')" width="90" align="right">
          <template #default="{ row }">{{ percent(row.deltaPercent) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_d2d0473b7bcc')" prop="affectedProductCount" width="90" align="center" />
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_a488e93d69cc')" width="100">
          <template #default="{ row }">{{ SOURCE_LABEL[row.sourceType] || row.sourceType }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_6320b4a8722a')" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status as BomPriceProposalStatus]" size="small">
              {{ STATUS_LABEL[row.status as BomPriceProposalStatus] || row.status }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_5c04175c4536')" prop="createdAt" width="160" />
        <CompatTableColumn :label="t('production.bom_priceadjustmentlist.label_ed31fbb483ee')" width="190" fixed="right">
          <template #default="{ row }">
            <UButton variant="link" color="primary" size="sm" @click="openDetail(row)">{{ t('production.bom_priceadjustmentlist.action_979a332955c8') }}</UButton>
            <template v-if="canApprove && row.status === 'PENDING'">
              <el-tooltip :disabled="priceVisible(row)" content="你看不到采购价，无法核对建议价，不能批准；可驳回或请能看到采购价的人审批" placement="top">
                <span>
                  <UButton variant="link" color="success" size="sm" data-testid="bpa-approve" :disabled="!priceVisible(row)" @click="handleApprove(row)">{{ t('production.bom_priceadjustmentlist.action_8cbe697b1573') }}</UButton>
                </span>
              </el-tooltip>
              <UButton variant="link" color="error" size="sm" data-testid="bpa-reject" @click="openReject(row)">{{ t('production.bom_priceadjustmentlist.action_089e6ed3f55f') }}</UButton>
            </template>
          </template>
        </CompatTableColumn>
      </CompatTable>

      <CompatPagination
        v-if="total > size"
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="loadData"
      />
    </UCard>

    <el-drawer v-model="detailOpen" :title="detail ? `调价建议 — ${subject(detail)}` : t('production.bom_priceadjustmentlist.title_6a74e40610b0')" size="440px">
      <CompatDescriptions v-if="detail" :column="1" border data-testid="bpa-detail">
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_6320b4a8722a')">{{ STATUS_LABEL[detail.status] || detail.status }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_6446454e8e36')">{{ detail.materialName || detail.materialTypeId }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_aea82737cc01')">{{ detail.productName || detail.productTypeId || '—' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_1096ac40b90b')">{{ detail.recipeCode || detail.recipeId }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_f7615229414c')">{{ money(detail.currentUnitPrice) }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_3a782343c11d')">{{ money(detail.proposedUnitPrice) }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_dc2f4c3987cc')">{{ money(detail.deltaAmount) }}（{{ percent(detail.deltaPercent) }}）</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_6b363b26e78a')">{{ detail.affectedProductCount }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_a488e93d69cc')">
          {{ SOURCE_LABEL[detail.sourceType] || detail.sourceType }}
          <span v-if="detail.sourceReceiveRecordId" class="sub">{{ t('production.bom_priceadjustmentlist.text_fb1a056140a6') }} {{ detail.sourceReceiveRecordId }}）</span>
        </CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.bom_priceadjustmentlist.label_5c04175c4536')">{{ detail.createdAt || '—' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem v-if="detail.status !== 'PENDING'" :label="detail.status === 'REJECTED' ? t('production.bom_priceadjustmentlist.label_58e5f79022ef') : t('production.bom_priceadjustmentlist.label_48dc49554691')">
          {{ detail.approvedAt || '—' }}
        </CompatDescriptionsItem>
        <CompatDescriptionsItem v-if="detail.status !== 'PENDING'" :label="detail.status === 'REJECTED' ? t('production.bom_priceadjustmentlist.label_1245e49f52f3') : t('production.bom_priceadjustmentlist.label_97d012369eab')">
          <span data-testid="bpa-detail-comment">{{ detail.approvalComment || '—' }}</span>
        </CompatDescriptionsItem>
      </CompatDescriptions>
      <div v-if="detail && canApprove && detail.status === 'PENDING'" class="drawer-actions">
        <UButton color="success" :disabled="!priceVisible(detail)" @click="handleApprove(detail)">{{ t('production.bom_priceadjustmentlist.action_8cbe697b1573') }}</UButton>
        <UButton color="error" @click="openReject(detail)">{{ t('production.bom_priceadjustmentlist.action_089e6ed3f55f') }}</UButton>
      </div>
    </el-drawer>

    <CompatDialog
      v-model="rejectOpen"
      :title="rejectTarget ? `驳回调价 — ${subject(rejectTarget)}` : t('production.bom_priceadjustmentlist.title_9270b869888d')"
      width="480px"
      data-testid="bpa-reject-dialog"
    >
      <p v-if="rejectTarget" class="reject-context">
        {{ t('production.bom_priceadjustmentlist.text_ff46e1c4d4ea') }} {{ money(rejectTarget.currentUnitPrice) }} {{ t('production.bom_priceadjustmentlist.text_87a354cd7d2f') }} {{ money(rejectTarget.proposedUnitPrice) }}{{ t('production.bom_priceadjustmentlist.text_9f1ee58f0049') }}
      </p>
      <CompatForm label-position="top">
        <CompatFormItem :label="t('production.bom_priceadjustmentlist.label_36a933aee61f')" required>
          <CompatSelect v-model="rejectChoice" :placeholder="t('production.bom_priceadjustmentlist.placeholder_d35fe2412948')" style="width: 100%" data-testid="bpa-reject-reason">
            <CompatOption v-for="r in REJECT_REASONS" :key="r" :label="r" :value="r" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem v-if="rejectChoice === '其他'" :label="t('production.bom_priceadjustmentlist.label_f52e08403996')" required>
          <el-input
            v-model="rejectOther"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="t('production.bom_priceadjustmentlist.placeholder_1f81dfa3d4f6')"
            data-testid="bpa-reject-other"
          />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="rejectOpen = false">{{ t('production.bom_priceadjustmentlist.action_2cd0f3be8738') }}</UButton>
        <UButton
          color="error"
          :disabled="rejectInvalid"
          :loading="rejecting"
          data-testid="bpa-reject-submit"
          @click="submitReject"
        >{{ t('production.bom_priceadjustmentlist.action_c68e99e0908b') }}</UButton>
      </template>
    </CompatDialog>
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
  color: var(--cs-muted);
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
  color: var(--cs-muted);
}
</style>
