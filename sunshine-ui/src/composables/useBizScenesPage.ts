import { computed, onMounted, reactive, ref, type ComputedRef, type Ref } from 'vue'
import { useDialog, useMessage } from 'naive-ui'
import {
  createBizScene,
  createBizScenePolicy,
  deleteBizScene,
  deleteBizScenePolicy,
  listBizScenePolicies,
  listBizScenes,
  updateBizScene,
  type BizSceneEntry,
  type BizScenePolicyEntry,
} from '../api/bizScenes'
import { friendlyErrorMessage } from '../api/apiError'

export const BIZ_SCENES_PAGE_KEY = Symbol('bizScenesPage')

export function useBizScenesPage() {
  const message = useMessage()
  const dialog = useDialog()

  const scenes = ref<BizSceneEntry[]>([])
  const rules = ref<BizScenePolicyEntry[]>([])
  const sceneSearch = ref('')
  const selectedCode = ref<string | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const savingRule = ref(false)
  const deleting = ref(false)

  const filteredScenes = computed(() => {
    const q = sceneSearch.value.trim().toLowerCase()
    if (!q) return scenes.value
    return scenes.value.filter(s =>
      s.bizScene.toLowerCase().includes(q)
      || (s.displayName || '').toLowerCase().includes(q),
    )
  })

  const selectedScene = computed(() =>
    scenes.value.find(s => s.bizScene === selectedCode.value) ?? null,
  )

  /** 当前场景规则列表（每条规则一项；rulesJson 为规则提示词文本） */
  const sceneRules = computed(() =>
    rules.value
      .filter(r => r.bizScene === selectedCode.value)
      .sort((a, b) => a.version - b.version),
  )

  const refreshing = computed(() => loading.value)

  async function refreshAll() {
    loading.value = true
    try {
      scenes.value = await listBizScenes()
      rules.value = await listBizScenePolicies()
      if (selectedCode.value && !scenes.value.some(s => s.bizScene === selectedCode.value)) {
        selectedCode.value = null
      }
      if (!selectedCode.value && scenes.value.length) {
        selectedCode.value = scenes.value[0].bizScene
      }
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '加载失败'))
    } finally {
      loading.value = false
    }
  }

  function selectScene(code: string) {
    selectedCode.value = code
  }

  /** 启用/禁用开关：active | disabled */
  async function toggleEnabled(scene: BizSceneEntry, v: boolean) {
    saving.value = true
    try {
      const updated = await updateBizScene(scene.bizScene, { status: v ? 'active' : 'disabled' })
      const idx = scenes.value.findIndex(s => s.bizScene === scene.bizScene)
      if (idx >= 0) scenes.value[idx] = updated
      message.success(v ? '已启用' : '已禁用')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '操作失败'))
    } finally {
      saving.value = false
    }
  }

  // ---- 码表创建 ----
  const showCreate = ref(false)
  const createDraft = ref({ bizScene: '', displayName: '', description: '' })
  const creating = ref(false)

  async function handleCreate() {
    if (!createDraft.value.bizScene.trim() || !createDraft.value.displayName.trim()) return
    creating.value = true
    try {
      await createBizScene({
        bizScene: createDraft.value.bizScene.trim(),
        displayName: createDraft.value.displayName.trim(),
        description: createDraft.value.description.trim(),
      })
      showCreate.value = false
      createDraft.value = { bizScene: '', displayName: '', description: '' }
      await refreshAll()
      message.success('业务场景已创建')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '创建失败'))
    } finally {
      creating.value = false
    }
  }

  // ---- 码表编辑（弹窗）----
  const showEdit = ref(false)
  const editDraft = ref({ bizScene: '', displayName: '', description: '' })
  const editing = ref(false)

  function openEdit(scene: BizSceneEntry) {
    editDraft.value = {
      bizScene: scene.bizScene,
      displayName: scene.displayName,
      description: scene.description ?? '',
    }
    showEdit.value = true
  }

  async function handleEdit() {
    if (!editDraft.value.bizScene.trim() || !editDraft.value.displayName.trim()) return
    editing.value = true
    try {
      await updateBizScene(editDraft.value.bizScene.trim(), {
        displayName: editDraft.value.displayName.trim(),
        description: editDraft.value.description.trim(),
      })
      showEdit.value = false
      await refreshAll()
      message.success('已保存')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '保存失败'))
    } finally {
      editing.value = false
    }
  }

  function handleDelete() {
    const scene = selectedScene.value
    if (!scene) return
    dialog.warning({
      class: 'sunshine-dialog',
      title: '删除业务场景',
      content: `确定删除「${scene.displayName}（${scene.bizScene}）」吗？其全部规则将一并删除，已绑定的 Skill/Agent 将视为无业务场景。`,
      positiveText: '删除',
      negativeText: '取消',
      positiveButtonProps: { type: 'error', size: 'medium' },
      negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
      onPositiveClick: () => doDelete(scene),
    })
  }

  async function doDelete(scene: BizSceneEntry) {
    deleting.value = true
    try {
      await deleteBizScene(scene.bizScene)
      if (selectedCode.value === scene.bizScene) selectedCode.value = null
      await refreshAll()
      message.success('已删除')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '删除失败'))
    } finally {
      deleting.value = false
    }
  }

  // ---- 场景规则：新增走弹窗，每条规则一项文本 ----
  const showCreateRule = ref(false)
  const ruleDraft = ref('')

  function openCreateRule() {
    if (!selectedScene.value) return
    ruleDraft.value = ''
    showCreateRule.value = true
  }

  async function handleSaveRule() {
    const scene = selectedScene.value
    if (!scene || !ruleDraft.value.trim()) return
    savingRule.value = true
    try {
      await createBizScenePolicy('default', {
        bizScene: scene.bizScene,
        rulesJson: ruleDraft.value.trim(),
      })
      showCreateRule.value = false
      ruleDraft.value = ''
      await refreshAll()
      message.success('规则已添加')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '添加规则失败'))
    } finally {
      savingRule.value = false
    }
  }

  function handleDeleteRule(rule: BizScenePolicyEntry) {
    dialog.warning({
      class: 'sunshine-dialog',
      title: '删除规则',
      content: '确定删除该条规则吗？删除后不再参与场景执行。',
      positiveText: '删除',
      negativeText: '取消',
      positiveButtonProps: { type: 'error', size: 'medium' },
      negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
      onPositiveClick: () => doDeleteRule(rule),
    })
  }

  async function doDeleteRule(rule: BizScenePolicyEntry) {
    deleting.value = true
    try {
      await deleteBizScenePolicy(rule.policyId)
      await refreshAll()
      message.success('已删除')
    } catch (e: unknown) {
      message.error(friendlyErrorMessage(e, '删除失败'))
    } finally {
      deleting.value = false
    }
  }

  onMounted(() => {
    void refreshAll()
  })

  return reactive({
    scenes,
    rules,
    sceneSearch,
    selectedCode,
    loading,
    saving,
    savingRule,
    deleting,
    filteredScenes,
    selectedScene,
    sceneRules,
    refreshing,
    showCreate,
    createDraft,
    creating,
    showEdit,
    editDraft,
    editing,
    showCreateRule,
    ruleDraft,
    refreshAll,
    selectScene,
    toggleEnabled,
    handleCreate,
    openEdit,
    handleEdit,
    handleDelete,
    openCreateRule,
    handleSaveRule,
    handleDeleteRule,
  })
}

type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type BizScenesPageComposable = ReturnType<typeof useBizScenesPage>

export type BizScenesPageApi = {
  [K in keyof BizScenesPageComposable]: UnwrapPageMember<BizScenesPageComposable[K]>
}
