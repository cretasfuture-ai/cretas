export type NuxtPage = 'plans' | 'canvas' | 'gallery' | 'supply' | null
export type PanelTab = 'answer' | 'evidence' | 'history'

export interface NuxtAction {
  id: string
  label: string
  disabled?: boolean
  primary?: boolean
  help?: string
  ariaLabel?: string
  loading?: boolean
  danger?: boolean
  kind?: 'row' | 'toolbar' | 'dialog'
}

export interface NuxtSnapshot {
  page: NuxtPage
  title: string
  cost?: boolean
  actions: NuxtAction[]
  regions?: Array<{ id: string; actions: NuxtAction[] }>
  context: {
    planNumber?: string
    productName?: string
    bomVersion?: string
    workflowVersion?: string
    status?: string
    readAt?: string
  }
  panel: {
    open: boolean
    title: string
    tab: PanelTab
    busy: boolean
    bodyHtml: string
    contextHtml: string
    question: string
    historyCount: number
    width: number
    left?: number
    top?: number
  }
  overlay?: { kind: 'modal' | 'drawer'; title: string; actions: NuxtAction[]; size?: number }
}

export interface NuxtBridge {
  snapshot: () => NuxtSnapshot
  invoke: (id: string) => Promise<void>
  bindContent: (el: HTMLElement) => void
  ask: (question: string) => Promise<void>
  panelTab: (tab: PanelTab) => void
  panelClose: () => void
  refresh: () => Promise<void>
  panelLayout: (layout: { width?: number; left?: number; top?: number }) => void
  panelQuestion?: (question: string) => void
  attachOverlay: (el: HTMLElement) => void
  overlayAction: (id: string) => Promise<void>
  overlayClose: () => void
}

declare global {
  interface Window {
    CretasNuxtBridge?: NuxtBridge
  }
}

export const fallbackSnapshot = (): NuxtSnapshot => ({
  page: null,
  title: '',
  actions: [],
  context: {},
  panel: { open: false, title: '配置解读', tab: 'answer', busy: false, bodyHtml: '', contextHtml: '', question: '', historyCount: 0, width: 420 }, overlay: undefined,
})
