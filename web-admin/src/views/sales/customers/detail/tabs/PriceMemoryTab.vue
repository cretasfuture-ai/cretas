<!--
  客户档案「价格」页 (客户价目表最小闭环, 2026-11-14 DECISIONS 十九):
  列出并维护该客户的专属售价。这里维护的价, 就是销售开单选客户+产品时自动带出的价
  (来源标「客户专属价」, 订单行可回查)。采购价目不在此页。价格列无权限时后端置 null, 显示「—」。
-->
<template>
  <div class="price-tab">
    <div class="toolbar">
      <span class="title">客户专属价目</span>
      <span class="hint">开单选这个客户和对应产品时，会自动带出下面的价，并标注来源「客户专属价」。</span>
      <el-button v-if="canEdit" type="primary" @click="openAdd">新增专属价</el-button>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="4" animated />
    <div v-else-if="state === 'error'">
      <el-result icon="error" :title="errorMsg">
        <template #extra><el-button type="primary" @click="fetchList">重试</el-button></template>
      </el-result>
    </div>
    <el-empty v-else-if="rows.length === 0" description="该客户还没有专属价，开单时按统一价或最近成交价" :image-size="80" />
    <el-table v-else :data="rows" border stripe size="small" data-testid="customer-price-table">
      <el-table-column label="产品" prop="itemName" min-width="200" show-overflow-tooltip />
      <el-table-column label="单位" prop="unit" width="80" />
      <el-table-column label="未税单价" width="130" align="right">
        <template #default="{ row }">{{ row.standardPrice == null ? '—' : Number(row.standardPrice).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.effective ? 'success' : 'info'" size="small">{{ row.effective ? '生效中' : '未生效' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效日期" prop="effectiveFrom" width="120" />
      <el-table-column v-if="canEdit" label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">改价</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '修改专属价' : '新增专属价'" width="440px" append-to-body>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="产品">
          <el-select
            v-model="form.productTypeId"
            filterable
            :disabled="editing"
            placeholder="选择成品"
            style="width: 100%"
            data-testid="price-product-select"
          >
            <el-option v-for="p in productOptions" :key="p.id" :label="p.label" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="未税单价">
          <el-input-number v-model="form.standardPrice" :min="0.0001" :precision="4" :controls="false" style="width: 100%" data-testid="price-input" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!form.productTypeId || !(Number(form.standardPrice) > 0)" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import {
  listCustomerPrices,
  saveCustomerPrice,
  deleteCustomerPrice,
  type CustomerPriceItem,
} from '@/api/customerPriceList';
import { isSalesOrderProduct, type SalesProductOption } from '@/views/sales/orders/salesOrderProductSearch';

const props = defineProps<{ customerId: string }>();
const { factoryId } = storeToRefs(useAuthStore());
const permissionStore = usePermissionStore();
const canEdit = computed(() => permissionStore.canWrite('sales'));

const state = ref<'loading' | 'ready' | 'error'>('loading');
const errorMsg = ref('');
const rows = ref<CustomerPriceItem[]>([]);
const productOptions = ref<{ id: string; label: string }[]>([]);
const dialogVisible = ref(false);
const editing = ref(false);
const saving = ref(false);
const form = ref<{ productTypeId: string; standardPrice: number | null }>({ productTypeId: '', standardPrice: null });

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    rows.value = await listCustomerPrices(factoryId.value, props.customerId);
    state.value = 'ready';
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败';
    state.value = 'error';
  }
}

async function loadProducts() {
  if (productOptions.value.length || !factoryId.value) return;
  const res = await get<SalesProductOption[]>(`/${factoryId.value}/product-types/sellable`);
  const list = res.success && Array.isArray(res.data) ? res.data : [];
  productOptions.value = list
    .filter(isSalesOrderProduct)
    .map((p) => ({ id: String(p.id), label: String(p.name || p.productName || p.id) }));
}

async function openAdd() {
  editing.value = false;
  form.value = { productTypeId: '', standardPrice: null };
  dialogVisible.value = true;
  await loadProducts();
}

async function openEdit(row: CustomerPriceItem) {
  editing.value = true;
  form.value = { productTypeId: row.productTypeId, standardPrice: Number(row.standardPrice ?? 0) };
  if (!productOptions.value.some((p) => p.id === row.productTypeId)) {
    productOptions.value.push({ id: row.productTypeId, label: row.itemName || row.productTypeId });
  }
  dialogVisible.value = true;
}

async function save() {
  if (!factoryId.value) return;
  saving.value = true;
  try {
    await saveCustomerPrice(factoryId.value, props.customerId, {
      productTypeId: form.value.productTypeId,
      standardPrice: Number(form.value.standardPrice),
    });
    ElMessage.success('已保存，开单将按此价带出');
    dialogVisible.value = false;
    await fetchList();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function remove(row: CustomerPriceItem) {
  try {
    await ElMessageBox.confirm(`删除「${row.itemName || row.productTypeId}」的专属价？删除后开单按统一价或最近成交价。`, '删除专属价', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    await deleteCustomerPrice(factoryId.value as string, props.customerId, row.itemId);
    ElMessage.success('已删除');
    await fetchList();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

onMounted(fetchList);
</script>

<style scoped>
.price-tab { padding: 12px 0; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.title { font-size: 15px; font-weight: 600; }
.hint { flex: 1; color: var(--cs-muted); font-size: 12px; }
</style>
