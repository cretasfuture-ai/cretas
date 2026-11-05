<template>
  <el-drawer
    :model-value="modelValue"
    :title="`SOP 文件 — ${targetName}`"
    size="520px"
    append-to-body
    data-testid="sop-files-drawer"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="sop-head">
      <span class="sop-hint">
        {{ entityType === 'WORK_PROCESS' ? '操作员在 App 报工页能看到这里的文件' : '本设备的操作 / 保养标准文件' }}
        · 仅支持 PDF 和图片，单个不超过 10MB
      </span>
      <el-upload
        v-if="canWrite"
        :show-file-list="false"
        :auto-upload="true"
        :accept="SOP_ACCEPT"
        :before-upload="handleBefore"
        :http-request="customUpload"
        :disabled="uploading"
      >
        <el-button type="primary" :loading="uploading" :disabled="uploading" data-testid="sop-upload-button">
          {{ uploading ? '上传中…' : '上传 SOP 文件' }}
        </el-button>
      </el-upload>
    </div>

    <el-skeleton v-if="loading" :rows="3" animated />
    <el-alert
      v-else-if="loadError"
      type="error"
      :closable="false"
      :title="`SOP 列表加载失败：${loadError}`"
      data-testid="sop-load-error"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>
    <el-empty
      v-else-if="list.length === 0"
      :description="canWrite ? '还没有 SOP 文件，点右上角上传' : '还没有 SOP 文件'"
      :image-size="64"
      data-testid="sop-empty"
    />
    <ul v-else class="sop-list" data-testid="sop-list">
      <li v-for="att in list" :key="att.id" class="sop-row" :data-testid="`sop-row-${att.id}`">
        <div class="sop-meta">
          <div class="sop-name" :title="att.fileName">{{ att.fileName }}</div>
          <div class="sop-sub">{{ kindLabel(att) }} · {{ formatAttachmentSize(att.fileSize) }} · {{ formatTime(att.uploadedAt) }}</div>
        </div>
        <el-button
          type="primary"
          link
          :loading="openingId === att.id"
          :data-testid="`sop-open-${att.id}`"
          @click="openFile(att)"
        >打开</el-button>
        <el-button
          v-if="canWrite"
          type="danger"
          link
          :data-testid="`sop-delete-${att.id}`"
          @click="removeFile(att)"
        >删除</el-button>
      </li>
    </ul>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  listAttachments,
  deleteAttachment,
  uploadAndRegister,
  getAttachmentDownloadUrl,
  checkSopFile,
  SOP_ACCEPT,
  type SopAttachmentMetadata,
} from '@/api/attachment';
import { formatAttachmentSize } from './attachmentUploadModel';
import { getErrorMessage, handleCatchError } from '@/utils/errorToast';

interface Props {
  modelValue: boolean;
  entityType: 'WORK_PROCESS' | 'EQUIPMENT';
  entityId: string;
  /** 工序名 / 设备名，放进标题与删除确认里（防呆 Rule 2：写操作带身份信息）。 */
  targetName: string;
  /** 是否有上传 / 删除权限；只是隐藏入口，真正的权限由后端判。 */
  canWrite: boolean;
  factoryId?: string;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  /** SOP 条数变化（上传 / 删除 / 加载后），列表页可据此刷新角标。 */
  countChange: [count: number];
}>();

const list = ref<SopAttachmentMetadata[]>([]);
const loading = ref(false);
const loadError = ref<string | null>(null);
const uploading = ref(false);
const openingId = ref<string | null>(null);
let loadSeq = 0;

async function load(): Promise<void> {
  if (!props.entityId) return;
  const seq = ++loadSeq;
  loading.value = true;
  loadError.value = null;
  try {
    const r = await listAttachments(props.entityType, props.entityId, props.factoryId);
    if (seq !== loadSeq) return;   // 换了目标，旧结果丢弃
    list.value = (r.data ?? []) as SopAttachmentMetadata[];
    emit('countChange', list.value.length);
  } catch (e: unknown) {
    if (seq !== loadSeq) return;
    list.value = [];
    loadError.value = getErrorMessage(e, '网络异常，请稍后重试');
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

watch(
  () => [props.modelValue, props.entityType, props.entityId] as const,
  ([visible]) => {
    if (visible) void load();
  },
  { immediate: true },
);

function handleBefore(file: File): boolean {
  const problem = checkSopFile(file);
  if (problem) {
    ElMessage.error(problem);
    return false;
  }
  return true;
}

async function customUpload(opts: { file: File }): Promise<void> {
  uploading.value = true;
  try {
    await uploadAndRegister(opts.file, props.entityType, props.entityId, { businessTag: 'SOP' }, props.factoryId);
    ElMessage.success('SOP 文件已上传');
    await load();
  } catch (e: unknown) {
    // 后端 4xx 的 message 已由 request 拦截器弹出；这里只补纯网络错误（OSS 直传失败等）
    handleCatchError(e, getErrorMessage(e, '上传失败，请检查网络后重试'));
  } finally {
    uploading.value = false;
  }
}

async function openFile(att: SopAttachmentMetadata): Promise<void> {
  openingId.value = att.id;
  try {
    const r = await getAttachmentDownloadUrl(att.id, props.factoryId);
    const url = r.data?.url;
    if (!url) throw new Error('没有拿到文件地址');
    window.open(url, '_blank', 'noopener');
  } catch (e: unknown) {
    handleCatchError(e, `打不开「${att.fileName}」：${getErrorMessage(e, '网络异常')}，请稍后重试`);
  } finally {
    openingId.value = null;
  }
}

async function removeFile(att: SopAttachmentMetadata): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${props.targetName}」的 SOP 文件「${att.fileName}」？删除后操作员在 App 里就看不到它了。`,
      '确认删除 SOP',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;   // 取消
  }
  try {
    await deleteAttachment(att.id, props.factoryId);
    ElMessage.success('已删除');
    await load();
  } catch (e: unknown) {
    handleCatchError(e, getErrorMessage(e, '删除失败，请稍后重试'));
  }
}

function kindLabel(att: SopAttachmentMetadata): string {
  return att.fileType?.toLowerCase() === 'application/pdf' || /\.pdf$/i.test(att.fileName) ? 'PDF' : '图片';
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

defineExpose({ reload: load });
</script>

<style scoped>
.sop-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.sop-hint {
  font-size: 12px;
  color: #909399;
  flex: 1;
}
.sop-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sop-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.sop-meta {
  flex: 1;
  min-width: 0;
}
.sop-name {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sop-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
</style>
