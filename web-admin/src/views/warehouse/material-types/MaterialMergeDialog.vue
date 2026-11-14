<script setup lang="ts">
/**
 * 合并重复物料弹窗 (仅写权限角色可打开, 门禁在 list.vue)
 *
 * 防呆: Rule 1 选定主物料立刻查 merge-check, 把「会发生什么 / 哪里拦住了」摆在提交前;
 *       Rule 2 标题带被合并物料的名称与料号; Rule 3 原因用标准下拉, 选「其他」才必填文本;
 *       Rule 5 拦截项给出去处链接 (路由存在才给), 没有路由就只显示 hint。
 * 拦截不是自动修: 库存/配方/工艺图/供应商/采购单要用户去对应页面处理, 历史单据不动。
 */
import { ref, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { get } from '@/api/request';
import {
  checkMaterialMerge,
  mergeMaterial,
  type MaterialBrief,
  type MaterialMergeBlocker,
  type MaterialMergeBlockerCode,
  type MaterialMergeCheck,
  type RawMaterialTypeRow,
} from '@/api/materialAliasMerge';
import { getErrorMessage } from '@/utils/errorToast';

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  material: RawMaterialTypeRow | null;
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'merged'): void;
}>();

const router = useRouter();

const REASON_OTHER = '其他';
const REASON_OPTIONS = ['同一物料不同叫法', '同类不同品牌整合', '重复录入', REASON_OTHER];
const REASON_TEXT_MAX = 500;

/** 拦截项 → 去处 (路由取自 router/index.ts; 没有对应页面的类型不给链接, 只显示 hint) */
const BLOCKER_LINKS: Partial<Record<MaterialMergeBlockerCode, { path: string; label: string }>> = {
  STOCK: { path: '/warehouse/materials', label: '去处理库存' },
  BOM: { path: '/system/product-processes', label: '去产品工艺改配方' },
  WORKFLOW: { path: '/system/product-processes', label: '去产品工艺改工艺图' },
  SUPPLIER: { path: '/procurement/suppliers', label: '去处理供应商关系' },
  PURCHASE_ORDER: { path: '/procurement/orders', label: '去处理采购单' },
};

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
});

const title = computed(() =>
  props.material ? `合并重复物料 — ${props.material.name}（${props.material.code || '-'}）` : '合并重复物料',
);

const candidates = ref<RawMaterialTypeRow[]>([]);
const candidatesLoading = ref(false);
const masterId = ref('');
const check = ref<MaterialMergeCheck | null>(null);
const checking = ref(false);
const checkError = ref('');
const reason = ref('');
const reasonText = ref('');
const submitting = ref(false);
let checkSeq = 0;

function toBrief(row: RawMaterialTypeRow): MaterialBrief {
  return { id: row.id, code: row.code || '', name: row.name, unit: row.unit || '', mainCategory: row.category || '' };
}

const masterOptions = computed(() => {
  const self = props.material;
  if (!self) return [];
  const list = candidates.value.filter((m) => m.id !== self.id && m.isActive !== false && !m.mergedIntoId);
  // 同类优先: 同一类别的排前面, 不排除其他类别 (后端 CATEGORY 拦截会给出原因)
  return list.slice().sort((a, b) => Number(b.category === self.category) - Number(a.category === self.category));
});

async function loadCandidates(): Promise<void> {
  if (!props.factoryId) return;
  candidatesLoading.value = true;
  try {
    const res = await get<RawMaterialTypeRow[] | { content?: RawMaterialTypeRow[] }>(
      `/${props.factoryId}/raw-material-types/active`,
    );
    const data = res.data;
    candidates.value = Array.isArray(data) ? data : data?.content ?? [];
  } catch (e) {
    candidates.value = [];
    checkError.value = getErrorMessage(e, '候选物料加载失败，请稍后重试');
  } finally {
    candidatesLoading.value = false;
  }
}

function resetState(): void {
  masterId.value = '';
  check.value = null;
  checkError.value = '';
  reason.value = '';
  reasonText.value = '';
  submitting.value = false;
  checkSeq += 1;
}

watch(
  () => [props.modelValue, props.material?.id] as const,
  ([open]) => {
    if (open) {
      resetState();
      void loadCandidates();
    }
  },
  { immediate: true },
);

async function onMasterChange(id: string): Promise<void> {
  check.value = null;
  checkError.value = '';
  if (!id || !props.material) return;
  const seq = ++checkSeq;
  checking.value = true;
  try {
    const res = await checkMaterialMerge(props.factoryId, props.material.id, id);
    if (seq !== checkSeq) return;
    check.value = res.data;
  } catch (e) {
    if (seq !== checkSeq) return;
    checkError.value = getErrorMessage(e, '合并检查失败，请稍后重试');
  } finally {
    if (seq === checkSeq) checking.value = false;
  }
}

const reasonValue = computed(() => (reason.value === REASON_OTHER ? reasonText.value.trim() : reason.value));
const reasonOk = computed(
  () => reason.value !== '' && (reason.value !== REASON_OTHER || reasonText.value.trim() !== ''),
);
const canConfirm = computed(
  () => !!check.value?.canMerge && masterId.value !== '' && reasonOk.value && !submitting.value && !checking.value,
);

function blockerLink(b: MaterialMergeBlocker): { path: string; label: string } | null {
  return BLOCKER_LINKS[b.code] ?? null;
}

