import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { DemoState } from '../f006Flow'
import type { NativeDemoContext } from '../f006NativeBridge'

const fixture=vi.hoisted(()=>({auth:{factoryId:'F006',user:{id:1309},currentRole:'factory_super_admin'}}))
vi.mock('@/store/modules/auth',()=>({useAuthStore:()=>fixture.auth}))
vi.mock('../f006Sandbox',async()=>{const{shallowRef}=await import('vue');return{SANDBOX_FLOW_STORAGE_KEY:'unit-flow',sandboxSession:shallowRef({sessionId:'owned-session',status:'READY'})}})
import { sandboxSession } from '../f006Sandbox'
import { assertNativeDemoScope,nativeAdapterFor,nativeDemoState,useNativeDemoAdapters } from '../f006NativeBridge'

let state:DemoState
beforeEach(()=>{
 fixture.auth.factoryId='F006';fixture.auth.user.id=1309;fixture.auth.currentRole='factory_super_admin'
 sandboxSession.value={sessionId:'owned-session',status:'READY',actor:1309}
 state={schemaVersion:2,factoryId:'F006',sandboxSessionId:'owned-session',runKey:'DEMO-F006-FLOW-20261105-unit',currentStep:0,entities:{},readbacks:{},completed:[]}
 nativeDemoState.value=state
})
afterEach(()=>{nativeDemoState.value=null})
it('rechecks identity and the real page permission immediately before every native handler',async()=>{
 let writable=true
 const commit=vi.fn().mockResolvedValue(undefined)
 const Host=defineComponent({setup(){useNativeDemoAdapters('unit-page',[{step:'purchase-create',canUse:()=>writable,prepare:async()=>{},commit}]);return()=>h('div')}})
 const wrapper=mount(Host)
 try{
  const context={state} as NativeDemoContext
  const adapter=nativeAdapterFor('purchase-create',state)!
  writable=false;await expect(adapter.commit(context)).rejects.toThrow('权限');expect(commit).not.toHaveBeenCalled()
  writable=true;fixture.auth.user.id=1310;await expect(adapter.commit(context)).rejects.toThrow('身份');expect(commit).not.toHaveBeenCalled()
  fixture.auth.user.id=1309;fixture.auth.factoryId='F007';expect(()=>nativeAdapterFor('purchase-create',state)).toThrow('身份')
  fixture.auth.factoryId='F006';fixture.auth.currentRole='factory_admin';expect(()=>nativeAdapterFor('purchase-create',state)).toThrow('身份')
  fixture.auth.currentRole='factory_super_admin';sandboxSession.value!.status='ENDING';expect(()=>nativeAdapterFor('purchase-create',state)).toThrow('身份')
  sandboxSession.value!.status='READY';await adapter.commit(context);expect(commit).toHaveBeenCalledTimes(1)
 }finally{wrapper.unmount()}
 expect(nativeAdapterFor('purchase-create',state)).toBeUndefined()
})

it('rejects incompatible or out-of-program saved state before native handlers',()=>{
 state.runKey='DEMO-F006-FLOW-forged';expect(()=>assertNativeDemoScope(state)).toThrow('资料不一致')
 state.runKey='DEMO-F006-FLOW-20261105-unit';state.currentStep=-1;expect(()=>assertNativeDemoScope(state)).toThrow('资料不一致')
 state.currentStep=100;expect(()=>assertNativeDemoScope(state)).toThrow('资料不一致')
})

it('retains the live page when a temporary cached instance registers and then unmounts',async()=>{
 const commit=vi.fn().mockResolvedValue(undefined)
 const Host=defineComponent({setup(){useNativeDemoAdapters('cached-same-page',[{step:'purchase-create',canUse:()=>true,prepare:async()=>{},commit}]);return()=>h('div')}})
 const active=mount(Host),temporary=mount(Host)
 temporary.unmount()
 try{await nativeAdapterFor('purchase-create',state)!.commit({state} as NativeDemoContext);expect(commit).toHaveBeenCalledTimes(1)}finally{active.unmount()}
 expect(nativeAdapterFor('purchase-create',state)).toBeUndefined()
})

it('waits for page permissions to finish loading without invoking a native handler',()=>{
 let ready=false
 const commit=vi.fn()
 const Host=defineComponent({setup(){useNativeDemoAdapters('loading-page',[{step:'purchase-create',canUse:()=>ready,prepare:async()=>{},commit}]);return()=>h('div')}})
 const host=mount(Host)
 try{expect(nativeAdapterFor('purchase-create',state)).toBeUndefined();ready=true;expect(nativeAdapterFor('purchase-create',state)).toBeDefined();expect(commit).not.toHaveBeenCalled()}finally{host.unmount()}
})
