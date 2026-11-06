import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { Ref } from 'vue'
import type { DemoEntity, DemoState } from '../f006Flow'
import type { NativeDemoContext } from '../f006NativeBridge'

const fixture=vi.hoisted(()=>({state:null as Ref<DemoState|null>|null,prepare:vi.fn(),commit:vi.fn(),responseOk:true}))
vi.mock('@/store/modules/auth',()=>({useAuthStore:()=>({factoryId:'F006',user:{id:1309},currentRole:'factory_super_admin'})}))
vi.mock('../f006DemoSupport',()=>({assertLiveAdmin:vi.fn().mockResolvedValue({id:'1309',factoryId:'F006',role:'factory_super_admin'})}))
vi.mock('../f006Flow',()=>({DEMO_RUN_KEY:'DEMO-F006-FLOW-20261105',DEMO_STEPS:[{id:'purchase-create',label:'采购'},{id:'sales-order-create',label:'销售'}],createDemoState:vi.fn(),initializeDemo:vi.fn(async(state:DemoState)=>state)}))
vi.mock('../f006Sandbox',async()=>{const {shallowRef}=await import('vue');return{sandboxSession:shallowRef({sessionId:'unit-session',status:'READY'}),beginSandbox:vi.fn(),refreshSandbox:vi.fn(),endSandbox:vi.fn(),clearEndedSandbox:vi.fn()}})
vi.mock('../f006NativeBridge',async()=>{
  const {shallowRef}=await import('vue');fixture.state=shallowRef<DemoState|null>(null)
  const assert=(state:DemoState)=>{if(state!==fixture.state!.value)throw Error('wrong owner')}
  return{nativeDemoState:fixture.state,assertNativeDemoScope:assert,nativeAdapterFor:()=>({prepare:fixture.prepare,commit:fixture.commit})}
})
vi.mock('@/api/request',async()=>{
  const {default:axios}=await import('axios')
  const client=axios.create({baseURL:'/api/mobile',adapter:async config=>({status:200,statusText:'OK',headers:{},config,data:JSON.stringify(fixture.responseOk?{success:true,data:{id:'native-id',factoryId:'F006',remark:'DEMO-F006-FLOW-20261105-unit',status:'DRAFT'}}:{success:false,message:'native validation failed'})})})
  client.interceptors.request.use(config=>{if(config.url?.startsWith('/F006/')){config.url='/api/mobile/F006/demo-sandbox/sessions/unit-session/proxy/api/mobile'+config.url;config.baseURL=undefined};return config})
  client.interceptors.response.use(response=>response.data)
  return{default:client}
})
import request from '@/api/request'
import { endNativeDemo, installNativeDemoRuntime, nativePlayback, nextNativeModule, startNativeDemo } from '../f006NativeRuntime'

let router:Router,dispose:(()=>void)|undefined
beforeEach(async()=>{
  vi.useFakeTimers();sessionStorage.clear();fixture.responseOk=true
  vi.spyOn(document,'hidden','get').mockReturnValue(false)
  fixture.state!.value={schemaVersion:2,sandboxSessionId:'unit-session',factoryId:'F006',runKey:'DEMO-F006-FLOW-20261105-unit',currentStep:0,entities:{},readbacks:{},completed:[],context:{supplier:{},customer:{},material:{},packaging:{},product:{},rawWarehouse:{},workshopWarehouse:{},processes:[],rawQuantity:1.25,finishedQuantity:4}}
  fixture.prepare.mockReset().mockResolvedValue(undefined);fixture.commit.mockReset().mockImplementation(async({state}:NativeDemoContext)=>state.currentStep===0?{purchaseOrder:{id:'po',factoryId:'F006',remark:state.runKey,status:'DRAFT'}}:{salesOrder:{id:'so',factoryId:'F006',remark:state.runKey,status:'DRAFT'}})
  Object.assign(nativePlayback,{enabled:false,running:false,paused:true,preparing:false,ending:false,awaitingNext:false,complete:false,index:0,error:'',remaining:0,atomicRequests:0})
  router=createRouter({history:createMemoryHistory(),routes:[{path:'/procurement/orders',component:{template:'<div />'}},{path:'/sales/orders/new',component:{template:'<div />'}}]})
  await router.push('/procurement/orders');dispose=installNativeDemoRuntime(router)
})
afterEach(()=>{dispose?.();document.body.innerHTML='';vi.useRealTimers();vi.restoreAllMocks()})

