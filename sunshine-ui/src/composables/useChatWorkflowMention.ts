import { computed, nextTick, ref, watch, type Ref } from 'vue'
import { listWorkflowCatalog, type WorkflowCatalogEntry } from '../api/workflows'
import { allowsWorkflowMention, type ExecutionPreference } from '../api/executionModes'
import type ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'

/** Composer # Workflow 补全 */
export function useChatWorkflowMention(
  inputText: Ref<string>,
  preference: Ref<ExecutionPreference>,
  loading: Ref<boolean>,
) {
  const inputRef = ref<InstanceType<typeof ComposerSkillInput>>()
  const workflowCatalog = ref<WorkflowCatalogEntry[]>([])
  const showWorkflowSuggest = ref(false)
  const workflowSuggestIndex = ref(0)
  const workflowMentionStart = ref(-1)
  const workflowQuery = ref('')

  const workflowMentionAllowed = computed(() => allowsWorkflowMention(preference.value))

  const filteredWorkflows = computed(() => {
    const q = workflowQuery.value.trim().toLowerCase()
    return workflowCatalog.value
      .filter(w => {
        if (!q) return true
        return (
          w.id.toLowerCase().includes(q)
          || w.displayName.toLowerCase().includes(q)
          || w.description?.toLowerCase().includes(q)
          || w.examples?.some(ex => ex.toLowerCase().includes(q))
        )
      })
      .slice(0, 8)
  })

  function formatWorkflowNodes(entry: WorkflowCatalogEntry): string {
    const chain = entry.nodes?.filter(n => n !== 'start').join(' → ')
    return chain || ''
  }

  function refreshWorkflowMention(text: string) {
    if (!workflowMentionAllowed.value) {
      showWorkflowSuggest.value = false
      return
    }
    const match = text.match(/#([\w\u4e00-\u9fff-]*)$/)
    if (!match || match.index == null) {
      showWorkflowSuggest.value = false
      return
    }
    workflowMentionStart.value = match.index
    workflowQuery.value = match[1]
    showWorkflowSuggest.value = workflowCatalog.value.length > 0
    workflowSuggestIndex.value = 0
  }

  watch(inputText, refreshWorkflowMention)
  watch(workflowMentionAllowed, (allowed) => {
    if (!allowed) showWorkflowSuggest.value = false
  })

  function applyWorkflowSuggest(workflow: WorkflowCatalogEntry) {
    if (workflowMentionStart.value < 0) return
    const prefix = inputText.value.slice(0, workflowMentionStart.value)
    inputText.value = `${prefix}#${workflow.id} `
    showWorkflowSuggest.value = false
    nextTick(() => inputRef.value?.focus())
  }

  async function loadWorkflowCatalog() {
    try {
      workflowCatalog.value = await listWorkflowCatalog()
    } catch (e) {
      console.warn('[ChatView] workflow catalog load failed', e)
    }
  }

  function handleWorkflowKeydown(e: KeyboardEvent): boolean {
    if (!showWorkflowSuggest.value || filteredWorkflows.value.length === 0) {
      return false
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      workflowSuggestIndex.value = (workflowSuggestIndex.value + 1) % filteredWorkflows.value.length
      return true
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      workflowSuggestIndex.value = (workflowSuggestIndex.value - 1 + filteredWorkflows.value.length)
        % filteredWorkflows.value.length
      return true
    }
    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
      e.preventDefault()
      applyWorkflowSuggest(filteredWorkflows.value[workflowSuggestIndex.value])
      return true
    }
    if (e.key === 'Escape') {
      showWorkflowSuggest.value = false
      return true
    }
    return false
  }

  return {
    inputRef,
    workflowCatalog,
    showWorkflowSuggest,
    workflowSuggestIndex,
    filteredWorkflows,
    workflowMentionAllowed,
    formatWorkflowNodes,
    applyWorkflowSuggest,
    loadWorkflowCatalog,
    handleWorkflowKeydown,
  }
}
