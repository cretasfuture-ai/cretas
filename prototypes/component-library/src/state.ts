import { reactive, watch } from 'vue'
import type { PageId, Plan, Role } from './types'

const settings = (() => { try { return JSON.parse(localStorage.getItem('cretas-v5-settings') || '{}') } catch { return {} } })()
export const state = reactive({
  role: (settings.role === 'platform' ? 'platform' : 'factory') as Role,
  page: (settings.role === 'platform' ? 'platform' : 'plans') as PageId,
  compare: false, dense: settings.dense ?? false,
  sidebarCollapsed: settings.sidebarCollapsed ?? false, mobileMenu: false, leaveOpen: false,
  textSize: settings.textSize === 'large' ? 'large' : 'standard',
  motion: settings.motion ?? (matchMedia('(prefers-reduced-motion: reduce)').matches ? 'off' : 'standard'),
  duration: Number(settings.duration) || 220,
  searchOpen: false, menusOpen: false, settingsOpen: false, changesOpen: false,
  message: '', selectedTenant: 'F006', planOverrides: {} as Record<string, Partial<Plan>>
})
try { state.planOverrides = JSON.parse(localStorage.getItem('cretas-v5-plans') || '{}') } catch { /* preserve source */ }
let timer: ReturnType<typeof setTimeout>
export function notify(message: string) { state.message = message; clearTimeout(timer); timer = setTimeout(() => { state.message = '' }, 4200) }
type EditGuard = { dirty: () => boolean; save: () => boolean | Promise<boolean>; discard?: () => void }
let editGuard: EditGuard | null = null, pendingAction: (() => void) | null = null
export function setEditGuard(guard: EditGuard | null) { editGuard = guard }
export function protectEdit(action: () => void) { if (editGuard?.dirty()) { pendingAction = action; state.leaveOpen = true } else action() }
export async function resolveLeave(save: boolean) { if (save && editGuard && !await editGuard.save()) return; if (!save) editGuard?.discard?.(); const action = pendingAction; pendingAction = null; state.leaveOpen = false; action?.() }
export function cancelLeave() { pendingAction = null; state.leaveOpen = false }
export function navigate(page: PageId) { if (page === state.page) { state.searchOpen = false; return }; protectEdit(() => { state.page = page; state.role = page === 'platform' ? 'platform' : 'factory'; state.searchOpen = false; state.mobileMenu = false }) }
export function switchRole(role: Role) { protectEdit(() => { state.role = role; state.page = role === 'platform' ? 'platform' : 'plans'; state.compare = false; state.mobileMenu = false }) }
export function savePlan(id: string, patch: Partial<Plan>) {
  const next = { ...state.planOverrides, [id]: { ...state.planOverrides[id], ...patch, localDraft: true } }
  try { localStorage.setItem('cretas-v5-plans', JSON.stringify(next)); state.planOverrides = next; notify('已保存本机演示草稿，可刷新查看；源账号记录未修改。'); return true }
  catch { notify('本机存储不可用，未保存修改。请检查浏览器存储设置后重试。'); return false }
}
watch(() => [state.role, state.dense, state.motion, state.duration, state.sidebarCollapsed, state.textSize], () => {
  try { localStorage.setItem('cretas-v5-settings', JSON.stringify({ role: state.role, dense: state.dense, motion: state.motion, duration: state.duration, sidebarCollapsed: state.sidebarCollapsed, textSize: state.textSize })) } catch { /* preferences remain usable in memory */ }
  document.documentElement.dataset.textSize = state.textSize
  document.documentElement.dataset.motion = state.motion
  document.documentElement.style.setProperty('--motion-duration', `${state.motion === 'off' ? 0 : state.motion === 'light' ? 100 : state.duration}ms`)
}, { immediate: true })
