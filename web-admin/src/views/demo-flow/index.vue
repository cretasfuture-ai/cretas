<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { enableNativeDemo } from '@/demo/f006NativeRuntime'
const router=useRouter()
const error=ref('')
onMounted(async()=>{try{enableNativeDemo();await router.replace('/procurement/orders')}catch(value){error.value=value instanceof Error?value.message:'无法进入演示'}})
</script>
<template><el-result v-if="error" icon="warning" title="无法进入演示" :sub-title="error" /><span v-else role="status">正在打开原采购页面…</span></template>
