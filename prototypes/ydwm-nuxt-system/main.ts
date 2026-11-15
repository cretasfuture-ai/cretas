import { createApp } from 'vue'
import uiPlugin from '@nuxt/ui/vue-plugin'
import App from './App.vue'
import './style.css'

const root = document.createElement('div')
root.id = 'cretas-nuxt-root'
root.setAttribute('aria-hidden', 'false')
document.body.appendChild(root)
createApp(App).use(uiPlugin).mount(root)
