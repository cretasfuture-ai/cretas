<script setup lang="ts">
// 正式形象：Codex 做的五位黏土角色（主仓 output/characters/cretas-digital-employees-motion-20261113-v3/assets/characters），
// 这里用的是从原图裁出的胸像 avatars/<id>.png（192×192，只裁剪缩放，没有改画）。
// 状态：idle 空闲 / working 办理中 / waiting 等你确认 / alert 发现问题，仍用右下角状态点表达。
// Codex 的动作素材是 待机 / 工作 / 等待确认 / 完成 四种（Canvas 动画），没有「发现问题」的姿态，所以姿态没有接。
// 找不到对应形象时退回首字占位。尺寸：24 / 40 / 64 / 96。
import {computed} from 'vue'
import {CREW} from './crew-config'
const IMG=import.meta.glob('./avatars/*.png',{eager:true,import:'default'}) as Record<string,string>
const p=defineProps<{name:string;state?:'idle'|'working'|'waiting'|'alert';size?:24|40|64|96}>()
const LABEL={idle:'空闲',working:'办理中',waiting:'等你确认',alert:'发现问题'}
const s=computed(()=>p.size||40),st=computed(()=>p.state||'idle')
const src=computed(()=>{const c=CREW.find(x=>x.name===p.name);return c?IMG['./avatars/'+c.id+'.png']||'':''})
</script>
<template><span class="crew-avatar" :class="{'has-img':!!src}" :data-state="st" :data-size="s" :style="{width:s+'px',height:s+'px',fontSize:Math.round(s*0.36)+'px'}" role="img" :aria-label="name+' · '+LABEL[st]" :title="name+' · '+LABEL[st]"><img v-if="src" :src="src" alt="" draggable="false"><template v-else>{{name.replace('数字','').slice(0,1)}}</template><i class="st"/></span></template>
