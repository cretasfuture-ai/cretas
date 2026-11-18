<script setup lang="ts">
/**
 * 区域销售分布的列表体。无数据 / 数据不可信时整块显示空状态, 不渲染任何区域数字。
 * 占比计算见 regionShare.ts。
 */
import { computed } from 'vue';
import { formatNumber } from '@/utils/format-number';
import { buildRegionShares, formatSharePercent, type RegionShareInput } from './regionShare';

const props = defineProps<{
  items: ReadonlyArray<RegionShareInput>;
  /** 驾驶舱是否有真实指标数据; 没有时区域分布一律视为无数据 */
  hasData: boolean;
}>();

const rows = computed(() => (props.hasData ? buildRegionShares(props.items) : []));
</script>

<template>
  <div v-if="rows.length > 0" class="region-list">
    <div v-for="(item, index) in rows" :key="item.name" class="region-item">
      <div class="region-name">{{ item.name }}</div>
      <div class="region-bar-wrapper">
        <div
          class="region-bar"
          :class="'rank-bar-' + Math.min(index, 3)"
          :style="{ width: item.percentage + '%' }"
        ></div>
      </div>
      <div class="region-value">
        <span class="value">{{ formatNumber(item.sales, 1) }}</span>
        <span class="percent">{{ formatSharePercent(item.percentage) }}</span>
      </div>
    </div>
  </div>
  <el-empty
    v-else
    class="region-empty"
    :image-size="80"
    description="暂无区域销售数据：需要含「区域」和「销售额」两列的销售数据，请先上传 Excel"
  />
</template>

<style lang="scss" scoped>
.region-item {
  display: flex;
  align-items: center;
  border-radius: 6px;
  padding: 12px 8px;
  margin: 0 -8px;
  border-bottom: 1px solid #F4F6F9;
  transition: background 0.2s ease;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: var(--el-fill-color-light, #F4F6F9);

    .region-bar {
      filter: brightness(1.1);
    }
  }
}

.region-name {
  width: 80px;
  font-size: 14px;
  color: var(--el-text-color-primary, #1A2332);
}

.region-bar-wrapper {
  flex: 1;
  height: 8px;
  background: #F4F6F9;
  border-radius: 4px;
  margin: 0 12px;
  overflow: hidden;
}

.region-bar {
  height: 100%;
  background: linear-gradient(90deg, #2D8B57, #4C9AFF);
  border-radius: 4px;
  transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1), filter 0.2s ease;

  &.rank-bar-0 { background: linear-gradient(90deg, #2D8B57, #4C9AFF); }
  &.rank-bar-1 { background: linear-gradient(90deg, #36B37E, #57D9A3); }
  &.rank-bar-2 { background: linear-gradient(90deg, #FFAB00, #FFC400); }
  &.rank-bar-3 { background: linear-gradient(90deg, #A0AEC0, #CBD5E0); }
}

.region-value {
  width: 110px;
  text-align: right;

  .value {
    font-size: 14px;
    color: var(--el-text-color-primary, #1A2332);
    font-weight: 500;
  }

  .percent {
    font-size: 12px;
    color: var(--color-text-secondary);
    margin-left: 8px;
  }
}
</style>
