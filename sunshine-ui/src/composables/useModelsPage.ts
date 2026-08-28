import { computed, h, ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NSwitch, useMessage, type DataTableColumns } from 'naive-ui'
import { friendlyErrorMessage } from '../api/apiError'
import {
  catalogEnabledModelOptions,
  createModelDefinition,
  createModelProvider,
  deleteModelDefinition,
  deleteModelProvider,
  deleteModelRoute,
  emptyCapabilities,
  emptyRequestExtrasDraft,
  listModelDefinitions,
  listModelProviders,
  listModelRouteKeys,
  listModelRoutes,
  listModelSceneKeys,
  listModelScenes,
  parseRequestExtrasDraft,
  resolveMaxCompletionTokens,
  buildRequestExtras,
  toggleModelDefinition,
  updateModelDefinition,
  updateModelProvider,
  upsertModelRoute,
  upsertModelScenes,
  type ModelCapabilities,
  type ModelDefinition,
  type ModelProvider,
  type ModelRoute,
  type ModelRouteKeyMeta,
  type ModelScene,
  type ModelSceneKeyMeta,
} from '../api/models'
import { useModelsRouteState, type ModelsTab } from './useModelsRouteState'

export type { ModelsTab }

function boolSelectValue(v: boolean | null): 'true' | 'false' | null {
  if (v === null) return null
  return v ? 'true' : 'false'
}

function boolDraftValue(v: 'true' | 'false' | null): boolean | null {
  if (v === null) return null
  return v === 'true'
}

