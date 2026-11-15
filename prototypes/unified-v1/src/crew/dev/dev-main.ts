// F 线开发预览入口（仅在 D 线接入 FrameRoot 之前用于本线自测；不进入统一构建）。
import {createApp,h} from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import UApp from '@nuxt/ui/components/App.vue'
import CrewModule from '../CrewModule.vue'
import {syncRoute} from '../../frame/store'
import '../../frame/frame.css'
// 与 frame/main.ts 相同：六膳门接口 /api/* 改走 /__lsm/api/*
const rawFetch=window.fetch.bind(window)
window.fetch=(i:RequestInfo|URL,init?:RequestInit)=>{if(typeof i==='string'&&i.startsWith('/api/'))i='/__lsm'+i;return rawFetch(i,init)}
document.body.classList.add('cs-app')
if(!location.hash)location.hash='#/crew'
window.addEventListener('hashchange',syncRoute);syncRoute()
createApp({render:()=>h(UApp,null,{default:()=>h('main',{class:'lsm-main'},[h(CrewModule)])})}).use(ui).mount('#app')
