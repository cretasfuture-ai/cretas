<script setup lang="ts">
// 尚未迁入的六膳门菜单页：保留菜单位置与评审说明，不用味美数据、不用模拟记录冒充真实来源。
import {computed} from 'vue'
import {S} from './store'
const entry=computed(()=>S.menu.find(m=>m.path===S.path))
</script>
<template><div class="module-review"><section class="card"><div class="section-head"><h2>本模块的评审范围</h2><span class="review-state">待逐页整理</span></div><div class="review-content"><p>{{entry?.reason||'保留现有管理端的功能目录，下一步按这个模块逐项确认用途、操作和结果。'}}</p><dl><dt>菜单位置</dt><dd>{{entry?entry.group+' / '+entry.title:S.path}}</dd><dt>参考功能入口</dt><dd>{{S.path}}</dd><dt>当前实现状态</dt><dd>已在统一菜单中归位；六膳门的该业务页面尚未迁入。</dd><dt>接下来核对</dt><dd>页面结构、按钮用途、字段配置、保存结果、权限边界和跨模块联动（由后续实现线按 unified-spec 规格分配）。</dd><dt>数据与权限</dt><dd>不使用味美账号数据，不用模拟记录冒充真实来源；菜单展示不启用关闭模块或授予权限。</dd></dl></div></section></div></template>
