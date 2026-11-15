import { createApp } from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import './style.css'
import './detail.css'
import App from './App.vue'
import { createPinia } from 'pinia'
import router from '../legacy-src/router'
import { installFusionTransport } from './fusion/transport'
import './fusion.css'
import './fusion-polish.css'
import './sidebar-motion.css'
import './fusion/motion'
installFusionTransport()
export const fusionPinia = createPinia()

createApp(App).use(fusionPinia).use(router).use(ui).use(ElementPlus, { locale: zhCn }).mount('#app')
