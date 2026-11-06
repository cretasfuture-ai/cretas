import { mount,flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { reactive,defineComponent,h } from 'vue'
import { createMemoryHistory,createRouter } from 'vue-router'
import { expect,it,vi } from 'vitest'
import { nativePlayback } from '@/demo/f006NativeRuntime'
const fixture=vi.hoisted(()=>({auth:null as null|{factoryId:string;currentRole:string;user:{id:number}}}))
vi.mock('@/store/modules/auth',()=>({useAuthStore:()=>fixture.auth}))
vi.mock('@/store/modules/app',()=>({useAppStore:()=>({initResponsive(){},isMobile:false,currentSidebarWidth:220})}))
vi.mock('@/demo/f006Sandbox',async()=>{const{shallowRef}=await import('vue');return{SANDBOX_FLOW_STORAGE_KEY:'cretas:f006:sandbox-flow:v1',sandboxSession:shallowRef({sessionId:'owned-session',status:'READY'}),recoverActiveSandbox:vi.fn().mockResolvedValue(undefined),selectSandboxActor:vi.fn()}})
import Layout from '../AppLayout.vue'
it('preserves each original business page with no side console and removes native controls on tenant change',async()=>{
 sessionStorage.clear();nativePlayback.enabled=true
 fixture.auth=reactive({factoryId:'F006',currentRole:'factory_super_admin',user:{id:1309}})
 const Original=defineComponent({render:()=>h('form',{'data-testid':'original-business-form'},[h('input')])})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/procurement/orders',component:Original},{path:'/warehouse/materials',component:Original}]})
 await router.push('/procurement/orders');await router.isReady()
 const wrapper=mount(Layout,{global:{plugins:[createPinia(),router],stubs:{AppSidebar:true,AppHeader:true,WorkspaceTabs:true,WorkspaceReferencePane:true,ReturnBanner:true,InlineCustomerService:true,F006SandboxBanner:true,F006NativeToolbar:{template:'<div data-testid="native-top-only">正在演示</div>'}}}})
 try{
  await flushPromises();expect(wrapper.findAll('form')).toHaveLength(1);expect(wrapper.find('[data-testid="native-top-only"]').exists()).toBe(true)
  expect(wrapper.find('[data-testid="demo-guide"]').exists()).toBe(false)
  await router.push('/warehouse/materials');await flushPromises();expect(wrapper.findAll('form')).toHaveLength(1)
  fixture.auth.factoryId='F007';await flushPromises();expect(wrapper.find('[data-testid="native-top-only"]').exists()).toBe(false)
 }finally{wrapper.unmount();nativePlayback.enabled=false}
})
