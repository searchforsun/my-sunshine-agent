import { computed, reactive } from 'vue'

/** 工作区标题栏下方统一错误/提示行的来源（按优先级展示一条） */
export type WorkspaceBannerSource = 'sync' | 'tree' | 'diff-summary' | 'diff' | 'git'

export type WorkspaceBannerKind = 'error' | 'info'

export type WorkspaceBannerPayload = {
  text: string
  kind: WorkspaceBannerKind
  /** 展示「重试」按钮（clone/同步失败） */
  retryable?: boolean
}

const PRIORITY: readonly WorkspaceBannerSource[] = [
  'sync',
  'tree',
  'diff-summary',
  'diff',
  'git',
]

const slots = reactive<Partial<Record<WorkspaceBannerSource, WorkspaceBannerPayload>>>({})
const flashTimers = new Map<WorkspaceBannerSource, ReturnType<typeof setTimeout>>()

/** 当前应展示的一条横幅（高优先级覆盖低优先级） */
export const workspaceBanner = computed(() => {
  for (const source of PRIORITY) {
    const item = slots[source]
    if (item?.text) return { source, ...item }
  }
  return null
})

export function setWorkspaceBanner(
  source: WorkspaceBannerSource,
  payload: WorkspaceBannerPayload,
): void {
  const timer = flashTimers.get(source)
  if (timer) {
    clearTimeout(timer)
    flashTimers.delete(source)
  }
  const text = payload.text.trim()
  if (!text) {
    delete slots[source]
    return
  }
  slots[source] = {
    text,
    kind: payload.kind,
    retryable: payload.retryable,
  }
}

export function clearWorkspaceBanner(source?: WorkspaceBannerSource): void {
  if (source) {
    const timer = flashTimers.get(source)
    if (timer) {
      clearTimeout(timer)
      flashTimers.delete(source)
    }
    delete slots[source]
    return
  }
  for (const key of PRIORITY) {
    clearWorkspaceBanner(key)
  }
}

/** 短暂提示（git 菜单操作等），到期自动清除该来源 */
export function flashWorkspaceBanner(
  source: WorkspaceBannerSource,
  payload: WorkspaceBannerPayload,
  ms = 2800,
): void {
  setWorkspaceBanner(source, payload)
  const timer = setTimeout(() => {
    flashTimers.delete(source)
    delete slots[source]
  }, ms)
  flashTimers.set(source, timer)
}