function goTo(path: string): void {
  void router.push(path);
}

async function handleConfirm(): Promise<void> {
  if (!canConfirm.value || !props.material || !check.value) return;
  const oldName = props.material.name;
  const masterName = check.value.master.name;
  submitting.value = true;
  try {
    const result = await mergeMaterial(props.factoryId, props.material.id, masterId.value, reasonValue.value || undefined);
    ElMessage.success(`已合并，「${oldName}」已成为「${masterName}」的别名`);
    const notes = result?.data?.replaced?.notes ?? [];
    if (notes.length > 0) {
      ElMessage({ message: notes.join('；'), type: 'warning', duration: 0, showClose: true });
    }
    visible.value = false;
    emit('merged');
  } catch (e) {
    // 拦截器已弹 sticky toast; 弹窗内保留一行, 并重查一次让拦截项与现状一致
    checkError.value = getErrorMessage(e, '合并失败，请稍后重试');
    void onMasterChange(masterId.value);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="640px" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="合并后：被合并的物料会停用，它的名字变成主物料的别名；历史单据不会被改动。"
    />

    <el-form label-width="100px" class="merge-form">
      <el-form-item label="合并到">
        <el-select
          v-model="masterId"
          filterable
          clearable
          placeholder="搜索并选择要保留的主物料"
          style="width: 100%"
          :loading="candidatesLoading"
          data-test="merge-master"
          @change="onMasterChange"
        >
          <el-option
            v-for="m in masterOptions"
            :key="m.id"
            :value="m.id"
            :label="`${m.name}（${m.code || '-'}）`"
          >
            <span>{{ m.name }}</span>
            <span class="opt-meta">{{ m.code }} · {{ m.category }}</span>
          </el-option>
        </el-select>
      </el-form-item>
    </el-form>

    <div v-loading="checking" class="merge-check">
      <el-alert v-if="checkError" type="error" :closable="false" show-icon :title="checkError" data-test="merge-error" />

      <template v-if="check">
        <div v-if="check.aliasesToAdd.length" class="merge-block" data-test="merge-aliases">
          合并后主物料「{{ check.master.name }}」会新增别名：{{ check.aliasesToAdd.join('、') }}
        </div>

        <div v-if="check.replacements?.length" class="merge-block" data-test="merge-replacements">
          <div>合并时会自动换成主物料「{{ check.master.name }}」：</div>
          <div
            v-for="r in check.replacements"
            :key="r.code"
            :data-test="`replacement-${r.code}`"
          >
            {{ r.message }}：{{ r.items.join('、') }}
          </div>
        </div>

        <el-alert
          v-for="(w, i) in check.warnings"
          :key="`w${i}`"
          class="merge-warning"
          type="warning"
          :closable="false"
          show-icon
          :title="w"
          data-test="merge-warning"
        />

        <div v-if="check.blockers.length" class="merge-blockers" data-test="merge-blockers">
          <div class="blockers-title">还不能合并，请先处理下面的问题：</div>
          <div v-for="b in check.blockers" :key="b.code" class="blocker" :data-test="`blocker-${b.code}`">
            <div class="blocker-message">{{ b.message }}</div>
            <ul v-if="b.items.length" class="blocker-items">
              <li v-for="(it, i) in b.items" :key="i">{{ it }}</li>
            </ul>
            <div class="blocker-hint">{{ b.hint }}</div>
            <el-button
              v-if="blockerLink(b)"
              link
              type="primary"
              :data-test="`blocker-link-${b.code}`"
              @click="goTo(blockerLink(b)!.path)"
            >{{ blockerLink(b)!.label }}</el-button>
          </div>
        </div>
        <el-alert
          v-else-if="check.canMerge"
          type="success"
          :closable="false"
          show-icon
          title="没有拦截项，可以合并"
        />
      </template>
    </div>

    <el-form label-width="100px" class="merge-form">
      <el-form-item label="合并原因" required>
        <el-select v-model="reason" placeholder="请选择原因" style="width: 100%" data-test="merge-reason">
          <el-option v-for="r in REASON_OPTIONS" :key="r" :label="r" :value="r" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="reason === REASON_OTHER" label="补充说明" required>
        <el-input
          v-model="reasonText"
          type="textarea"
          :rows="2"
          :maxlength="REASON_TEXT_MAX"
          show-word-limit
          placeholder="请写清楚为什么合并"
          data-test="merge-reason-text"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canConfirm"
        data-test="merge-confirm"
        @click="handleConfirm"
      >确认合并</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.merge-form { margin-top: 12px; }
.opt-meta { float: right; color: var(--cs-muted); font-size: 12px; }
.merge-check { min-height: 8px; }
.merge-block { margin: 8px 0; color: var(--cs-muted); }
.merge-warning { margin: 6px 0; }
.merge-blockers { margin: 8px 0; padding: 8px 12px; border: 1px solid #fbc4c4; background: var(--cs-danger-soft); border-radius: 4px; }
.blockers-title { color: var(--cs-danger); font-weight: 600; margin-bottom: 6px; }
.blocker { padding: 6px 0; border-top: 1px dashed #fbc4c4; }
.blocker:first-of-type { border-top: 0; }
.blocker-message { color: #c45656; font-weight: 600; }
.blocker-items { margin: 4px 0; padding-left: 20px; color: var(--cs-muted); }
.blocker-hint { color: var(--cs-muted); font-size: 13px; }
</style>
