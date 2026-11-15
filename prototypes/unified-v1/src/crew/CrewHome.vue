<script setup lang="ts">
// 结构 1 · 班组首页：五位数字员工各一张卡（CrewCards，与 G 线「今天」页共用），老板和厂长一屏看全厂 AI 工作情况。
// 第七批（R27 G14）：启用配置搬到「设置 · 数字班组配置」（/crew/settings），这里只留跳转。
import {computed} from 'vue'
import {navigate} from '../frame/store'
import {C} from './crew-store'
import {CREW} from './crew-config'
import CrewCards from './CrewCards.vue'
const enabled=computed(()=>CREW.filter(c=>C.state?C.state.config.enabled[c.id]!==false:true).length)
function go(p:string){const q:Record<string,string>={};if(C.ws==='ydwm')q.ws='ydwm';navigate(p,q)}
</script>
<template><div>
<CrewCards/>
<p class="footnote" style="display:flex;gap:12px;flex-wrap:wrap;align-items:center">
  <span>本厂启用 {{enabled}} / {{CREW.length}} 位数字员工。</span>
  <button class="btn link" type="button" data-testid="crew-settings-link" @click="go('/crew/settings')">到「设置 · 数字班组配置」调整启用</button>
  <button class="btn link" type="button" @click="go('/crew/mobile')">一线手机端</button>
  <button class="btn link" type="button" @click="go('/crew/map')">功能归属与架构</button>
</p>
<p class="footnote">「今天办成」只数工作记录里「已办成」的事（任务办完、回读核实）；已交办、原型演示未执行都不计。问题来自「发现的问题」（按快照现算）。</p>
</div></template>
