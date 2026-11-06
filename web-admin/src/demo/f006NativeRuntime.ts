import axios, { type AxiosAdapter } from 'axios'
import { nextTick, reactive } from 'vue'
import type { Router } from 'vue-router'
import request from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'
import { assertLiveAdmin } from './f006DemoSupport'
import { createDemoState, DEMO_STEPS, initializeDemo, type DemoEntity, type DemoState } from './f006Flow'
import { beginSandbox, clearEndedSandbox, endSandbox, recoverActiveSandbox, refreshSandbox, sandboxSession } from './f006Sandbox'
import { serializeSandboxLifecycle } from './f006SandboxLifecycle'
import { assertNativeDemoScope, nativeAdapterFor, nativeDemoState, type NativeDemoStepId, type NativeDemoUi } from './f006NativeBridge'
import { assertNativeCheckpoint, verifyNativeProductionScope } from './f006NativeCheckpoint'

const STORAGE = 'cretas:f006:native-demo:v1'
const ENABLED = 'cretas:f006:native-demo-enabled:v1'
const UNVERIFIED = 'cretas:f006:native-demo-unverified:v1'
function requireVerifiedStep(): void {
  if (sessionStorage.getItem(UNVERIFIED)) throw new Error('本页已有提交尚未完成回读核验，为避免重复单据已锁定重试；请保留当前结果，结束并清空后重新演示')
}
export const nativeProgram: Array<{ id: NativeDemoStepId; label: string }> = DEMO_STEPS.flatMap(step => [
  { id: step.id as NativeDemoStepId, label: step.id === 'purchase-receive' ? '填写并暂存原生收货单' : step.label },
  ...(step.id === 'sales-order-create' ? [{ id: 'purchase-link-sales' as const, label: '原生采购草稿关联销售需求' }] : []),
  ...(step.id === 'finished-goods-receive' ? [{ id: 'supplemental-costs' as const, label: '原生费用补录与完整成本' }] : []),
])
function restoreEnabled(): boolean { try { return typeof sessionStorage !== 'undefined' && sessionStorage.getItem(ENABLED) === 'F006:1309' } catch { return false } }
export const nativePlayback = reactive({ enabled: restoreEnabled(), running: false, paused: true, preparing: false, ending: false, awaitingNext: false, complete: false, index: 0, label: '准备原生页面演示', stage: '', remaining: 0, error: '', atomicRequests: 0 })
let router: Router | undefined
let generation = 0
let interaction = 0
let expectedNavigation: string | undefined
let committing = false
let holdSubmittedPage = false
let stoppedWriteFence = false
let noActiveVerified = false
let activeRun: Promise<void> | undefined
let pendingReads = 0
let lastRead = 0
type Captured = { method: string; path: string; data: unknown; ok: boolean }
const listeners = new Set<(response: Captured) => void>()
const originalAdapters = new WeakMap<AxiosAdapter, AxiosAdapter>()
const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
function wireBody(value: unknown): unknown { if(typeof value==='string'){try{return JSON.parse(value)}catch{return value}}return value }
function unwrap(value: unknown): unknown { const body = record(value); if (body.success === false) throw new Error(String(body.message || '原生提交失败')); return typeof body.success === 'boolean' ? body.data : value }
function validActor(): boolean { const auth = useAuthStore(); return auth.factoryId === 'F006' && Number(auth.user?.id) === 1309 && auth.currentRole === 'factory_super_admin' }
function scope(): DemoState { const state = nativeDemoState.value; if (!state) throw new Error('原生演示尚未准备'); assertNativeDemoScope(state); return state }
function persist(): void { const state = nativeDemoState.value; if (state) sessionStorage.setItem(STORAGE, JSON.stringify({ version: 1, state })) }
export function enableNativeDemo(): void { if (!validActor()) throw new Error('仅允许F006演示账号'); nativePlayback.enabled = true; sessionStorage.setItem(ENABLED, 'F006:1309') }
export function pauseNativeDemo(reason = '已暂停，点击开始继续当前页面'): void { nativePlayback.paused = true; interaction++; nativePlayback.remaining = 0; nativePlayback.stage = reason }
function assertRunning(owner: number): void { if (owner !== generation) throw new Error('演示已结束或会话已改变'); scope() }
async function playPoint(owner: number): Promise<void> { assertRunning(owner); while (nativePlayback.paused) { await delay(100); assertRunning(owner) } }
function visible(element: HTMLElement): boolean { const box = element.getBoundingClientRect(); return box.width > 0 && box.height > 0 && getComputedStyle(element).visibility !== 'hidden' && !element.closest('[aria-hidden="true"]') }
function loading(): boolean { return pendingReads > 0 || Date.now() - lastRead < 500 || Array.from(document.querySelectorAll<HTMLElement>('.app-content .el-loading-mask, .el-dialog .el-loading-mask, .el-drawer .el-loading-mask')).some(visible) }
function canonicalPath(config: { url?: string; baseURL?: string }): string | undefined {
  const sid = nativeDemoState.value?.sandboxSessionId
  if (!sid) return undefined
  const url = new URL(axios.getUri(config), window.location.origin)
  const prefix = `/api/mobile/F006/demo-sandbox/sessions/${sid}/proxy/api/mobile/F006`
  if (url.origin !== window.location.origin || !url.pathname.startsWith(prefix + '/')) return undefined
  return url.pathname.slice(prefix.length)
}
function readPath(path: string): string { const clean = path.startsWith('/F006/') ? path.slice(5) : path; if (!clean.startsWith('/') || clean.includes('..') || clean.startsWith('//') || /https?:/i.test(clean)) throw new Error('原生演示读取路径不合法'); return `/F006${clean}` }
function remember(key: string, entity: DemoEntity): void { const state = scope(); if (entity.factoryId && entity.factoryId !== 'F006') throw new Error('业务回读工厂不一致'); state.entities[key] = entity; state.readbacks[key] = entity; persist() }

