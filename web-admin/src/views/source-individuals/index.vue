<script setup lang="ts">
/**
 * 源头个体档案 —— 溯源链最前端有生命周期的个体(牛/鸭/猪/…)。
 *
 * ⚠️ 通用能力: 物种是字段值 `species`, ⛔ 页面文案里不出现任何单一物种/单一客户专属词。
 *
 * 两处刻意的「不好看但诚实」:
 *  1. 标识牌编号列旁边不做任何「唯一性」暗示 —— 它真的可以重复(脱落重挂/跨场撞号)。
 *  2. 系谱选不到就报错, ⛔ 不静默置空。后端 422 的文案原样弹给用户。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import type { FormInstance } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  DNA_STATUS_LABELS,
  createSourceIndividual,
  deleteSourceIndividual,
  listSourceIndividuals,
  updateSourceIndividual,
  type DnaVerificationStatus,
  type SourceIndividual,
  type SourceIndividualPayload,
} from '@/api/sourceIndividual';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

const loading = ref(false);
const rows = ref<SourceIndividual[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const keyword = ref('');

const drawerVisible = ref(false);
const drawerTitle = ref('登记源头个体');
const formRef = ref<FormInstance>();
const saving = ref(false);
const editingId = ref<string | null>(null);

const DNA_OPTIONS = (Object.keys(DNA_STATUS_LABELS) as DnaVerificationStatus[]).map((value) => ({
  value,
  label: DNA_STATUS_LABELS[value],
}));

const form = reactive<Required<Pick<SourceIndividualPayload, 'individualCode'>> & SourceIndividualPayload>({
  individualCode: '',
  tagNumber: '',
  species: '',
  breed: '',
  sex: '',
  birthDate: '',
  originFarm: '',
  externalGeneBankId: '',
  dnaSampleCode: '',
  dnaVerificationStatus: 'UNSAMPLED',
  sireIndividualId: '',
  damIndividualId: '',
  notes: '',
});

const rules = {
  individualCode: [{ required: true, message: '请输入档案编号', trigger: 'blur' }],
};

// ── 系谱候选 ────────────────────────────────────────────────────────────────
// 远程搜索既有个体(按档案编号 / 标识牌编号)。⛔ 候选里剔掉自己 —— 后端会因
// 「指向自己」422, 把它列出来只是在邀请用户撞一次错误。
const parentOptions = ref<SourceIndividual[]>([]);
const parentSearching = ref(false);

async function searchParents(query: string) {
  if (!factoryId.value) return;
  parentSearching.value = true;
  try {
    const res = await listSourceIndividuals(factoryId.value, { size: 20, keyword: query });
    const list = res.success && res.data ? res.data.content : [];
    parentOptions.value = list.filter((row) => row.id !== editingId.value);
  } finally {
    parentSearching.value = false;
  }
}

function parentLabel(row: SourceIndividual) {
  return row.tagNumber ? `${row.individualCode} · ${row.tagNumber}` : row.individualCode;
}

onMounted(() => {
  // `?keyword=` 带参打开 (溯源页个体抽屉的「编辑」跳过来时带的是档案编号):
  // 列表先按它过滤, 用户在行上点「编辑」。⚠️ 只在首载读一次, 之后搜索框归用户。
  const q = route?.query?.keyword;
  if (typeof q === 'string' && q.trim()) keyword.value = q.trim();
  loadData();
});
watch([page, size], loadData);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listSourceIndividuals(factoryId.value, {
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim(),
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      total.value = res.data.totalElements;
    } else {
      // ⛔ 读不到就说读不到, 不留一张空表让人以为「这个工厂还没有个体档案」。
      rows.value = [];
      total.value = 0;
      ElMessage({ message: res.message || '个体档案列表读取失败', type: 'error', showClose: true });
    }
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  page.value = 1;
  loadData();
}

function resetForm() {
  Object.assign(form, {
    individualCode: '',
    tagNumber: '',
    species: '',
    breed: '',
    sex: '',
    birthDate: '',
    originFarm: '',
    externalGeneBankId: '',
    dnaSampleCode: '',
    dnaVerificationStatus: 'UNSAMPLED' as DnaVerificationStatus,
    sireIndividualId: '',
    damIndividualId: '',
    notes: '',
  });
}

function handleCreate() {
  editingId.value = null;
  drawerTitle.value = '登记源头个体';
  resetForm();
  parentOptions.value = [];
  drawerVisible.value = true;
}

function handleEdit(row: SourceIndividual) {
  editingId.value = row.id;
  drawerTitle.value = `个体档案 · ${row.individualCode}`;
  Object.assign(form, {
    individualCode: row.individualCode,
    tagNumber: row.tagNumber || '',
    species: row.species || '',
    breed: row.breed || '',
    sex: row.sex || '',
    birthDate: row.birthDate || '',
    originFarm: row.originFarm || '',
    externalGeneBankId: row.externalGeneBankId || '',
    dnaSampleCode: row.dnaSampleCode || '',
    dnaVerificationStatus: row.dnaVerificationStatus || 'UNSAMPLED',
    sireIndividualId: row.sireIndividualId || '',
    damIndividualId: row.damIndividualId || '',
    notes: row.notes || '',
  });
  // 已选的父本/母本要在下拉里有一条能显示出来, 否则框里只剩一个空白。
  parentOptions.value = rows.value.filter(
    (candidate) =>
      candidate.id !== row.id &&
      (candidate.id === row.sireIndividualId || candidate.id === row.damIndividualId)
  );
  drawerVisible.value = true;
}

/** 空串 ⇒ undefined: 后端 partial 语义下 null/空串与「不改」是两件事。 */
function blank(value?: string | null) {
  const trimmed = String(value ?? '').trim();
  return trimmed === '' ? undefined : trimmed;
}

