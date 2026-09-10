<script setup lang="ts">
import { ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import FormalDeliveries from './FormalDeliveries.vue';
import LegacyShipments from './LegacyShipments.vue';
const activeTab = ref('formal');
const auth = useAuthStore();
</script>
<template>
  <div class="shipments-page">
    <h2>出货记录</h2>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="正式发货记录" name="formal"><FormalDeliveries v-if="activeTab === 'formal'" /></el-tab-pane>
      <el-tab-pane label="历史手工出货记录（只读）" name="legacy"><LegacyShipments v-if="activeTab === 'legacy'" :key="auth.factoryId" /></el-tab-pane>
    </el-tabs>
  </div>
</template>
<style scoped>
.shipments-page { padding: 20px; }
h2 { margin: 0 0 16px; }
</style>
