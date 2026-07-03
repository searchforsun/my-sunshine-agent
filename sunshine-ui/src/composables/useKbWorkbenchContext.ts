import { inject, provide, watch, type InjectionKey, type Ref, ref } from 'vue'
import type { ConfigVersionSummary } from '../api/ragAdmin'
import type { TenantId } from '../api/tenants'

/** 工作台调试/评测使用的配置来源（不影响线上 Chat published） */
export type KbConfigApplyMode = 'published' | 'version'

export interface KbAppliedConfig {
  mode: KbConfigApplyMode
  versionId: number | null
  label: string
}

export function defaultKbAppliedConfig(): KbAppliedConfig {
  return { mode: 'published', versionId: null, label: '生效版本' }
}

/** 知识库工作台上下文：`(tenantId, kbId)` + revision 驱动四 Tab 统一重载 */
export interface KbWorkbenchContext {
  tenantId: Ref<TenantId>
  kbId: Ref<string | null>
  revision: Ref<number>
  bumpRevision: () => void
  configVersions: Ref<ConfigVersionSummary[]>
  setConfigVersions: (versions: ConfigVersionSummary[]) => void
  appliedConfig: Ref<KbAppliedConfig>
  setAppliedConfig: (cfg: KbAppliedConfig) => void
  resetAppliedConfig: () => void
}

export const KB_WORKBENCH_KEY: InjectionKey<KbWorkbenchContext> = Symbol('kbWorkbench')

export function createKbWorkbenchContext(
  tenantId: Ref<TenantId>,
  kbId: Ref<string | null>,
): KbWorkbenchContext {
  const revision = ref(0)
  const configVersions = ref<ConfigVersionSummary[]>([])
  const appliedConfig = ref<KbAppliedConfig>(defaultKbAppliedConfig())

  function bumpRevision() {
    revision.value++
  }

  function setConfigVersions(versions: ConfigVersionSummary[]) {
    configVersions.value = versions
  }

  function setAppliedConfig(cfg: KbAppliedConfig) {
    appliedConfig.value = cfg
  }

  function resetAppliedConfig() {
    appliedConfig.value = defaultKbAppliedConfig()
  }

  watch(kbId, () => {
    configVersions.value = []
    resetAppliedConfig()
  })

  return {
    tenantId,
    kbId,
    revision,
    bumpRevision,
    configVersions,
    setConfigVersions,
    appliedConfig,
    setAppliedConfig,
    resetAppliedConfig,
  }
}

export function provideKbWorkbenchContext(ctx: KbWorkbenchContext) {
  provide(KB_WORKBENCH_KEY, ctx)
}

export function useKbWorkbenchContext(): KbWorkbenchContext {
  const ctx = inject(KB_WORKBENCH_KEY)
  if (!ctx) {
    throw new Error('KbWorkbenchContext 未 provide，请在 KnowledgeView 内使用')
  }
  return ctx
}

/** 单 Tab 内异步加载：revision 变更时自动 abort 进行中的请求 */
export function useKbPanelLoad(revision: Ref<number>) {
  let controller: AbortController | null = null

  function beginLoad(): AbortSignal {
    controller?.abort()
    controller = new AbortController()
    return controller.signal
  }

  function abortPanelLoad() {
    controller?.abort()
    controller = null
  }

  watch(revision, () => {
    abortPanelLoad()
  })

  return { beginLoad, abortPanelLoad }
}

/** 将 appliedConfig 转为 admin API 的 configMode / configVersionId */
export function appliedConfigToApi(applied: KbAppliedConfig): {
  configMode: string
  configVersionId?: number
} {
  if (applied.mode === 'version' && applied.versionId != null) {
    return { configMode: 'version', configVersionId: applied.versionId }
  }
  if (applied.versionId != null) {
    return { configMode: 'published', configVersionId: applied.versionId }
  }
  return { configMode: 'published' }
}
