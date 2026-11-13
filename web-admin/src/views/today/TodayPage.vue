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
const doBlocks = computed(() => [T.s.approve, T.s.receiving, T.s.unordered, T.s.salesOrders]);
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
      <template #header><b>还没有的来源</b></template>
      <ul class="gaps" data-testid="today-gaps">
        <li><b>今日待称重任务</b>：缺「按生产计划工序与已提交报工算出未报完任务」的聚合，由 P5（数据疑点与校验）补接口，补齐前不显示数量。</li>
        <li><b>等我确认 / 数字员工在办什么</b>：数字班组要到 P8 才有，本批不做。</li>
        <li><b>发现的问题</b>：来源分类与关闭结论在 P4（发现的问题）做，本批不显示。</li>
        <li><b>对账差异</b>：只有外部系统授权的工厂才有，由 P10（外部软件）补，本厂没有这类数据。</li>
        <li><b>协同任务 / 规则提醒</b>：协同任务页不在迁移范围（M7），本批没有来源。</li>
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
