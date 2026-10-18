<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue';
import { listAttachments, getAttachmentDownloadUrl } from '@/api/attachment';
import { formatBusinessDateTime } from '../salesOrderGuards';

const props = defineProps<{
  factoryId: string; deliveryId: string; photoUrls: string[];
  signedByName: string; signedAt: string; remark: string;
}>();
const loading = ref(false);
const error = ref('');
const photos = ref<{ name: string; url: string }[]>([]);
let version = 0;
onBeforeUnmount(() => { ++version; });

async function load() {
  const requestVersion = ++version;
  loading.value = true; error.value = ''; photos.value = [];
  try {
    if (!props.factoryId || !props.deliveryId) throw new Error('missing context');
    if (!props.photoUrls.length) return;
    const result = await listAttachments('SHIPMENT', props.deliveryId, props.factoryId);
    if (!result.success) throw new Error('attachment query failed');
    const saved = props.photoUrls.map(url => result.data.find(a =>
      a.fileUrl === url && a.factoryId === props.factoryId && a.entityId === props.deliveryId
      && a.entityType === 'SHIPMENT' && a.fileCategory === 'SIGNATURE',
    ));
    if (saved.some(a => !a)) throw new Error('saved receipt attachment missing');
    const loaded = await Promise.all(saved.map(async attachment => {
      if (!attachment) throw new Error('missing attachment');
      const response = await getAttachmentDownloadUrl(attachment.id, props.factoryId);
      if (!response.success || !response.data.url) throw new Error('preview link unavailable');
      return { name: attachment.fileName, url: response.data.url };
    }));
    if (version === requestVersion) photos.value = loaded;
  } catch {
    if (version === requestVersion) error.value = '签收凭证加载失败，请重试；仍无法查看时请联系管理员核对附件权限和已保存照片。';
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section aria-label="已保存的签收凭证">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="签收人">{{ signedByName || '未记录' }}</el-descriptions-item>
      <el-descriptions-item label="签收时间">{{ signedAt ? formatBusinessDateTime(signedAt) : '未记录' }}</el-descriptions-item>
      <el-descriptions-item label="签收备注">{{ remark || '无' }}</el-descriptions-item>
    </el-descriptions>
    <el-skeleton v-if="loading" :rows="2" animated />
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-else-if="!photos.length" description="尚未保存签收照片" :image-size="60" />
    <div v-else class="receipt-photos">
      <figure v-for="(photo, index) in photos" :key="index">
        <el-image :src="photo.url" :alt="photo.name" fit="contain"
          :preview-src-list="photos.map(p => p.url)" :initial-index="index" preview-teleported>
          <template #error><span role="alert">凭证图片无法显示，请重试</span></template>
        </el-image>
        <figcaption>{{ photo.name }}</figcaption>
      </figure>
    </div>
    <el-button v-if="error || photos.length" @click="load" :loading="loading">重新加载凭证</el-button>
  </section>
</template>

<style scoped>
.receipt-photos { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
figure { margin: 0; max-width: 100%; }
.el-image { width: 220px; height: 160px; max-width: 100%; }
figcaption { overflow-wrap: anywhere; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
