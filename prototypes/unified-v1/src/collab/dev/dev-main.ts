// E 线开发预览入口（仅在 D 线接入 FrameRoot 之前用于本线自测；不进入统一构建）。
import {createApp,h} from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import UApp from '@nuxt/ui/components/App.vue'
import EModule from '../EModule.vue'
import '../../frame/frame.css'
document.body.classList.add('cs-app')
if(!location.hash)location.hash='#/quality/inspections'
createApp({render:()=>h(UApp,null,{default:()=>h(EModule)})}).use(ui).mount('#app')