describe('native presentation requires an explicit next-module click',()=>{
  it('submits once, remains on the original page indefinitely, then advances only on next',async()=>{
    const run=startNativeDemo();await flushPromises();await vi.advanceTimersByTimeAsync(9999)
    expect(fixture.commit).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1);await run
    expect(fixture.commit).toHaveBeenCalledTimes(1)
    expect(nativePlayback.awaitingNext).toBe(true);expect(nativePlayback.paused).toBe(true)
    await vi.advanceTimersByTimeAsync(60000)
    expect(fixture.commit).toHaveBeenCalledTimes(1);expect(router.currentRoute.value.path).toBe('/procurement/orders')
    const next=nextNativeModule();await flushPromises();await vi.advanceTimersByTimeAsync(10000);await next
    expect(router.currentRoute.value.path).toBe('/sales/orders/new');expect(fixture.commit).toHaveBeenCalledTimes(2)
  })

  it('observes the real native DOM handler after shared URL rewriting and prevents its automatic route change',async()=>{
    const button=document.createElement('button');button.dataset.testid='native-submit';button.textContent='保存';document.body.append(button)
    vi.spyOn(button,'getBoundingClientRect').mockReturnValue(new DOMRect(10,100,120,40))
    button.scrollIntoView=vi.fn();Object.defineProperty(document,'elementFromPoint',{configurable:true,value:()=>button})
    button.addEventListener('click',()=>{void request.post('/F006/purchase/orders',{remark:'DEMO-F006-FLOW-20261105-unit'})})
    fixture.commit.mockImplementation(async({ui}:NativeDemoContext)=>{
      const value=await ui.capture({method:'POST',path:'/purchase/orders'},()=>ui.click('[data-testid="native-submit"]')) as DemoEntity
      ui.remember('purchaseOrder',value)
      await router.push('/sales/orders/new')
      await ui.hold('原生结果回读前仍可完成当前提交',1)
      return{purchaseOrder:value}
    })
    const run=startNativeDemo();await flushPromises();await vi.advanceTimersByTimeAsync(12000);await run
    expect(fixture.state!.value!.entities.purchaseOrder?.id).toBe('native-id')
    expect(router.currentRoute.value.path).toBe('/procurement/orders')
    expect(nativePlayback.awaitingNext).toBe(true)
    await router.push('/sales/orders/new')
    expect(router.currentRoute.value.path).toBe('/procurement/orders')
  })

  it('blocks query changes after submission and Start cannot cross the next-module gate',async()=>{
    const run=startNativeDemo();await flushPromises();await vi.advanceTimersByTimeAsync(10000);await run
    await router.push('/procurement/orders?action=create');await startNativeDemo()
    expect(router.currentRoute.value.fullPath).toBe('/procurement/orders')
    expect(fixture.commit).toHaveBeenCalledTimes(1)
    expect(nativePlayback.awaitingNext).toBe(true)
  })

  it('blocks premature native mutations outside the current commit',async()=>{
    const run=startNativeDemo();await flushPromises()
    await expect(request.post('/F006/purchase/orders',{})).rejects.toThrow('提交阶段')
    expect(fixture.state!.value!.currentStep).toBe(0)
    nativePlayback.paused=false;await vi.advanceTimersByTimeAsync(12000);await run
  })

  it('never replays a mutation after its readback or checkpoint fails',async()=>{
    fixture.commit.mockImplementation(async()=>{await request.post('/F006/purchase/orders',{});throw Error('readback lost')})
    const run=startNativeDemo();await flushPromises();await vi.advanceTimersByTimeAsync(10000);await run
    expect(nativePlayback.error).toContain('readback lost')
    const retry=startNativeDemo();await flushPromises();await retry
    expect(fixture.commit).toHaveBeenCalledTimes(1)
    expect(nativePlayback.error).toContain('避免重复单据')
    expect(fixture.state!.value!.currentStep).toBe(0)
  })

  it('restores a submitted page into the mandatory next-module gate after reload',async()=>{
    const value=fixture.state!.value!;value.currentStep=1
    sessionStorage.setItem('cretas:f006:native-demo:v1',JSON.stringify({version:1,state:value}));fixture.state!.value=null
    await startNativeDemo()
    expect(nativePlayback.awaitingNext).toBe(true);expect(nativePlayback.paused).toBe(true)
    expect(fixture.prepare).not.toHaveBeenCalled();expect(fixture.commit).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/procurement/orders')
  })

  it('refuses cleanup while an atomic native submission is in flight',async()=>{
    nativePlayback.atomicRequests=1
    await expect(endNativeDemo()).rejects.toThrow('原子提交')
    const sandbox=await import('../f006Sandbox')
    expect(sandbox.endSandbox).not.toHaveBeenCalled()
    expect(sandbox.clearEndedSandbox).not.toHaveBeenCalled()
    expect(fixture.state!.value).not.toBeNull()
  })

  it('does not advance when the native page cannot complete its actual submission',async()=>{
    fixture.commit.mockRejectedValue(new Error('native form blocked'))
    const run=startNativeDemo();await flushPromises();await vi.advanceTimersByTimeAsync(10000);await run
    expect(fixture.state!.value!.currentStep).toBe(0);expect(nativePlayback.awaitingNext).toBe(false)
    expect(nativePlayback.error).toContain('native form blocked')
  })
})
