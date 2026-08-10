import { computed, h, ref, type Ref } from 'vue'
import { NButton, NSwitch, useMessage, type DataTableColumns } from 'naive-ui'
import { friendlyErrorMessage } from '../api/apiError'
import {
  catalogEnabledModelOptions,
  createModelDefinition,
  createModelProvider,
  deleteModelDefinition,
  deleteModelProvider,
  emptyCapabilities,
  emptyRequestExtrasDraft,
  listModelDefinitions,
  listModelProviders,
  listModelSceneKeys,
  listModelScenes,
  parseRequestExtrasDraft,
  resolveMaxCompletionTokens,
  buildRequestExtras,
  toggleModelDefinition,
  updateModelDefinition,
  updateModelProvider,
  upsertModelScenes,
  type ModelCapabilities,
  type ModelDefinition,
  type ModelProvider,
  type ModelScene,
  type ModelSceneKeyMeta,
} from '../api/models'

export type ModelsTab = 'providers' | 'models' | 'scenes'

export function useModelsPage() {
  const message = useMessage()
  const loading = ref(false)
  const saving = ref(false)
  const activeTab = ref<ModelsTab>('providers')

  const providers = ref<ModelProvider[]>([])
  const definitions = ref<ModelDefinition[]>([])
  const scenes = ref<ModelScene[]>([])
  const sceneKeys = ref<ModelSceneKeyMeta[]>([])

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
    { label: 'true', value: true },
    { label: 'false', value: false },
  ]
  const thinkingTypeOptions = [
    { label: 'adaptive', value: 'adaptive' },
    { label: 'disabled', value: 'disabled' },
  ]
  const serviceTierOptions = [
    { label: 'standard', value: 'standard' },
    { label: 'priority', value: 'priority' },
  ]

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

  const showDeleteConfirm = ref(false)
  const deleteTarget = ref<{ kind: 'provider' | 'definition'; id: number; label: string } | null>(null)

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
      const [p, d, s, keys] = await Promise.all([
        listModelProviders(),
        listModelDefinitions(),
        listModelScenes(),
        listModelSceneKeys(),
      ])
      providers.value = p
      definitions.value = d
      scenes.value = s
      sceneKeys.value = keys
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
      } else {
        await deleteModelDefinition(deleteTarget.value.id)
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

  return {
    loading,
    saving,
    activeTab,
    providers,
    definitions,
    scenes,
    sceneKeys,
    providerOptions,
    modelSelectOptions,
    availableSceneKeyOptions,
    canCreateScene,
    sceneDraftDescription,
    providerColumns,
    definitionColumns,
    sceneColumns,
    showProviderModal,
    providerEditId,
    providerDraft,
    showDefinitionModal,
    definitionEditId,
    definitionDraft,
    boolParamOptions,
    thinkingTypeOptions,
    serviceTierOptions,
    showSceneModal,
    sceneEditKey,
    sceneDraft,
    showDeleteConfirm,
    deleteTarget,
    refreshPage,
    openCreateProvider,
    openCreateDefinition,
    openCreateScene,
    submitProvider,
    submitDefinition,
    submitScene,
    confirmDelete,
  }
}

export type ModelsPage = ReturnType<typeof useModelsPage>
export type ModelsPageRef = Ref<ModelsPage>