function createUi(owner: number): NativeDemoUi {
  const until: NativeDemoUi['until'] = async (predicate, label, timeout = 90000) => {
    let elapsed = 0
    while (true) { assertRunning(owner); if (await predicate()) return; if (elapsed >= timeout) throw new Error(`等待原生界面超时：${label}`); await delay(100); if (!nativePlayback.paused) elapsed += 100 }
  }
  const waitFor: NativeDemoUi['waitFor'] = async (selector, timeout) => {
    let found: HTMLElement | undefined
    await until(() => { const hits = Array.from(document.querySelectorAll<HTMLElement>(selector)).filter(visible); if (hits.length > 1) throw new Error(`原生控件不唯一：${selector}`); found = hits[0]; return Boolean(found) }, selector, timeout)
    return found!
  }
  const show = async (element: HTMLElement) => { element.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' }); element.classList.add('f006-native-current-field'); await delay(500); element.classList.remove('f006-native-current-field') }
  const hold: NativeDemoUi['hold'] = async (label, seconds = 10) => {
    const duration = Math.max(1, seconds)
    let remaining = duration, epoch = interaction
    nativePlayback.stage = label; nativePlayback.remaining = remaining
    while (remaining > 0) {
      await playPoint(owner)
      if (epoch !== interaction) { epoch = interaction; remaining = duration; nativePlayback.remaining=remaining;nativePlayback.stage=label }
      await delay(1000); assertRunning(owner)
      if (nativePlayback.paused || epoch !== interaction || document.hidden || loading()) continue
      remaining--; nativePlayback.remaining = remaining
    }
  }
  const navigate: NativeDemoUi['navigate'] = async path => {
    await playPoint(owner)
    if (!/^\/(procurement|sales|warehouse|production|workflow)(\/|\?|$)/.test(path)) throw new Error('不允许跳转到演示业务范围外')
    expectedNavigation = router!.resolve(path).fullPath
    try { await router!.push(path) } finally { expectedNavigation = undefined }
    await nextTick(); document.scrollingElement && (document.scrollingElement.scrollTop = 0); await until(() => !loading(), '原页面业务资料加载')
  }
  const click: NativeDemoUi['click'] = async (selector, label) => {
    await playPoint(owner); nativePlayback.stage = label || '操作原页面按钮'
    const element = await waitFor(selector)
    if (element.closest('[data-native-demo-toolbar]') || element.getAttribute('aria-disabled') === 'true' || element.classList.contains('is-disabled') || ('disabled' in element && element.disabled)) throw new Error('原页面按钮当前不可操作')
    await show(element); await playPoint(owner)
    const box = element.getBoundingClientRect(), hit = document.elementFromPoint((box.left + box.right) / 2, (box.top + box.bottom) / 2)
    if (hit && !element.contains(hit)) throw new Error('原页面控件被弹窗遮挡，请检查后继续')
    element.click(); await nextTick(); await delay(300)
  }
  return {
    fill: async (label, setter, selector) => { await playPoint(owner); nativePlayback.stage = `正在填写：${label}`; if (selector) await show(await waitFor(selector)); await playPoint(owner); await setter(); await nextTick(); await delay(700); assertRunning(owner) },
    click,
    input: async (selector, value) => {
      await playPoint(owner); const target = await waitFor(selector), input = target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement ? target : target.querySelector<HTMLInputElement | HTMLTextAreaElement>('input,textarea')
      if (!input || input.disabled || input.readOnly) throw new Error(`原生输入框不可填写：${selector}`)
      await show(input); await playPoint(owner); const prototype = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(prototype, 'value')!.set!.call(input, String(value)); input.dispatchEvent(new Event('input', {bubbles:true})); input.dispatchEvent(new Event('change', {bubbles:true})); await nextTick(); await delay(700)
    },
    select: async (selector, optionText) => { await click(selector, '展开原生选项'); await until(() => Array.from(document.querySelectorAll<HTMLElement>('[role="option"],.el-select-dropdown__item')).some(el => visible(el) && el.innerText.trim() === optionText), '原生选项'); const options = Array.from(document.querySelectorAll<HTMLElement>('[role="option"],.el-select-dropdown__item')).filter(el => visible(el) && el.innerText.trim() === optionText); if (options.length !== 1) throw new Error('原生下拉选项不唯一'); await playPoint(owner); options[0]!.click(); await nextTick() },
    upload: async (selector, file) => { await playPoint(owner); const root = document.querySelector<HTMLElement>(selector); const input = root instanceof HTMLInputElement ? root : root?.querySelector<HTMLInputElement>('input[type="file"]'); if (!root || !input || input.disabled || (!visible(root) && !root.closest('.el-upload'))) throw new Error('原生上传控件不可用'); const files = new DataTransfer(); files.items.add(file); input.files = files.files; input.dispatchEvent(new Event('change', {bubbles:true})); await nextTick(); await delay(700) },
    waitFor, until, hold, navigate,
    read: async path => { assertRunning(owner); return unwrap(await request.get(readPath(path))) },
    capture: async (match, action, timeout = 90000) => {
      let result: Captured | undefined
      const listener = (response: Captured) => { const matches = typeof match.path === 'string' ? response.path === match.path : match.path.test(response.path); if (response.method === match.method && matches) result = response }
      listeners.add(listener)
      try {
        await action()
        await until(() => { const errors = Array.from(document.querySelectorAll<HTMLElement>('.el-form-item__error')).filter(visible); if (!result && errors.length) throw new Error(errors.map(error => error.innerText).join('；')); return Boolean(result) }, '原生按钮提交与真实响应', timeout)
        if (!result!.ok) throw new Error(String(record(result!.data).message || '原生操作未成功'))
        return unwrap(result!.data)
      } finally { listeners.delete(listener) }
    },
    remember,
    receiptFile: async poNumber => {
      const state = scope(), canvas = document.createElement('canvas'); canvas.width = 1200; canvas.height = 760
      const context = canvas.getContext('2d'); if (!context) throw new Error('浏览器不能生成演示凭证')
      context.fillStyle='#fff'; context.fillRect(0,0,1200,760); context.fillStyle='#163657'; context.font='bold 42px sans-serif'; context.fillText('DEMO · 收货凭证（演示示意）',60,100); context.font='28px sans-serif'; context.fillText(`采购单：${poNumber}`,60,220); context.fillText('此图不是实际发票或真实供应商送货凭证',60,340); context.font='20px sans-serif'; context.fillText(state.runKey,60,540)
      const blob = await new Promise<Blob>((resolve,reject)=>canvas.toBlob(value=>value?resolve(value):reject(new Error('演示凭证生成失败')),'image/png'))
      return new File([blob], `${state.runKey}-receipt.png`, {type:'image/png'})
    },
  }
}

function routeFor(step: NativeDemoStepId, state: DemoState): string {
  const id = (key: string) => encodeURIComponent(String(state.entities[key]?.id || ''))
  if (step === 'purchase-create' || step === 'purchase-link-sales') return '/procurement/orders'
  if (step === 'purchase-submit') return `/procurement/orders/${id('purchaseOrder')}`
  if (step === 'purchase-approve') return '/workflow/pending?moduleCode=PURCHASE_ORDER'
  if (step === 'sales-order-create') return '/sales/orders/new'
  if (step === 'sales-order-submit') return `/sales/orders/${id('salesOrder')}`
  if (step === 'sales-order-approve') return '/workflow/pending?moduleCode=SALES_ORDER'
  if (step.startsWith('purchase-receive')) return `/warehouse/materials?view=receiving&purchaseOrderId=${id('purchaseOrder')}`
  if (step === 'material-requisition') return '/production/material-flow?tab=requisitions'
  if (step === 'supplemental-costs') return `/production/batches/${id('productionBatch')}`
  if (step.startsWith('production-') || step.startsWith('process-report-') || step === 'finished-goods-receive') return '/production/plans'
  if (step === 'sales-warehouse-confirm') return '/warehouse/shipments'
  if (step.startsWith('sales-')) return `/sales/orders/${id('salesOrder')}`
  return `/production/traceability?batchNumber=${encodeURIComponent(String(state.entities.processReport6?.batchNumber || ''))}`
}

async function readyState(owner: number): Promise<DemoState> {
  const checkOwner = () => { if (owner !== generation) throw new Error('本轮演示已停止') }
  checkOwner()
  if (!validActor()) throw new Error('当前账号不能运行F006原生演示')
  nativePlayback.preparing = true
  try {
    await serializeSandboxLifecycle(async()=>{checkOwner();if(!sandboxSession.value)await beginSandbox(request)})
    checkOwner()
    const deadline = Date.now()+420000
    while (sandboxSession.value?.status !== 'READY') { if (!sandboxSession.value || ['FAILED','ENDED'].includes(sandboxSession.value.status) || Date.now()>deadline) throw new Error('隔离演示未就绪，请结束清理后重试'); await delay(1500); checkOwner(); await serializeSandboxLifecycle(async()=>{checkOwner();await refreshSandbox(request);checkOwner()}); checkOwner() }
    const actor = await assertLiveAdmin(); checkOwner(); if (actor.id !== '1309') throw new Error('实时演示账号不一致')
    let state = nativeDemoState.value
    if (!state) {
      try { const saved=JSON.parse(sessionStorage.getItem(STORAGE)||'null'); if (saved?.version===1 && saved.state?.sandboxSessionId===sandboxSession.value.sessionId) {state=saved.state as DemoState;nativePlayback.awaitingNext=state.currentStep>0&&state.currentStep<nativeProgram.length;nativePlayback.complete=state.currentStep===nativeProgram.length;nativePlayback.index=Math.max(0,state.currentStep-1)} } catch { /* fresh local controller state, never substitute business data */ }
      state ||= createDemoState(); nativeDemoState.value=state
    }
    if(!state.context){await initializeDemo(state);checkOwner();persist()}
    assertNativeDemoScope(state); return state
  } finally { if (owner === generation) nativePlayback.preparing=false }
}
async function runCurrent(): Promise<void> {
  if (nativePlayback.running || nativePlayback.preparing || nativePlayback.awaitingNext) return
  nativePlayback.error=''; nativePlayback.paused=false
  const owner=generation
  try {
    const state=await readyState(owner); requireVerifiedStep(); if(nativePlayback.awaitingNext){nativePlayback.paused=true;nativePlayback.stage='已恢复上一页提交结果；点击下一模块才继续';return} const packet=nativeProgram[state.currentStep]
    if (!packet) { nativePlayback.complete=true; nativePlayback.stage='本轮已完成，可查看溯源和成本，再结束清空'; return }
    nativePlayback.running=true; nativePlayback.index=state.currentStep; nativePlayback.label=packet.label
    const ui=createUi(owner)
    await ui.navigate(routeFor(packet.id,state))
    if (packet.id==='traceability-read') {
      await ui.navigate('/production/traceability')
      await ui.click('[data-testid="mode-sales-order"]','按本轮销售单反向溯源')
      await ui.input('[data-testid="sales-order-input"]',String(state.entities.salesOrder?.orderNumber||''))
      await ui.click('[data-testid="sales-order-search"]','查询本轮销售单')
      await ui.until(()=>Boolean(document.querySelector('[data-testid="sales-order-anchor"]')),'销售单真实来源')
      const number=String(state.entities.processReport6?.batchNumber||'')
      const options=Array.from(document.querySelectorAll<HTMLElement>('[data-testid="sales-batch-option"]')).filter(option=>visible(option)&&option.innerText.includes(number))
      if(options.length!==1)throw new Error('销售单未返回本轮唯一成品批次')
      options[0]!.setAttribute('data-native-trace-target','true')
      await ui.click('[data-native-trace-target="true"]','展示本轮批次的原料和工序来源')
      await ui.until(()=>Boolean(document.querySelector('[data-testid="lineage-timeline"]')),'本轮真实溯源')
      const trace=record(await ui.read(`/traceability/full/${encodeURIComponent(number)}`))
      if(!Array.isArray(trace.materials)||!trace.materials.length||!Array.isArray(trace.shipments)||!trace.shipments.length)throw new Error('本轮溯源缺少真实采购来源或销售出货')
      remember('traceability',{...trace,batchNumber:number,salesOrderNumber:state.entities.salesOrder?.orderNumber,purchaseOrderNumber:state.entities.purchaseOrder?.orderNumber})
      assertRunning(owner); state.currentStep++;persist()
    }
    else {
      let adapter=undefined as ReturnType<typeof nativeAdapterFor>
      await ui.until(()=>Boolean(adapter=nativeAdapterFor(packet.id,state)), '原生页面准备')
      await adapter!.prepare({state,context:state.context!,ui})
      await ui.hold('原表单已填写，准备提交',10)
      await playPoint(owner); const actor=await assertLiveAdmin(); if(actor.id!=='1309')throw new Error('实时账号已改变')
      committing=true
      let changes: Record<string,DemoEntity>|void
      try { changes=await adapter!.commit({state,context:state.context!,ui}) } finally { committing=false }
      if(changes)for(const [key,value]of Object.entries(changes))remember(key,value)
      await verifyNativeProductionScope(packet.id,state,ui.read); assertRunning(owner)
      assertNativeCheckpoint(packet.id,state)
      state.currentStep++; persist(); sessionStorage.removeItem(UNVERIFIED)
    }
    nativePlayback.awaitingNext=state.currentStep<nativeProgram.length; nativePlayback.complete=!nativePlayback.awaitingNext
    nativePlayback.paused=true; nativePlayback.remaining=0; nativePlayback.stage=nativePlayback.complete?'本轮已完成，可查看溯源和成本，再结束清空':'本页面已提交，停留展示结果；点击“下一模块”才切换'
  } catch(error) { if(owner===generation){nativePlayback.paused=true; nativePlayback.error=error instanceof Error?error.message:'原生演示失败，已停止'} }
  finally { if(owner===generation){nativePlayback.running=false; committing=false} }
}
async function launchCurrent():Promise<void>{const task=runCurrent();activeRun=task;try{await task}finally{if(activeRun===task)activeRun=undefined}}
export async function startNativeDemo(): Promise<void> { if(nativePlayback.ending||stoppedWriteFence)throw new Error('本轮正在结束');enableNativeDemo(); if(nativePlayback.running){nativePlayback.stage='正在继续当前原生页面';nativePlayback.paused=false;interaction++;return}; await launchCurrent() }
export async function nextNativeModule(): Promise<void> { if(nativePlayback.ending||!nativePlayback.awaitingNext || nativePlayback.running)return;holdSubmittedPage=false;nativePlayback.awaitingNext=false;await launchCurrent() }
export async function finishNativeCleanup(): Promise<void> {
  if(nativePlayback.atomicRequests>0||committing||nativePlayback.preparing)throw new Error('当前提交尚未结束，不能清空')
  if(sandboxSession.value?.status!=='ENDED'&&!(sandboxSession.value===null&&noActiveVerified))throw new Error('尚未取得服务端清理完成证明')
  stoppedWriteFence=true;generation++;holdSubmittedPage=false;listeners.clear()
  if(sandboxSession.value?.status==='ENDED')clearEndedSandbox()
  nativeDemoState.value=null;sessionStorage.removeItem(STORAGE);sessionStorage.removeItem(ENABLED);sessionStorage.removeItem(UNVERIFIED)
  nativePlayback.enabled=false;nativePlayback.running=false;nativePlayback.preparing=false;nativePlayback.awaitingNext=false;nativePlayback.complete=false;nativePlayback.error=''
  // Destroy the original page's asynchronous handler realm. A sticky write fence
  // remains installed until this reload, so late UI continuations cannot hit production.
  window.location.replace('/procurement/orders')
}
export async function endNativeDemo(): Promise<void> {
  if(nativePlayback.ending)return
  if(nativePlayback.atomicRequests>0||nativePlayback.preparing){pauseNativeDemo();throw new Error('当前原子提交或准备尚未结束，请稍候再结束')}
  nativePlayback.ending=true;pauseNativeDemo('正在结束并清空');generation++
  try{
    await activeRun;nativePlayback.running=false;committing=false
    for(const button of document.querySelectorAll<HTMLElement>('.el-message-box__btns .el-button'))if(visible(button)&&['取消','关闭'].includes(button.innerText.trim()))button.click()
    const deadline=Date.now()+90000
    while(nativePlayback.atomicRequests>0||pendingReads>0){if(Date.now()>deadline)throw new Error('原生请求尚未结束，保留隔离，请稍后重试');await delay(100)}
    await serializeSandboxLifecycle(async()=>{
      if(!validActor())throw new Error('结束时账号身份已变化')
      if(!sandboxSession.value){await recoverActiveSandbox(request);if(!validActor())throw new Error('核对时账号已变化');noActiveVerified=sandboxSession.value===null}
      if(sandboxSession.value){
        const sid=sandboxSession.value.sessionId;let ended=await endSandbox(request);const deadline=Date.now()+420000
        while(ended.status!=='ENDED'){
          if(ended.status==='FAILED'||Date.now()>deadline)throw new Error('服务端清理尚未确认，保留隔离，请重试结束')
          await delay(1500);if(!validActor()||sandboxSession.value?.sessionId!==sid)throw new Error('清理时身份或会话已改变')
          ended=await refreshSandbox(request)
        }
      }
    })
    await finishNativeCleanup()
  }finally{nativePlayback.ending=false}
}

export function installNativeDemoRuntime(value: Router): () => void {
  router=value
  if(validActor()&&sessionStorage.getItem(ENABLED)==='F006:1309')nativePlayback.enabled=true
  const removeGuard=value.beforeEach((to,from)=>{if(expectedNavigation===to.fullPath){expectedNavigation=undefined;return true}if(nativePlayback.enabled&&!nativePlayback.complete&&to.fullPath!==from.fullPath&&(holdSubmittedPage||nativePlayback.running||nativePlayback.awaitingNext)){nativePlayback.stage='停留在当前原生页面，请使用“下一模块”切换';return false};return true})
  const manual=(event:Event)=>{if(event.isTrusted&&nativePlayback.running&&!(event.target instanceof Element&&event.target.closest('[data-native-demo-toolbar]')))pauseNativeDemo('已暂停：检测到人工操作，点击开始继续')}
  const visibility=()=>{if(document.hidden&&nativePlayback.running)pauseNativeDemo('已暂停：窗口切到后台，返回后点击开始继续')}
  document.addEventListener('pointerdown',manual,true);document.addEventListener('keydown',manual,true);document.addEventListener('visibilitychange',visibility)
  const interceptor=request.interceptors.request.use(config=>{
    if(stoppedWriteFence&&(config.method||'GET').toUpperCase()!=='GET'&&/\/F006\//.test(config.url||''))return Promise.reject(new Error('本轮演示已结束，请等待页面重新载入'))
    if(!nativeDemoState.value||!nativePlayback.enabled)return config
    const configured=axios.getAdapter(config.adapter||request.defaults.adapter), adapter=originalAdapters.get(configured)||configured
    const method=(config.method||'GET').toUpperCase()
    const tracked:AxiosAdapter=async actual=>{
      // Request interceptors run LIFO. Resolve only here, after the shared
      // interceptor has rewritten the URL and applied the sandbox fence.
      const path=canonicalPath(actual);if(!path)return adapter(actual)
      // These existing endpoints are page summaries/telemetry, not document submission.
      if(method==='POST'&&(/^\/list-summary\/[A-Za-z]+$/.test(path)||path==='/activity-events'||path==='/attachments/batch-3chip-counts'||/^\/production-plans\/[^/]+\/process-sheet\/input-availability$/.test(path)))return adapter(actual)
      if(method==='GET'){pendingReads++;lastRead=Date.now()}else {
        scope()
        if(!committing) { pauseNativeDemo('请通过顶部演示控制提交当前表单'); throw new Error('当前不在原生演示提交阶段，已阻止重复或提前操作') }
        sessionStorage.setItem(UNVERIFIED,JSON.stringify({sessionId:scope().sandboxSessionId,step:scope().currentStep,method,path}))
        nativePlayback.atomicRequests++
      }
      try{const response=await adapter(actual);if(method!=='GET'){const result={method,path,data:wireBody(response.data),ok:response.status<400&&record(wireBody(response.data)).success!==false};if(result.ok)holdSubmittedPage=true;for(const listener of listeners)listener(result)};return response}
      catch(error){if(method!=='GET'){const failure=error as {response?:{data?:unknown};message?:string};const result={method,path,data:wireBody(failure.response?.data)||{success:false,message:failure.message||'原生请求失败'},ok:false};for(const listener of listeners)listener(result)};throw error}
      finally{if(method==='GET'){pendingReads--;lastRead=Date.now()}else nativePlayback.atomicRequests--}
    }
    originalAdapters.set(tracked,adapter);config.adapter=tracked;return config
  })
  return()=>{generation++;holdSubmittedPage=false;pauseNativeDemo();removeGuard();if(!stoppedWriteFence)request.interceptors.request.eject(interceptor);document.removeEventListener('pointerdown',manual,true);document.removeEventListener('keydown',manual,true);document.removeEventListener('visibilitychange',visibility);nativeDemoState.value=null;nativePlayback.running=false;nativePlayback.preparing=false}
}
