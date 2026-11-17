<script setup lang="ts">
// App.vue - 根组件
// 角色同步在路由守卫中处理，避免循环依赖
// UApp 提供 Nuxt UI 的 TooltipProvider/Toaster/Overlay；没有它任何页面里的 UTooltip 会在 setup 抛错。
// locale 仍由 plugins/nuxtUiLocale 在 app 级 provide（UApp 会继承）；弹层 z-index 见 styles/nuxt-ui.css（1100–1200，低于 Element 的 2000）。
</script>

<template>
  <UApp>
    <router-view />
  </UApp>
</template>

<style>
/* 全局样式重置（legacy-reset 层，见 style.css） */
@layer legacy-reset {
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
  }
}

html, body, #app {
  height: 100%;
  width: 100%;
}

body {
  font-family: var(--cs-font-family);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* Element Plus 变量统一在 src/styles/element-plus-theme.css 映射到 --cs-*（P1），这里不再写死 */

/* 滚动条样式 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: #909399;
}

::-webkit-scrollbar-track {
  background: #f5f7fa;
}
</style>
