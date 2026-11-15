<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { PageId, SampleData } from '../types'
import { state } from '../state'
import { fusionPaths } from './identity'
import Icon from '../components/Icon.vue'
defineProps<{ data: SampleData }>()
const route = useRoute()
const supportTitle = computed(() => !Object.values(fusionPaths).includes(route.path) && route.path !== '/production/process-config' ? String(route.meta.title || '') : '')
watch(() => route.path, path => { const matched = Object.entries(fusionPaths).find(([, value]) => value === path); if (matched) state.page = matched[0] as PageId })
</script>
<template><div class="fusion-business" :data-fusion-page="state.page"><div v-if="supportTitle" class="support-route-header"><Icon name="link" :size="18" /><span>{{ supportTitle }}</span><small>当前业务的关联页面</small></div><RouterView v-slot="{ Component }"><component :is="Component" :key="route.path" /></RouterView></div></template>