async function handleSubmit() {
  if (!factoryId.value || !formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const payload: SourceIndividualPayload = {
      tagNumber: blank(form.tagNumber),
      species: blank(form.species),
      breed: blank(form.breed),
      sex: blank(form.sex),
      birthDate: blank(form.birthDate),
      originFarm: blank(form.originFarm),
      externalGeneBankId: blank(form.externalGeneBankId),
      dnaSampleCode: blank(form.dnaSampleCode),
      dnaVerificationStatus: form.dnaVerificationStatus,
      sireIndividualId: blank(form.sireIndividualId),
      damIndividualId: blank(form.damIndividualId),
      notes: blank(form.notes),
    };
    const res = editingId.value
      ? await updateSourceIndividual(factoryId.value, editingId.value, payload)
      : await createSourceIndividual(factoryId.value, {
          ...payload,
          individualCode: String(form.individualCode).trim(),
        });
    if (res.success) {
      ElMessage.success(editingId.value ? '个体档案已更新' : '个体档案已建立');
      drawerVisible.value = false;
      await loadData();
    }
  } catch (error) {
    // 422 INVALID_PEDIGREE 的文案原样弹出来 —— 用户必须知道系谱**没有**存上。
    const message = String((error as Error)?.message || '个体档案保存失败');
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: SourceIndividual) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(`确认删除个体档案 ${row.individualCode}？`, '删除个体档案', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    const res = await deleteSourceIndividual(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('个体档案已删除');
      await loadData();
    }
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    const message = String((error as Error)?.message || '删除失败');
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>源头个体档案</h2>
        <p>溯源链最前端的个体（物种由「物种」字段表达，本页面不绑定任何单一物种）</p>
      </div>
      <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">
        登记个体
      </el-button>
    </div>

    <el-card shadow="never" class="content-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          clearable
          placeholder="搜索档案编号 / 标识牌编号 / 物种 / 品种 / 来源场"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>

      <!--
        列宽预算 (1366 视口, 侧栏 220 展开): 1366 - 220 - 三层 20px 内边距×2 - 竖向滚动条 ≈ 1009px。
        定宽合计 140+120+110+80+110+60+104+120 = 844, 加来源场 min 110 = 954 < 1009 —— 不出横向滚动条。
        ⚠️ 原来 1180px 在 1440 上就出滚动条, 「DNA 比对」被右侧固定的「操作」列盖得只剩一个字 ——
        技术员最关心的那一列排第 3, 别再排在最右。
        空字段整格留空, ⛔ 不渲染 '-': 「没登记」不需要一个占位符来说明自己。
      -->
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="individualCode" label="档案编号" width="140" show-overflow-tooltip />
        <el-table-column prop="tagNumber" label="标识牌编号" width="120" show-overflow-tooltip />
        <el-table-column label="DNA 比对" width="110" align="center">
          <template #default="{ row }">
            <!-- ⛔ 读不到状态整格留空, 不兜底成「未采样」——「没读到」和「没采样」是两件事 -->
            <el-tag
              v-if="row.dnaVerificationStatus"
              :type="row.dnaVerificationStatus === 'MISMATCH' ? 'danger'
                : row.dnaVerificationStatus === 'MATCHED' ? 'success' : 'info'"
              size="small"
              round
              data-testid="dna-chip"
            >
              {{ row.dnaVerificationStatusDisplayName || row.dnaVerificationStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="species" label="物种" width="80" show-overflow-tooltip />
        <el-table-column prop="breed" label="品种" width="110" show-overflow-tooltip />
        <el-table-column prop="sex" label="性别" width="60" />
        <el-table-column prop="birthDate" label="出生日期" width="104" />
        <el-table-column prop="originFarm" label="来源养殖场" min-width="110" show-overflow-tooltip />
        <el-table-column v-if="canWrite" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-sizes="[20, 50, 100]"
      />
    </el-card>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="档案编号" prop="individualCode">
          <el-input
            v-model="form.individualCode"
            maxlength="64"
            show-word-limit
            :disabled="!!editingId"
          />
          <div v-if="editingId" class="field-hint">档案编号建档后不可修改 —— 已打印的标签按它对账</div>
        </el-form-item>
        <el-form-item label="标识牌编号">
          <el-input v-model="form.tagNumber" maxlength="64" show-word-limit />
          <div class="field-hint">
            耳标 / 脚环 / 芯片号。可以重复（脱落重挂、跨场引进都会撞号），系统不强制唯一
          </div>
        </el-form-item>
        <el-form-item label="物种">
          <el-input v-model="form.species" maxlength="32" placeholder="例如 CATTLE / DUCK / PIG" />
        </el-form-item>
        <el-form-item label="品种">
          <el-input v-model="form.breed" maxlength="64" />
        </el-form-item>
        <el-form-item label="性别">
          <el-input v-model="form.sex" maxlength="16" />
        </el-form-item>
        <el-form-item label="出生日期">
          <el-date-picker
            v-model="form.birthDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
          />
        </el-form-item>
        <el-form-item label="来源养殖场">
          <el-input v-model="form.originFarm" maxlength="128" />
        </el-form-item>

        <el-divider content-position="left">系谱</el-divider>
        <el-form-item label="父本">
          <el-select
            v-model="form.sireIndividualId"
            filterable
            clearable
            remote
            reserve-keyword
            :remote-method="searchParents"
            :loading="parentSearching"
            placeholder="按档案编号 / 标识牌编号搜索"
            class="full-width"
          >
            <el-option
              v-for="option in parentOptions"
              :key="option.id"
              :label="parentLabel(option)"
              :value="option.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="母本">
          <el-select
            v-model="form.damIndividualId"
            filterable
            clearable
            remote
            reserve-keyword
            :remote-method="searchParents"
            :loading="parentSearching"
            placeholder="按档案编号 / 标识牌编号搜索"
            class="full-width"
          >
            <el-option
              v-for="option in parentOptions"
              :key="option.id"
              :label="parentLabel(option)"
              :value="option.id"
            />
          </el-select>
          <div class="field-hint">
            父本 / 母本必须是本工厂已建档的个体 —— 填一个不存在的会被拒绝，不会静默存成空的
          </div>
        </el-form-item>

        <el-divider content-position="left">DNA / 基因比对</el-divider>
        <el-form-item label="外部基因库编号">
          <el-input v-model="form.externalGeneBankId" maxlength="128" />
          <div class="field-hint">本系统只记采样与比对状态，基因数据存在外部基因库</div>
        </el-form-item>
        <el-form-item label="采样编号">
          <el-input v-model="form.dnaSampleCode" maxlength="64" />
        </el-form-item>
        <el-form-item label="比对状态">
          <el-select v-model="form.dnaVerificationStatus" class="full-width">
            <el-option
              v-for="option in DNA_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.page-container {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 20px;
    color: #1a2332;
  }

  p {
    margin: 4px 0 0;
    color: #7a8599;
    font-size: 13px;
  }
}

.content-card {
  border-radius: 10px;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 420px) auto auto;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

.field-hint {
  margin-top: 4px;
  color: #7a8599;
  font-size: 12px;
  line-height: 1.5;
}

.full-width {
  width: 100%;
}
</style>