export function useModelsPage() {
  const message = useMessage()
  const route = useRoute()
  const routeState = useModelsRouteState()
  const loading = ref(false)
  const saving = ref(false)
  const activeTab = ref<ModelsTab>(routeState.readTab())
  // 非法 ?tab= 归一到合法 tab（刷新可重定向）
  routeState.syncQuery({ tab: activeTab.value })

  const providers = ref<ModelProvider[]>([])
  const definitions = ref<ModelDefinition[]>([])
  const scenes = ref<ModelScene[]>([])
  const sceneKeys = ref<ModelSceneKeyMeta[]>([])
  const routes = ref<ModelRoute[]>([])
  const routeKeys = ref<ModelRouteKeyMeta[]>([])

  const showProviderModal = ref(false)
  const providerEditId = ref<number | null>(null)
  const providerDraft = ref({
    providerKey: '',
    displayName: '',
    protocol: 'openai-compatible',
    baseUrl: '',
    pathPrefix: '',
    apiKey: '',
    enabled: true,
  })

  const showDefinitionModal = ref(false)
  const definitionEditId = ref<number | null>(null)
  const definitionDraft = ref({
    providerKey: '',
    modelName: '',
    displayName: '',
    contextWindow: 32768,
    maxOutputTokens: 8192,
    encoding: 'cl100k_base',
    capabilities: emptyCapabilities(),
    requestExtras: emptyRequestExtrasDraft(),
    userSelectable: true,
    enabled: true,
    sortOrder: 0,
  })

  const boolParamOptions = [
    { label: 'true', value: 'true' as const },
    { label: 'false', value: 'false' as const },
  ]
  const thinkingTypeOptions = [
    { label: 'adaptive', value: 'adaptive' },
    { label: 'disabled', value: 'disabled' },
  ]
  const serviceTierOptions = [
    { label: 'standard', value: 'standard' },
    { label: 'priority', value: 'priority' },
  ]

  // NSelect value 仅支持 string/number；boolean 经字符串桥转换，运行时仍写回 boolean
  const reasoningSplitSelectValue = computed({
    get: () => boolSelectValue(definitionDraft.value.requestExtras.reasoning_split),
    set: (v) => {
      definitionDraft.value.requestExtras.reasoning_split = boolDraftValue(v)
    },
  })
  const includeUsageSelectValue = computed({
    get: () => boolSelectValue(definitionDraft.value.requestExtras.stream_options_include_usage),
    set: (v) => {
      definitionDraft.value.requestExtras.stream_options_include_usage = boolDraftValue(v)
    },
  })

  const showSceneModal = ref(false)
  const sceneEditKey = ref<string | null>(null)
  const sceneDraft = ref({
    sceneKey: '',
    primaryModel: '',
    fallbackModel: null as string | null,
    extrasText: '',
    enabled: true,
    remark: '',
  })

  const showRouteModal = ref(false)
  const routeEditKey = ref<string | null>(null)
  const routeDraft = ref({
    callSite: '',
    models: [] as string[],
    enabled: true,
    remark: '',
  })

  const showDeleteConfirm = ref(false)
  const deleteTarget = ref<{ kind: 'provider' | 'definition' | 'route'; id: number; label: string } | null>(null)

  const providerOptions = computed(() =>
    providers.value.map((p) => ({ label: `${p.displayName} (${p.providerKey})`, value: p.providerKey })),
  )

  const modelSelectOptions = computed(() =>
    catalogEnabledModelOptions({
      providers: [],
      definitions: definitions.value.map((d) => ({
        modelName: d.modelName,
        providerKey: d.providerKey,
        displayName: d.displayName,
        contextWindow: d.contextWindow,
        encoding: d.encoding,
        capabilities: d.capabilities,
        userSelectable: d.userSelectable,
        enabled: d.enabled,
        sortOrder: d.sortOrder,
      })),
      scenes: [],
    }),
  )

  /** 新建时仅可选尚未绑定的枚举场景 */
  const availableSceneKeyOptions = computed(() => {
    const bound = new Set(scenes.value.map((s) => s.sceneKey))
    return sceneKeys.value
      .filter((k) => !bound.has(k.sceneKey))
      .map((k) => ({
        label: k.sceneKey,
        value: k.sceneKey,
        description: k.description,
      }))
  })

  const canCreateScene = computed(() => availableSceneKeyOptions.value.length > 0)

  /** 新建时仅可选尚未配置策略的枚举调用点 */
  const availableRouteKeyOptions = computed(() => {
    const bound = new Set(routes.value.map((r) => r.callSite))
    return routeKeys.value
      .filter((k) => !bound.has(k.key))
      .map((k) => ({
        label: k.key,
        value: k.key,
        description: k.description,
      }))
  })

  const canCreateRoute = computed(() => availableRouteKeyOptions.value.length > 0)

  const routeDraftDescription = computed(() => {
    const key = routeDraft.value.callSite
    return routeKeys.value.find((k) => k.key === key)?.description ?? ''
  })

  const sceneDraftDescription = computed(() => {
    const key = sceneDraft.value.sceneKey
    const meta = sceneKeys.value.find((k) => k.sceneKey === key)
      || (scenes.value.find((s) => s.sceneKey === key)
        ? { description: scenes.value.find((s) => s.sceneKey === key)!.description } : null)
    return meta?.description ?? ''
  })

  async function refreshPage() {
    loading.value = true
    try {
      const [p, d, s, keys, r, rk] = await Promise.all([
        listModelProviders(),
        listModelDefinitions(),
        listModelScenes(),
        listModelSceneKeys(),
        listModelRoutes(),
        listModelRouteKeys(),
      ])
      providers.value = p
      definitions.value = d
      scenes.value = s
      sceneKeys.value = keys
      routes.value = r
      routeKeys.value = rk
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载失败'))
    } finally {
      loading.value = false
    }
  }

  function openCreateProvider() {
    providerEditId.value = null
    providerDraft.value = {
      providerKey: '',
      displayName: '',
      protocol: 'openai-compatible',
      baseUrl: '',
      pathPrefix: '',
      apiKey: '',
      enabled: true,
    }
    showProviderModal.value = true
  }

  function openEditProvider(row: ModelProvider) {
    providerEditId.value = row.id
    providerDraft.value = {
      providerKey: row.providerKey,
      displayName: row.displayName,
      protocol: row.protocol || 'openai-compatible',
      baseUrl: row.baseUrl,
      pathPrefix: row.pathPrefix ?? '',
      apiKey: '',
      enabled: row.enabled,
    }
    showProviderModal.value = true
  }

  async function submitProvider() {
    const d = providerDraft.value
    if (!d.displayName.trim() || !d.baseUrl.trim()) {
      message.warning('请填写显示名与 base_url')
      return
    }
    if (providerEditId.value == null && !d.providerKey.trim()) {
      message.warning('请填写 provider_key')
      return
    }
    if (providerEditId.value == null && !d.apiKey.trim()) {
      message.warning('新建须填写 api_key')
      return
    }
    saving.value = true
    try {
      if (providerEditId.value == null) {
        await createModelProvider({
          providerKey: d.providerKey.trim(),
          displayName: d.displayName.trim(),
          protocol: d.protocol.trim() || 'openai-compatible',
          baseUrl: d.baseUrl.trim(),
          pathPrefix: d.pathPrefix,
          apiKey: d.apiKey,
          enabled: d.enabled,
        })
      } else {
        await updateModelProvider(providerEditId.value, {
          displayName: d.displayName.trim(),
          protocol: d.protocol.trim() || 'openai-compatible',
          baseUrl: d.baseUrl.trim(),
          pathPrefix: d.pathPrefix,
          apiKey: d.apiKey.trim() || undefined,
          enabled: d.enabled,
        })
      }
      message.success('已保存')
      showProviderModal.value = false
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存失败'))
    } finally {
      saving.value = false
    }
  }

  function openCreateDefinition() {
    definitionEditId.value = null
    definitionDraft.value = {
      providerKey: providers.value[0]?.providerKey ?? '',
      modelName: '',
      displayName: '',
      contextWindow: 32768,
      maxOutputTokens: 8192,
      encoding: 'cl100k_base',
      capabilities: emptyCapabilities(),
      requestExtras: emptyRequestExtrasDraft(),
      userSelectable: true,
      enabled: true,
      sortOrder: 0,
    }
    showDefinitionModal.value = true
  }

  function openEditDefinition(row: ModelDefinition) {
    definitionEditId.value = row.id
    definitionDraft.value = {
      providerKey: row.providerKey,
      modelName: row.modelName,
      displayName: row.displayName,
      contextWindow: row.contextWindow,
      maxOutputTokens: resolveMaxCompletionTokens(row.requestExtras, row.maxOutputTokens),
      encoding: row.encoding || 'cl100k_base',
      capabilities: { ...row.capabilities },
      requestExtras: parseRequestExtrasDraft(row.requestExtras),
      userSelectable: row.userSelectable,
      enabled: row.enabled,
      sortOrder: row.sortOrder,
    }
    showDefinitionModal.value = true
  }

  async function submitDefinition() {
    const d = definitionDraft.value
    if (!d.providerKey.trim() || !d.modelName.trim() || !d.displayName.trim()) {
      message.warning('请填写供应商、模型名、显示名')
      return
    }
    saving.value = true
    try {
      const body = {
        providerKey: d.providerKey.trim(),
        modelName: d.modelName.trim(),
        displayName: d.displayName.trim(),
        contextWindow: d.contextWindow,
        maxOutputTokens: d.maxOutputTokens,
        encoding: d.encoding.trim() || 'cl100k_base',
        capabilities: d.capabilities as ModelCapabilities,
        requestExtras: buildRequestExtras(d.requestExtras, d.maxOutputTokens),
        userSelectable: d.userSelectable,
        enabled: d.enabled,
        sortOrder: d.sortOrder,
      }
      if (definitionEditId.value == null) {
        await createModelDefinition(body)
      } else {
        await updateModelDefinition(definitionEditId.value, body)
      }
      message.success('已保存')
      showDefinitionModal.value = false
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存失败'))
    } finally {
      saving.value = false
    }
  }

  async function handleToggleDefinition(row: ModelDefinition) {
    try {
      await toggleModelDefinition(row.id)
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换失败'))
    }
  }

  async function handleToggleUserSelectable(row: ModelDefinition) {
    try {
      await updateModelDefinition(row.id, {
        providerKey: row.providerKey,
        modelName: row.modelName,
        displayName: row.displayName,
        contextWindow: row.contextWindow,
        maxOutputTokens: resolveMaxCompletionTokens(row.requestExtras, row.maxOutputTokens),
        encoding: row.encoding,
        capabilities: row.capabilities,
        requestExtras: buildRequestExtras(
          parseRequestExtrasDraft(row.requestExtras),
          resolveMaxCompletionTokens(row.requestExtras, row.maxOutputTokens),
        ),
        userSelectable: !row.userSelectable,
        enabled: row.enabled,
        sortOrder: row.sortOrder,
      })
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换失败'))
    }
  }

  async function handleToggleProvider(row: ModelProvider) {
    try {
      await updateModelProvider(row.id, {
        displayName: row.displayName,
        protocol: row.protocol,
        baseUrl: row.baseUrl,
        pathPrefix: row.pathPrefix,
        enabled: !row.enabled,
      })
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换失败'))
    }
  }

  async function handleToggleScene(row: ModelScene) {
    try {
      await upsertModelScenes({
        sceneKey: row.sceneKey,
        primaryModel: row.primaryModel,
        fallbackModel: row.fallbackModel,
        extras: row.extras,
        enabled: !row.enabled,
      })
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换失败'))
    }
  }

  function openEditScene(row: ModelScene) {
    sceneEditKey.value = row.sceneKey
    sceneDraft.value = {
      sceneKey: row.sceneKey,
      primaryModel: row.primaryModel,
      fallbackModel: row.fallbackModel,
      extrasText: row.extras ? JSON.stringify(row.extras, null, 2) : '',
      enabled: row.enabled,
      remark: row.remark ?? '',
    }
    showSceneModal.value = true
  }

  function openCreateScene() {
    if (!canCreateScene.value) {
      message.info('全部枚举场景均已绑定')
      return
    }
    const first = availableSceneKeyOptions.value[0]
    sceneEditKey.value = null
    sceneDraft.value = {
      sceneKey: first?.value ?? '',
      primaryModel: '',
      fallbackModel: null,
      extrasText: '',
      enabled: true,
      remark: '',
    }
    showSceneModal.value = true
  }

  function openCreateRoute() {
    if (!canCreateRoute.value) {
      message.info('全部枚举调用点均已配置')
      return
    }
    const first = availableRouteKeyOptions.value[0]
    routeEditKey.value = null
    routeDraft.value = {
      callSite: first?.value ?? '',
      models: [],
      enabled: true,
      remark: '',
    }
    showRouteModal.value = true
  }

  function openEditRoute(row: ModelRoute) {
    routeEditKey.value = row.callSite
    routeDraft.value = {
      callSite: row.callSite,
      models: [...row.models],
      enabled: row.enabled,
      remark: row.remark ?? '',
    }
    showRouteModal.value = true
  }

  async function submitRoute() {
    const d = routeDraft.value
    if (!d.callSite.trim()) {
      message.warning('请选择调用点')
      return
    }
    if (!d.models.length) {
      message.warning('请选择候选模型池')
      return
    }
    const known = routeKeys.value.some((k) => k.key === d.callSite.trim())
    if (!known) {
      message.warning('调用点必须为系统枚举值')
      return
    }
    saving.value = true
    try {
      await upsertModelRoute({
        callSite: d.callSite.trim(),
        models: d.models,
        enabled: d.enabled,
        remark: d.remark.trim() || null,
      })
      message.success('已保存')
      showRouteModal.value = false
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存失败'))
    } finally {
      saving.value = false
    }
  }

  async function handleToggleRoute(row: ModelRoute) {
    try {
      await upsertModelRoute({
        callSite: row.callSite,
        models: row.models,
        enabled: !row.enabled,
      })
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换失败'))
    }
  }

  function askDeleteRoute(row: ModelRoute) {
    deleteTarget.value = { kind: 'route', id: row.id, label: row.callSite }
    showDeleteConfirm.value = true
  }

  async function submitScene() {
    const d = sceneDraft.value
    if (!d.sceneKey.trim() || !d.primaryModel.trim()) {
      message.warning('请选择场景与主模型')
      return
    }
    const known = sceneKeys.value.some((k) => k.sceneKey === d.sceneKey.trim())
    if (!known) {
      message.warning('场景必须为系统枚举值')
      return
    }
    let extras: Record<string, unknown> | null = null
    if (d.extrasText.trim()) {
      try {
        extras = JSON.parse(d.extrasText) as Record<string, unknown>
      } catch {
        message.warning('extras JSON 无效')
        return
      }
    }
    saving.value = true
    try {
      await upsertModelScenes({
        sceneKey: d.sceneKey.trim(),
        primaryModel: d.primaryModel.trim(),
        fallbackModel: d.fallbackModel || null,
        extras,
        enabled: d.enabled,
      })
      message.success('已保存')
      showSceneModal.value = false
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存失败'))
    } finally {
      saving.value = false
    }
  }

  function askDeleteProvider(row: ModelProvider) {
    deleteTarget.value = { kind: 'provider', id: row.id, label: row.providerKey }
    showDeleteConfirm.value = true
  }

  function askDeleteDefinition(row: ModelDefinition) {
    deleteTarget.value = { kind: 'definition', id: row.id, label: row.modelName }
    showDeleteConfirm.value = true
  }

  async function confirmDelete() {
    if (!deleteTarget.value) return
    saving.value = true
    try {
      if (deleteTarget.value.kind === 'provider') {
        await deleteModelProvider(deleteTarget.value.id)
      } else if (deleteTarget.value.kind === 'definition') {
        await deleteModelDefinition(deleteTarget.value.id)
      } else {
        await deleteModelRoute(deleteTarget.value.id)
      }
      message.success('已删除')
      showDeleteConfirm.value = false
      deleteTarget.value = null
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '删除失败'))
    } finally {
      saving.value = false
    }
  }

  const providerColumns = computed<DataTableColumns<ModelProvider>>(() => [
    { title: '标识', key: 'providerKey', width: 120, ellipsis: { tooltip: true } },
    { title: '名称', key: 'displayName', ellipsis: { tooltip: true } },
    { title: '接口地址', key: 'baseUrl', ellipsis: { tooltip: true } },
    { title: '路径前缀', key: 'pathPrefix', width: 88, ellipsis: { tooltip: true } },
    {
      title: '密钥',
      key: 'apiKeyMasked',
      width: 120,
      render: (row) => row.configured ? (row.apiKeyMasked || '已配置') : '未配置',
    },
    {
      title: '启用',
      key: 'enabled',
      width: 72,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.enabled,
        onUpdateValue: () => { void handleToggleProvider(row) },
      }),
    },
    {
      title: '',
      key: 'actions',
      width: 140,
      render: (row) => h('div', { class: 'row-actions' }, [
        h(NButton, { size: 'tiny', quaternary: true, onClick: () => openEditProvider(row) }, () => '编辑'),
        h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => askDeleteProvider(row) }, () => '删除'),
      ]),
    },
  ])

  const definitionColumns = computed<DataTableColumns<ModelDefinition>>(() => [
    { title: '模型', key: 'modelName', ellipsis: { tooltip: true } },
    { title: '显示名', key: 'displayName', ellipsis: { tooltip: true } },
    { title: '供应商', key: 'providerKey', width: 110 },
    { title: '上下文窗口', key: 'contextWindow', width: 100 },
    {
      title: '输出上限',
      key: 'maxOutputTokens',
      width: 96,
      render: (row) => row.maxOutputTokens && row.maxOutputTokens > 0 ? row.maxOutputTokens : '—',
    },
    {
      title: '能力',
      key: 'capabilities',
      width: 160,
      render: (row) => {
        const caps = row.capabilities
        const tags: string[] = []
        if (caps.reasoning) tags.push('推理')
        if (caps.multimodal) tags.push('多模态')
        if (caps.toolCall) tags.push('工具')
        return tags.length ? tags.join(' · ') : '—'
      },
    },
    {
      title: '可选',
      key: 'userSelectable',
      width: 72,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.userSelectable,
        onUpdateValue: () => { void handleToggleUserSelectable(row) },
      }),
    },
    { title: '排序', key: 'sortOrder', width: 64 },
    {
      title: '启用',
      key: 'enabled',
      width: 72,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.enabled,
        onUpdateValue: () => { void handleToggleDefinition(row) },
      }),
    },
    {
      title: '',
      key: 'actions',
      width: 140,
      render: (row) => h('div', { class: 'row-actions' }, [
        h(NButton, { size: 'tiny', quaternary: true, onClick: () => openEditDefinition(row) }, () => '编辑'),
        h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => askDeleteDefinition(row) }, () => '删除'),
      ]),
    },
  ])

  const sceneColumns = computed<DataTableColumns<ModelScene>>(() => [
    {
      title: '场景',
      key: 'sceneKey',
      width: 150,
      ellipsis: { tooltip: true },
    },
    {
      title: '场景描述',
      key: 'description',
      ellipsis: { tooltip: true },
      render: (row) => row.description || '—',
    },
    { title: '主模型', key: 'primaryModel', width: 160, ellipsis: { tooltip: true } },
    {
      title: '兜底模型',
      key: 'fallbackModel',
      width: 140,
      ellipsis: { tooltip: true },
      render: (row) => row.fallbackModel || '—',
    },
    {
      title: '启用',
      key: 'enabled',
      width: 72,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.enabled,
        onUpdateValue: () => { void handleToggleScene(row) },
      }),
    },
    {
      title: '',
      key: 'actions',
      width: 90,
      render: (row) => h(NButton, { size: 'tiny', quaternary: true, onClick: () => openEditScene(row) }, () => '编辑'),
    },
  ])

  const routeColumns = computed<DataTableColumns<ModelRoute>>(() => [
    {
      title: '调用点',
      key: 'callSite',
      width: 130,
      ellipsis: { tooltip: true },
    },
    {
      title: '调用点描述',
      key: 'description',
      ellipsis: { tooltip: true },
      render: (row) => row.description || '—',
    },
    {
      title: '候选模型池（按序取首个启用）',
      key: 'models',
      minWidth: 220,
      ellipsis: { tooltip: true },
      render: (row) => (row.models.length ? row.models.join(' → ') : '—'),
    },
    { title: '策略', key: 'strategy', width: 110 },
    {
      title: '启用',
      key: 'enabled',
      width: 72,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.enabled,
        onUpdateValue: () => { void handleToggleRoute(row) },
      }),
    },
    {
      title: '',
      key: 'actions',
      width: 130,
      render: (row) => h('div', { class: 'row-actions' }, [
        h(NButton, { size: 'tiny', quaternary: true, onClick: () => openEditRoute(row) }, () => '编辑'),
        h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => askDeleteRoute(row) }, () => '删除'),
      ]),
    },
  ])

  watch(activeTab, (tab) => {
    routeState.syncQuery({ tab })
  })

  watch(
    () => route.query.tab,
    () => {
      const tab = routeState.readTab()
      if (activeTab.value !== tab) activeTab.value = tab
    },
  )

  return {
    loading,
    saving,
    activeTab,
    providers,
    definitions,
    scenes,
    sceneKeys,
    routes,
    routeKeys,
    providerOptions,
    modelSelectOptions,
    availableSceneKeyOptions,
    canCreateScene,
    sceneDraftDescription,
    availableRouteKeyOptions,
    canCreateRoute,
    routeDraftDescription,
    providerColumns,
    definitionColumns,
    sceneColumns,
    routeColumns,
    showProviderModal,
    providerEditId,
    providerDraft,
    showDefinitionModal,
    definitionEditId,
    definitionDraft,
    boolParamOptions,
    reasoningSplitSelectValue,
    includeUsageSelectValue,
    thinkingTypeOptions,
    serviceTierOptions,
    showSceneModal,
    sceneEditKey,
    sceneDraft,
    showRouteModal,
    routeEditKey,
    routeDraft,
    showDeleteConfirm,
    deleteTarget,
    refreshPage,
    openCreateProvider,
    openCreateDefinition,
    openCreateScene,
    openCreateRoute,
    submitProvider,
    submitDefinition,
    submitScene,
    submitRoute,
    confirmDelete,
  }
}

export type ModelsPage = ReturnType<typeof useModelsPage>
export type ModelsPageRef = Ref<ModelsPage>
