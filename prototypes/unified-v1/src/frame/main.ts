import {createApp} from 'vue'
import ui from '@nuxt/ui/vue-plugin'
import FrameRoot from './FrameRoot.vue'
import './frame.css'
// 六膳门原有组件按独立应用写的接口 /api/*；在统一入口里全部改走同源转发 /__lsm/api/*（到六膳门服务 19062）。
// 味美接口不在此域内，这一层保证六膳门工作区不会读写味美数据。
const rawFetch=window.fetch.bind(window)
window.fetch=(input:RequestInfo|URL,init?:RequestInit)=>{
  if(typeof input==='string'&&input.startsWith('/api/'))input='/__lsm'+input
  return rawFetch(input,init)
}
document.body.classList.add('cs-app')
// 焦点在工作区内容里时，Ctrl K 交给外壳的菜单搜索（与味美同一快捷键）。
window.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'&&window.parent!==window){e.preventDefault();window.parent.postMessage({source:'lsm-frame',type:'search'},location.origin)}},true)
// 页面内用 replaceState/pushState 改了查询参数（如 E 的 stage=、over=1）时，也告诉外壳，让顶层地址同步，刷新不丢
for(const k of ['pushState','replaceState'] as const){const o=history[k].bind(history);history[k]=(s:unknown,t:string,u?:string|URL|null)=>{o(s,t,u);try{const h=location.hash.replace(/^#/,'')||'/dashboard';window.parent.postMessage({source:'lsm-frame',type:'route',route:h},location.origin)}catch{/* 独立打开时无外壳 */}}}
createApp(FrameRoot).use(ui).mount('#app')
