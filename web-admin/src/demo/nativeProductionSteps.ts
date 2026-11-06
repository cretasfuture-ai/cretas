import { toDateString } from '@/utils/dateFormat'
import { prepareStep, type DemoEntity, type DemoStepId } from './f006Flow'
import type { NativeDemoAdapter, NativeDemoContext, NativeDemoStepId } from './f006NativeBridge'

/**
 * Native production-flow adapters.  These intentionally contain no business
 * writes: every mutation is performed by the button already rendered by the
 * corresponding Vue page.  The adapters only fill the visible controls,
 * click the existing handler, observe its response, and read the result back.
 */
type Capability = () => boolean
type AdapterOptions = {
  canWrite: Capability
  canConfirmReceiptWrite?: Capability
  canViewPrice?: Capability
  preparePlan?: NativeDemoAdapter['prepare']
  openProcessEntry?: NativeDemoAdapter['prepare']
}

const id = (value: unknown): string => String(value ?? '')
const entity = (value: unknown): DemoEntity => (value && typeof value === 'object' ? value as DemoEntity : {})
const nativeProcessCodes = new Map<string, Record<number, string>>()
const nativeProcessPrepared = new Map<string, Record<number, Record<string, unknown>>>()
const field = (selector: string, value: unknown, label: string) => async ({ ui }: NativeDemoContext) => {
  await ui.fill(label, () => undefined, selector)
  if (value !== undefined && value !== null) await ui.input(selector, String(value))
}

async function read(ui: NativeDemoContext['ui'], path: string): Promise<DemoEntity> {
  return entity(await ui.read(path))
}

/** The original time pickers are intentionally readonly: use their real panels. */
export async function selectNativeTime(ui: NativeDemoContext['ui'], selector: string, value: string): Promise<void> {
  const field=await ui.waitFor(selector)
  const input=field.querySelector<HTMLInputElement>('input')
  if(!input)throw new Error('当前工序的时间控件尚未加载')
  await ui.click(`${selector} .el-input__wrapper`, '展开原生时间选择器')
  const panel=await ui.waitFor('.el-time-panel',15000)
  const lists=panel.querySelectorAll<HTMLElement>('.el-time-spinner__list')
  if(lists.length!==2)throw new Error('原生时间选择器不是小时/分钟模式')
  const parts=value.split(':')
  for(let index=0;index<2;index++){
    const choices=Array.from(lists[index]!.querySelectorAll<HTMLElement>('.el-time-spinner__item')).filter(item=>item.textContent?.trim()===parts[index])
    if(choices.length!==1)throw new Error('原生时间选项不唯一')
    document.querySelectorAll('[data-native-clock-target]').forEach(item=>item.removeAttribute('data-native-clock-target'))
    choices[0]!.setAttribute('data-native-clock-target','current')
    await ui.click('[data-native-clock-target="current"]',index===0?'选择小时':'选择分钟')
  }
  await ui.click('.el-time-panel__btn.confirm','确认原生时间')
  await ui.until(()=>input.value===value,'时间控件实际保存选择')
}

async function closeSubmittedProcessDrawer(ui: NativeDemoContext['ui']): Promise<void> {
  const headings=Array.from(document.querySelectorAll<HTMLElement>('[data-testid="process-sheet-product-name"]')).filter(node=>node.getBoundingClientRect().width>0)
  if(!headings.length)return
  if(headings.length!==1)throw new Error('当前报工窗口不唯一，请核对原页面')
  const heading=headings[0]!
  const close=heading.closest('.el-drawer')?.querySelector<HTMLElement>('.el-drawer__close-btn')
  if(!close)throw new Error('原报工窗口没有关闭按钮')
  close.setAttribute('data-native-process-close','true')
  await ui.click('[data-native-process-close="true"]','关闭已提交工序结果')
  await ui.until(()=>heading.getBoundingClientRect().width===0,'原报工窗口关闭')
}

function adapter(step: NativeDemoStepId, canUse: Capability, prepare: NativeDemoAdapter['prepare'], commit: NativeDemoAdapter['commit']): NativeDemoAdapter {
  return { step, canUse, prepare, commit }
}

