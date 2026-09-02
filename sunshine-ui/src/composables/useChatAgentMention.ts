import { computed, nextTick, ref, watch, type Ref } from 'vue'
import { listAgentCatalogIndex, type AgentCatalogIndexEntry } from '../api/agents'
import { allowsAgentMention, type ExecutionMode } from '../api/executionModes'
import { matchesSessionKind } from '../utils/kindFilter'
import type ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'

/** Composer $ 智能体 补全 */
export function useChatAgentMention(
  inputText: Ref<string>,
  preference: Ref<ExecutionMode>,
  loading: Ref<boolean>,
  sessionKind: Ref<string>,
) {
  const inputRef = ref<InstanceType<typeof ComposerSkillInput>>()
  const agentCatalog = ref<AgentCatalogIndexEntry[]>([])
  const showAgentSuggest = ref(false)
  const agentSuggestIndex = ref(0)
  const agentMentionStart = ref(-1)
  const agentQuery = ref('')

  const agentMentionAllowed = computed(() => allowsAgentMention(preference.value))

  const filteredAgents = computed(() => {
    const q = agentQuery.value.trim().toLowerCase()
    return agentCatalog.value
      .filter(e => e.enabled
        && matchesSessionKind(sessionKind.value, e.kind)
        && (
          !q
          || e.id.toLowerCase().includes(q)
          || e.displayName.toLowerCase().includes(q)
        ))
      .slice(0, 8)
  })

  function refreshAgentMention(text: string) {
    if (!agentMentionAllowed.value) {
      showAgentSuggest.value = false
      return
    }
    const match = text.match(/\$([\w\u4e00-\u9fff-]*)$/)
    if (!match || match.index == null) {
      showAgentSuggest.value = false
      return
    }
    agentMentionStart.value = match.index
    agentQuery.value = match[1]
    showAgentSuggest.value = filteredAgents.value.length > 0
    agentSuggestIndex.value = 0
  }

  watch(inputText, refreshAgentMention)
  watch(agentMentionAllowed, (allowed) => {
    if (!allowed) showAgentSuggest.value = false
  })

  function applyAgentSuggest(agent: AgentCatalogIndexEntry) {
    if (agentMentionStart.value < 0) return
    const prefix = inputText.value.slice(0, agentMentionStart.value)
    inputText.value = `${prefix}$${agent.id} `
    showAgentSuggest.value = false
    nextTick(() => inputRef.value?.focus())
  }

  async function loadAgentCatalog() {
    try {
      agentCatalog.value = await listAgentCatalogIndex()
    } catch (e) {
      console.warn('[ChatView] agent catalog load failed', e)
    }
  }

  function handleAgentKeydown(e: KeyboardEvent): boolean {
    if (!showAgentSuggest.value || filteredAgents.value.length === 0) {
      return false
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      agentSuggestIndex.value = (agentSuggestIndex.value + 1) % filteredAgents.value.length
      return true
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      agentSuggestIndex.value = (agentSuggestIndex.value - 1 + filteredAgents.value.length)
        % filteredAgents.value.length
      return true
    }
    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
      e.preventDefault()
      applyAgentSuggest(filteredAgents.value[agentSuggestIndex.value])
      return true
    }
    if (e.key === 'Escape') {
      showAgentSuggest.value = false
      return true
    }
    return false
  }

  return {
    inputRef,
    agentCatalog,
    showAgentSuggest,
    agentSuggestIndex,
    filteredAgents,
    agentMentionAllowed,
    applyAgentSuggest,
    loadAgentCatalog,
    handleAgentKeydown,
  }
}
