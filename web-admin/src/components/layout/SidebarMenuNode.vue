<script setup lang="ts">
import type { Component } from 'vue';
import type { MenuItem } from './menuConfig';

defineOptions({ name: 'SidebarMenuNode' });

defineProps<{
  item: MenuItem;
  collapsed: boolean;
  iconMap: Record<string, Component>;
  titleForItem: (item: MenuItem) => string;
  badgeForItem: (item: MenuItem) => number | 'unknown' | null;
  level: number;
}>();

/** 数字超过 99 显示 99+; 算不出来显示「?」(不是 0, 也不隐藏)。 */
function badgeLabel(value: number | 'unknown' | null): string {
  if (value === 'unknown') return '?';
  if (value === null) return '';
  return value > 99 ? '99+' : String(value);
}
</script>

<template>
  <div v-if="item.groupLabel" class="menu-group-label" :class="{ 'is-hidden-group': collapsed }" :aria-hidden="collapsed">
    {{ item.groupLabel }}
  </div>

  <el-sub-menu
    v-if="item.children?.length"
    :index="item.path"
    :aria-label="titleForItem(item)"
    popper-class="app-sidebar-menu-popper"
  >
    <template #title>
      <el-icon v-if="item.icon && iconMap[item.icon]">
        <component :is="iconMap[item.icon]" />
      </el-icon>
      <span class="menu-title-copy">{{ titleForItem(item) }}</span>
      <span v-if="badgeForItem(item) !== null" class="menu-task-badge" :class="{ 'menu-task-badge--unknown': badgeForItem(item) === 'unknown' }" :title="badgeForItem(item) === 'unknown' ? '待办数量暂时无法确认' : undefined">{{ badgeLabel(badgeForItem(item)) }}</span>
    </template>

    <SidebarMenuNode
      v-for="child in item.children"
      :key="child.path"
      :item="child"
      :collapsed="collapsed"
      :icon-map="iconMap"
      :title-for-item="titleForItem"
      :badge-for-item="badgeForItem"
      :level="level + 1"
    />
  </el-sub-menu>

  <el-menu-item v-else :index="item.path" :aria-label="titleForItem(item)">
    <el-icon v-if="item.icon && iconMap[item.icon]">
      <component :is="iconMap[item.icon]" />
    </el-icon>
    <template #title>
      <span class="menu-title-copy">{{ titleForItem(item) }}</span>
      <span v-if="badgeForItem(item) !== null" class="menu-task-badge" :class="{ 'menu-task-badge--unknown': badgeForItem(item) === 'unknown' }" :title="badgeForItem(item) === 'unknown' ? '待办数量暂时无法确认' : undefined">{{ badgeLabel(badgeForItem(item)) }}</span>
    </template>
  </el-menu-item>
</template>

<style scoped>
.menu-group-label {
  padding: 8px 12px 4px 36px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  user-select: none;
  max-height: 40px;
  opacity: 1;
  transition: opacity 140ms ease-out, max-height 0.3s ease-in-out,
    padding 0.3s ease-in-out, margin 0.3s ease-in-out;
  transition-behavior: allow-discrete;
}

.menu-group-label:not(:first-child) {
  margin-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
  padding-top: 10px;
}

.menu-group-label.is-hidden-group {
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
  margin-top: 0;
  border-top-width: 0;
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .menu-group-label { transition: none; }
}

.menu-title-copy { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.menu-task-badge {
  min-width: 20px;
  height: 20px;
  margin-left: auto;
  padding: 0 6px;
  border-radius: 999px;
  background: #e75555;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  line-height: 1;
  font-weight: 700;
  box-shadow: 0 0 0 2px rgba(231, 85, 85, 0.14);
}

.menu-task-badge--unknown {
  background: #909399;
}
</style>
