import { computed, ref, type ComputedRef, type Ref } from 'vue'
import {
  addPromptVersion,
  getPrompt,
  parseRoutingContentJson,
  serializeRoutingContent,
  updatePrompt,
  validateRoutingRules,
  type PromptDetail,
  type PromptListItem,
  type RoutingRuleContent,
  type RoutingWarningItem,
} from '../api/prompts'
import { friendlyErrorMessage } from '../api/apiError'
import type { WorkflowCatalogEntry } from '../api/workflows'

export interface RoutingRuleOpsDeps {
  selectedId: Ref<string | null>
  detail: Ref<PromptDetail | null>
  prompts: Ref<PromptListItem[]>
  workflowCatalog: Ref<WorkflowCatalogEntry[]>
  editDisplayName: Ref<string>
  editDescription: Ref<string>
  editPriority: Ref<number>
  saving: Ref<boolean>
  isContentEditable: ComputedRef<boolean>
  selectedListItem: ComputedRef<PromptListItem | null>
  message: ReturnType<typeof import('naive-ui')['useMessage']>
  refreshList: (keepSelection?: boolean) => Promise<void>
}

export function useRoutingRuleOps(deps: RoutingRuleOpsDeps) {
  const {
    selectedId,
    detail,
    prompts,
    workflowCatalog,
    editDisplayName,
    editDescription,
    editPriority,
    saving,
    isContentEditable,
    selectedListItem,
    message,
    refreshList,
  } = deps

  const routingForm = ref<RoutingRuleContent>(parseRoutingContentJson(null))
  const routingWarnings = ref<RoutingWarningItem[]>([])
  const validating = ref(false)

  const isRoutingSelected = computed(() => selectedListItem.value?.kind === 'routing-rule')

  const workflowOptions = computed(() =>
    workflowCatalog.value
      .slice()
      .sort((a, b) => a.id.localeCompare(b.id))
      .map(w => ({
        label: w.displayName && w.displayName !== w.id
          ? `${w.displayName}（${w.id}）`
          : w.id,
        value: w.id,
      })),
  )

  function applyRoutingContent(contentJson: string | null) {
    routingForm.value = parseRoutingContentJson(contentJson)
    routingWarnings.value = []
  }

  async function saveRoutingRule() {
    if (!selectedId.value || !detail.value) return
    if (!isContentEditable.value) {
      message.warning('生效版本不可直接修改，请先「复制为草稿」')
      return
    }
    const contentJson = serializeRoutingContent(routingForm.value)
    saving.value = true
    validating.value = true
    try {
      const validateRes = await validateRoutingRules([{
        id: selectedId.value,
        priority: editPriority.value,
        enabled: detail.value.enabled,
        contentJson,
      }])
      routingWarnings.value = validateRes.warnings ?? []
      await updatePrompt(selectedId.value, {
        displayName: editDisplayName.value.trim(),
        description: editDescription.value.trim(),
        priority: editPriority.value,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      const latest = await getPrompt(selectedId.value)
      await addPromptVersion(selectedId.value, {
        status: 'draft',
        contentText: null,
        contentJson,
        expectedUpdatedAt: latest.updatedAt ?? null,
      })
      if (routingWarnings.value.length) {
        message.warning(`已保存草稿（${routingWarnings.value.length} 条冲突警告）`)
      } else {
        message.success('路由规则草稿已保存')
      }
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存路由规则失败'))
      console.error(e)
    } finally {
      saving.value = false
      validating.value = false
    }
  }

  return {
    routingForm,
    routingWarnings,
    validating,
    isRoutingSelected,
    workflowOptions,
    applyRoutingContent,
    saveRoutingRule,
  }
}
