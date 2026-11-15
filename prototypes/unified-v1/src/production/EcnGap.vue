<script setup lang="ts">
// 工程变更通知（六膳门，R37）：黑湖没有 BOM / 工艺变更单（申请、影响范围、生效版本）这类数据，Cretas 也没有生产版本号可挂，
// 所以不造数据、不拿别的数据顶替，只写缺口和现有的替代入口。味美侧用编译页原生的 /production/bom/ecns，不走本页。
// 不留补充入口的理由：变更单的核心是「影响哪个版本、哪天生效」，六膳门路线没有版本号，自由文本登记会变成对不上任何版本的孤立记录。
import UButton from '@nuxt/ui/components/Button.vue'
import {navigate} from '../frame/store'
import {sourceTagHTML} from '../frame/ai-protocol'
</script>
<template><section class="card table-panel ecn">
<div class="ecn-head"><h2>工程变更通知</h2><span v-html="sourceTagHTML('missing')"/></div>
<p><b>黑湖没有此类数据。</b>黑湖接口里没有 BOM 或工艺的变更单，也没有生产版本号，所以这里没有变更申请、影响范围和生效版本可显示。</p>
<dl class="ecn-kv"><dt>缺什么</dt><dd>变更单（申请人、变更内容、影响的产品和工序、生效日期）和生效版本。</dd><dt>找谁补</dt><dd>要黑湖开放变更单接口，或确认 Cretas 以后自建；在这之前本页不造数据。</dd><dt>现在能看什么</dt><dd>工序配置里的 BOM 页签是当前用料依据；本地工作流保存的版本记录了每次工艺改动的时间和原因。</dd></dl>
<div class="ecn-act"><UButton size="sm" color="neutral" variant="outline" @click="navigate('/production/process-hub',{tab:'bom'})">看 BOM 用料依据</UButton><UButton size="sm" color="neutral" variant="outline" @click="navigate('/production/process-hub')">打开工序配置</UButton></div>
</section></template>
<style scoped>.ecn-head{display:flex;justify-content:space-between;align-items:center}.ecn-head h2{margin:0}.ecn-kv{display:grid;grid-template-columns:96px 1fr;gap:6px 12px;font-size:13px}.ecn-kv dt{color:#667589}.ecn-kv dd{margin:0}.ecn-act{display:flex;gap:8px;margin-top:12px}</style>
