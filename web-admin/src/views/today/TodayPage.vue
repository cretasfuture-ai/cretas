<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import SourceBlock from './SourceBlock.vue';
import { useToday } from './useToday';

const auth = useAuthStore();
const { T, reload } = useToday();
onMounted(() => { void reload(); });
watch(() => [auth.factoryId, auth.user?.id], () => { void reload(); });

// 北京时间当天（不用 UTC 截断：noUtcDayTruncation 闸）
const day = computed(() => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()));
const doBlocks = computed(() => [T.s.crewConfirm, T.s.approve, T.s.receiving, T.s.unordered, T.s.salesOrders]);
const watchBlocks = computed(() => [T.s.risk, T.s.expiry]);
</script>

<template>
  <div class="today" data-testid="today-page">
    <header class="head">
      <div>
        <h1>今天 <small>{{ day }}（北京时间）</small></h1>
        <p class="why">打开先看轮到我办的事；每一项的数字来自对应页面的同一接口，点进去就是那一页。</p>
      </div>
      <el-button :loading="T.busy" @click="reload">刷新</el-button>
    </header>

    <el-card class="card" shadow="never">
      <template #header><b>今天要办的事</b></template>
      <SourceBlock v-for="b in doBlocks" :key="b.id" :s="b" />
    </el-card>

    <el-card class="card" shadow="never">
      <template #header><b>我在盯的</b></template>
      <SourceBlock v-for="b in watchBlocks" :key="b.id" :s="b" />
    </el-card>

    <el-card class="card" shadow="never">
      <template #header><b>暂未接入的事项</b></template>
      <ul class="gaps" data-testid="today-gaps">
        <li><b>今日待称重任务</b>：还没有按生产计划汇总未报完的称重任务，所以这里不显示数量。</li>
        <li><b>发现的问题</b>：转给你的问题已在上面「从“发现的问题”转来的」里显示，其余请到「发现的问题」页查看。</li>
        <li><b>对账差异</b>：只有接入外部系统的工厂才有，本厂没有这类数据。</li>
        <li><b>协同任务 / 规则提醒</b>：暂未提供，这里不显示。</li>
      </ul>
    </el-card>
  </div>
</template>

<style scoped>
.today { padding: 16px 0; max-width: 960px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
h1 small { font-size: var(--cs-type-caption); color: var(--cs-muted); font-weight: 400; }
.why { margin: 4px 0 0; color: var(--cs-muted); font-size: var(--cs-type-body); }
.card { margin-bottom: 12px; }
.gaps { margin: 0; padding-left: 18px; font-size: var(--cs-type-body); color: var(--cs-muted); line-height: 1.8; }
</style>
