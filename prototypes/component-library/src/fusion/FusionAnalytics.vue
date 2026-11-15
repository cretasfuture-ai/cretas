<script setup lang="ts">
import { inject, ref, onMounted } from 'vue'
import type { SampleData } from '../types'
import { fusionDataKey } from './context'
import CostOverview from './CostOverview.vue'
import Dashboard from '../../legacy-src/views/smart-bi/Dashboard.vue'
const shared = inject(fusionDataKey), data = ref<SampleData | undefined>(shared?.value)
onMounted(async () => { const response=await fetch('/__v5/data'); if(response.ok) data.value=await response.json() })
</script>
<template><div class="fusion-analytics"><CostOverview v-if="data" :data="data" /><section class="fusion-executive-section"><Dashboard /></section></div></template>