export function createNativeProductionAdapters(options: AdapterOptions, requestedSteps?: readonly NativeDemoStepId[]): NativeDemoAdapter[] {
  const write = options.canWrite
  const receipt = options.canConfirmReceiptWrite ?? write
  const price = options.canViewPrice ?? write
  const plan = (step: DemoStepId): NativeDemoAdapter => adapter(step, write,
    async packet => {
      const {ui,state}=packet
      if (step === 'production-plan-create') {
        if(!options.preparePlan)throw new Error('当前页面没有接入原生排产表单')
        await options.preparePlan(packet)
        await ui.input('[data-testid="production-plan-planned-date"]', toDateString(new Date()))
        await ui.input('[data-testid="production-plan-batch-date"]', toDateString(new Date()))
        await ui.input('[data-testid="production-plan-notes"]', state.runKey)
      } else {
        await ui.navigate('/production/plans')
      }
    },
    async ({ state, context, ui }) => {
      if (step === 'production-plan-create') {
        const response = await ui.capture({ method: 'POST', path: '/production-plans/batch-from-so' }, () => ui.click('[data-testid="production-plan-submit"]', '确定'))
        const envelope = entity(response)
        const value = entity(Array.isArray(response) ? response[0] : Array.isArray(envelope.data) ? envelope.data[0] : envelope.data || response)
        const plan = await read(ui, `/production-plans/${id(value.id || value.productionPlanId)}`)
        ui.remember('productionPlan', plan); return { productionPlan: plan }
      }
      const planId = id(state.entities.productionPlan?.id)
      const response = await ui.capture({ method: 'POST', path: /production-plans\/[^/]+\/create-batch/ }, async () => {
        await ui.click(`[data-testid="production-plan-create-batch-${planId}"]`, '下发APP报工')
      })
      const value = entity(response)
      const batch = await read(ui, `/processing/batches/${id(value.id || value.productionBatchId || value.batchId)}`)
      if (id(batch.productionPlanId) !== planId) throw new Error('生产批次与本次生产计划不一致')
      ui.remember('productionBatch', batch); return { productionBatch: batch }
    })

  const requisition = adapter('material-requisition', write,
    async ({ ui, state, context }) => { await ui.navigate('/production/material-flow?tab=requisitions'); await ui.click('[data-testid="material-requisition-create"]', '按计划生成'); await ui.click('[data-testid="material-requisition-plan"] .el-select__wrapper','展开本轮领料计划'); await ui.click(`[data-testid="material-requisition-plan-option-${id(state.entities.productionPlan?.id)}"]`,'选择本轮真实生产计划'); await ui.input('[data-testid="material-requisition-amount"]', String(context.rawQuantity)) },
    async ({ state, context, ui }) => {
      await ui.click('[data-testid="material-requisition-generate"]', '生成领料单')
      await ui.hold('展示领料单生成结果')
      const rows = await ui.read('/material-requisitions') as { content?: unknown[] } | unknown[]
      const list = Array.isArray(rows) ? rows : (rows.content ?? [])
      const row = entity(list.find((v) => id((v as DemoEntity).productionPlanId) === id(state.entities.productionPlan?.id)))
      if (!row.id) throw new Error('未读取到本次生产计划的领料单')
      ui.remember('materialRequisition', row)
      await ui.click(`[data-testid="material-requisition-start-${id(row.id)}"]`, '开始备料')
      await ui.click('.el-message-box__btns .el-button--primary', '确认开始备料')
      await ui.hold('展示备料状态')
      await ui.click(`[data-testid="material-requisition-confirm-${id(row.id)}"]`, '打开确认领料')
      for (const item of Array.isArray(row.items) ? row.items : []) {
        const preset=id(item.materialTypeId)===id(context.material.id)?context.rawQuantity:id(item.materialTypeId)===id(context.packaging.id)?context.finishedQuantity:item.requiredQty??item.requestedQty??item.quantity
        if(preset==null||!Number.isFinite(Number(preset))||Number(preset)<0)throw new Error('本轮领料存在未设定实际数量的物料，请核对原页面')
        await ui.input(`[data-testid="picked-input-${id(item.id)}"]`,String(preset))
      }
      await ui.capture({ method: 'PUT', path: /material-requisitions\/[^/]+\/confirm-picking/ }, () => ui.click('[data-testid="material-requisition-submit"]', '确认领料'))
      await ui.hold('展示领料数量')
      await ui.click(`[data-testid="material-requisition-transfer-${id(row.id)}"]`, '调拨到生产仓')
      await ui.click('.el-message-box__btns .el-button--primary', '确认调拨')
      await ui.hold('展示调拨状态')
      await ui.click(`[data-testid="material-requisition-receive-${id(row.id)}"]`, '生产仓签收')
      await ui.click('.el-message-box__btns .el-button--primary', '确认签收')
      await ui.hold('展示签收状态')
      const fresh = entity(await ui.read(`/material-requisitions/${id(row.id)}`))
      ui.remember('materialRequisition', fresh); return { materialRequisition: fresh }
    })

  const process = (n: 1 | 2 | 3 | 4 | 5 | 6): NativeDemoAdapter => adapter(`process-report-${n}`,
    write,
    async packet => {
      const {ui,state}=packet
      if(!options.openProcessEntry)throw new Error('当前页面没有接入原生工序报工表单')
      await options.openProcessEntry(packet)
      await ui.hold('展示原有报工界面',1)
      const modes=await ui.waitFor('[data-testid="process-sheet-view-mode"]')
      const cards=Array.from(modes.querySelectorAll<HTMLElement>('label')).filter(label=>label.textContent?.trim()==='卡片')
      if(cards.length!==1)throw new Error('原页面缺少唯一的卡片报工切换')
      cards[0]!.setAttribute('data-native-process-card','true')
      await ui.click('[data-native-process-card="true"]','切换原有卡片报工视图')
      await ui.click(`[data-testid="process-tab-${n}"]`, `打开工序${n}`)
      const node = await ui.waitFor(`[data-testid="process-report-${n}"]`, 15000)
      const processCode = node.getAttribute('data-process-code')
      if (!processCode) throw new Error(`工序${n}未读取真实 workflow processCode`)
      await ui.hold('展示本工序录入界面',1)
      if(!node.querySelector('[data-testid="production-date"]'))await ui.click('[data-testid="add-process-row"]','新增本工序原生报工行')
      const codes = nativeProcessCodes.get(state.runKey) || {}
      codes[n] = processCode; nativeProcessCodes.set(state.runKey, codes)
      const values = Object.fromEntries(prepareStep(state, `process-report-${n}` as DemoStepId).fields.map((item) => [item.name, item.value]))
      const prepared = nativeProcessPrepared.get(state.runKey) || {}; prepared[n] = values; nativeProcessPrepared.set(state.runKey, prepared)
      await ui.input('[data-testid="production-date"]', toDateString(new Date()))
      if (n === 1) await ui.input('[data-testid="material-input-total"]', String(values.inputQuantity))
      else {
        const inputSelector=node.querySelector('[data-testid="single-source-input-quantity"]')?'[data-testid="single-source-input-quantity"]':node.querySelector('[data-testid="finished-input-kg"]')?'[data-testid="finished-input-kg"]':'[data-testid="input-source-quantity"]'
        await ui.input(inputSelector,String(values.inputQuantity))
      }
      await ui.input('[data-testid="output-quantity"]', String(values.outputQuantity))
      await selectNativeTime(ui,'[data-testid="output-start-time"]', `08:${String(n*3).padStart(2,'0')}`)
      await selectNativeTime(ui,'[data-testid="output-end-time"]', `08:${String(n*3+2).padStart(2,'0')}`)
      await ui.input('[data-testid="output-worker-count"]', String(values.workerCount))
    },
    async ({ state, context, ui }) => {
      const processCode = nativeProcessCodes.get(state.runKey)?.[n]
      if (!processCode) throw new Error(`工序${n}缺少真实 workflow processCode`)
      await ui.capture({ method: 'POST', path: /\/process-sheet\/row\/submit$/ }, async () => {
        await ui.click('[data-testid="process-report-submit"]', `提交工序${n}`)
        const confirmation=await ui.waitFor('.el-message-box')
        if(!confirmation.querySelector('.el-message-box__title')?.textContent?.includes('确认正式报工'))throw new Error('报工存在其他待核对事项，请先核对原页面')
        await ui.click('.el-message-box__btns .el-button--primary','确认原页面正式报工')
      })
      await ui.hold(`展示工序${n}提交结果`)
      const rows = await ui.read(`/production-plans/${id(state.entities.productionPlan?.id)}/process-sheet/rows?process=${encodeURIComponent(processCode)}&processOrder=${n}`)
      const list = Array.isArray(rows) ? rows : entity(rows).data
      if (!Array.isArray(list)) throw new Error(`工序${n}回读格式无效`)
      const result = list.find((row) => id(entity(row).clientRowId) === `${state.runKey}:p${n}`) as DemoEntity | undefined
      if (!result || result.submissionStatus !== 'SUBMITTED') throw new Error(`工序${n}未回读 SUBMITTED 真实行`)
      ui.remember(`processReport${n}`, result); return { [`processReport${n}`]: result }
    })

  const settle = adapter('production-settle', write,
    async ({ ui, state }) => { const planId = id(state.entities.productionPlan?.id); await ui.navigate('/production/plans'); await closeSubmittedProcessDrawer(ui); await ui.click(`[data-testid="production-settle-${planId}"]`, '核对结单'); await ui.waitFor('[data-testid="production-settle-dialog"]', 15000); const prefill=entity(await ui.read(`/production-plans/${planId}/settlement-prefill`)); if(entity(prefill.audit).clean!==true)throw new Error('本轮报工未通过结单审计，已停止原生演示'); await ui.until(() => !document.querySelector('[data-testid="settlement-blocker"]'), '结单 clean prefill') },
    async ({ state, ui }) => {
      const planId = id(state.entities.productionPlan?.id)
      const response = await ui.capture({ method: 'POST', path: /production-plans\/[^/]+\/settle/ }, async () => {
        await ui.click('[data-testid="production-settle-dialog-submit"]', '确认结单并结束计划')
      })
      const value = entity(response)
      const settlement = await read(ui, `/production-plans/${id(state.entities.productionPlan?.id)}/settlement`)
      if (id(settlement.status || settlement.postingStatus) === 'BLOCKER' || id(settlement.status) === 'SHORTAGE_CASE_OPEN') throw new Error('结单存在阻断项，原生演示已停止')
      ui.remember('productionSettlement', settlement); return { productionSettlement: { ...value, ...settlement } }
    })

  const receive = adapter('finished-goods-receive', receipt,
    async ({ ui, state }) => { const planId = id(state.entities.productionPlan?.id); await ui.navigate('/production/plans'); await ui.click(`[data-testid="finished-goods-receive-${planId}"]`, '确认入库'); await ui.waitFor('[data-testid="finished-goods-receive-dialog"]', 15000); await ui.input('[data-testid="native-receipt-quantity"]', String(state.context?.finishedQuantity ?? 0)) },
    async ({ state, ui }) => {
      const planId = id(state.entities.productionPlan?.id)
      const response = await ui.capture({ method: 'POST', path: /production-plans\/[^/]+\/warehouse-receipt/ }, async () => {
        await ui.click('[data-testid="finished-goods-receive-dialog-submit"]', '确认入库')
      })
      const value = entity(response)
      const settlement = await read(ui, `/production-plans/${id(state.entities.productionPlan?.id)}/settlement`)
      if (!['POSTED', 'POSTED_WITH_TOLERANCE'].includes(id(settlement.postingStatus))) throw new Error('成品入库尚未回读 POSTED')
      ui.remember('productionWarehouseReceipt', value); return { productionWarehouseReceipt: { ...value, ...settlement } }
    })

  const costs = adapter('supplemental-costs', price,
    async ({ ui }) => { await ui.waitFor('[data-testid="supplemental-costs-open"]', 15000); await ui.click('[data-testid="supplemental-costs-open"]', '补录费用'); await ui.input('[data-testid="supplemental-equipment-cost"]', '12.8'); await ui.input('[data-testid="supplemental-other-cost"]', '7.2') },
    async ({ ui, state }) => {
      await ui.capture({ method: 'PUT', path: /production-plans\/[^/]+\/costs/ }, () => ui.click('[data-testid="supplemental-costs-submit"]', '保存并重新核算'))
      await ui.hold('展示补录费用完成结果')
      const cost = await read(ui, `/production/batches/${id(state.entities.productionBatch?.batchNumber)}/cost-breakdown`)
      if (id(cost.calculationStatus) !== 'COMPLETE' || cost.costComplete !== true || Number(cost.missingCostItemCount) !== 0) throw new Error('补录费用未完成成本核算')
      ui.remember('supplementalCosts', cost); return { supplementalCosts: cost }
    })

  const all = [plan('production-plan-create'), plan('production-batch-create'), requisition, process(1), process(2), process(3), process(4), process(5), process(6), settle, receive, costs]
  return requestedSteps ? all.filter((item) => requestedSteps.includes(item.step)) : all
}
