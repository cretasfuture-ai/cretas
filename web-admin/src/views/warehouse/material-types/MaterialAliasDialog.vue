<script setup lang="ts">
/**
 * 物料别名管理弹窗 (仅写权限角色可打开, 门禁在 list.vue)
 *
 * 配方单、供应商单据上的叫法都可以加成别名, 搜索时能直接找到这个物料。
 * 错误文案原样显示后端 message (409「已是物料「Y」的别名」等), 拦截器已弹 sticky toast,
 * 这里再在弹窗内留一行, 用户关掉 toast 后仍能看到为什么没加成。
 */
import { ref, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  listMaterialAliases,
  addMaterialAlias,
  deleteMaterialAlias,
  type MaterialAliasItem,
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
  (e: 'changed'): void;
}>();

const ALIAS_MAX_LEN = 100;

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
});

const aliases = ref<MaterialAliasItem[]>([]);
const loading = ref(false);
const adding = ref(false);
const newAlias = ref('');
const errorText = ref('');

const title = computed(() =>
  props.material ? `别名 — ${props.material.name}（${props.material.code || '-'}）` : '别名',
);
const trimmed = computed(() => newAlias.value.trim());
const tooLong = computed(() => trimmed.value.length > ALIAS_MAX_LEN);
const canAdd = computed(() => trimmed.value !== '' && !tooLong.value && !adding.value);

async function load(): Promise<void> {
  if (!props.material || !props.factoryId) return;
  loading.value = true;
  errorText.value = '';
  try {
    const res = await listMaterialAliases(props.factoryId, props.material.id);
    aliases.value = res.data || [];
  } catch (e) {
    errorText.value = getErrorMessage(e, '别名加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.modelValue, props.material?.id] as const,
  ([open]) => {
    if (open) {
      newAlias.value = '';
      aliases.value = [];
      void load();
    }
  },
  { immediate: true },
);

async function handleAdd(): Promise<void> {
  if (!props.material || !canAdd.value) return;
  adding.value = true;
  errorText.value = '';
  try {
    await addMaterialAlias(props.factoryId, props.material.id, trimmed.value);
    newAlias.value = '';
    ElMessage.success('别名已添加');
    await load();
    emit('changed');
  } catch (e) {
    errorText.value = getErrorMessage(e, '别名添加失败，请稍后重试');
  } finally {
    adding.value = false;
  }
}

async function handleDelete(item: MaterialAliasItem): Promise<void> {
  if (!props.material) return;
  errorText.value = '';
  try {
    await deleteMaterialAlias(props.factoryId, props.material.id, item.id);
    ElMessage.success('别名已删除');
    await load();
    emit('changed');
  } catch (e) {
    errorText.value = getErrorMessage(e, '别名删除失败，请稍后重试');
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="520px" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="配方单、供应商单据上的叫法都可以加成别名，搜索时能直接找到这个物料"
    />
    <el-alert
      v-if="errorText"
      class="alias-error"
      type="error"
      :closable="false"
      show-icon
      :title="errorText"
    />

    <div v-loading="loading" class="alias-list">
      <div v-if="!loading && aliases.length === 0" class="alias-empty">还没有别名</div>
      <div v-for="item in aliases" :key="item.id" class="alias-row">
        <span class="alias-text">{{ item.alias }}</span>
        <el-tag v-if="item.source === 'MERGE'" size="small" type="info">合并带入</el-tag>
        <el-button
          link
          type="danger"
          :data-test="`alias-delete-${item.id}`"
          @click="handleDelete(item)"
        >删除</el-button>
      </div>
    </div>

    <div class="alias-add">
      <el-input
        v-model="newAlias"
        placeholder="输入别名，如：生抽"
        :maxlength="ALIAS_MAX_LEN + 20"
        data-test="alias-input"
        @keyup.enter="handleAdd"
      />
      <el-button type="primary" :loading="adding" :disabled="!canAdd" data-test="alias-add" @click="handleAdd">
        添加
      </el-button>
    </div>
    <div v-if="tooLong" class="alias-too-long">别名最多 {{ ALIAS_MAX_LEN }} 个字，请缩短后再添加</div>
  </el-dialog>
</template>

<style scoped>
.alias-error { margin-top: 8px; }
.alias-list { margin: 12px 0; min-height: 48px; }
.alias-empty { color: var(--cs-muted); padding: 8px 0; }
.alias-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; border-bottom: 1px solid #f0f0f0; }
.alias-text { flex: 1; }
.alias-add { display: flex; gap: 8px; }
.alias-too-long { color: var(--cs-danger); font-size: 12px; margin-top: 4px; }
</style>
