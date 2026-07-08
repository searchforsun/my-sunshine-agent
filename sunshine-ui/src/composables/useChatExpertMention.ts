import { computed, nextTick, ref, watch, type Ref } from 'vue'
import { listExpertCatalogIndex, type ExpertCatalogIndexEntry } from '../api/experts'
import { allowsExpertMention, type ExecutionPreference } from '../api/executionModes'
import type ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'

/** Composer $ Expert 补全 */
export function useChatExpertMention(
  inputText: Ref<string>,
  preference: Ref<ExecutionPreference>,
  loading: Ref<boolean>,
) {
  const inputRef = ref<InstanceType<typeof ComposerSkillInput>>()
  const expertCatalog = ref<ExpertCatalogIndexEntry[]>([])
  const showExpertSuggest = ref(false)
  const expertSuggestIndex = ref(0)
  const expertMentionStart = ref(-1)
  const expertQuery = ref('')

  const expertMentionAllowed = computed(() => allowsExpertMention(preference.value))

  const filteredExperts = computed(() => {
    const q = expertQuery.value.trim().toLowerCase()
    return expertCatalog.value
      .filter(e => e.enabled && (
        !q
        || e.id.toLowerCase().includes(q)
        || e.displayName.toLowerCase().includes(q)
      ))
      .slice(0, 8)
  })

  function refreshExpertMention(text: string) {
    if (!expertMentionAllowed.value) {
      showExpertSuggest.value = false
      return
    }
    const match = text.match(/\$([\w\u4e00-\u9fff-]*)$/)
    if (!match || match.index == null) {
      showExpertSuggest.value = false
      return
    }
    expertMentionStart.value = match.index
    expertQuery.value = match[1]
    showExpertSuggest.value = expertCatalog.value.some(e => e.enabled)
    expertSuggestIndex.value = 0
  }

  watch(inputText, refreshExpertMention)
  watch(expertMentionAllowed, (allowed) => {
    if (!allowed) showExpertSuggest.value = false
  })

  function applyExpertSuggest(expert: ExpertCatalogIndexEntry) {
    if (expertMentionStart.value < 0) return
    const prefix = inputText.value.slice(0, expertMentionStart.value)
    inputText.value = `${prefix}$${expert.id} `
    showExpertSuggest.value = false
    nextTick(() => inputRef.value?.focus())
  }

  async function loadExpertCatalog() {
    try {
      expertCatalog.value = await listExpertCatalogIndex()
    } catch (e) {
      console.warn('[ChatView] expert catalog load failed', e)
    }
  }

  function handleExpertKeydown(e: KeyboardEvent): boolean {
    if (!showExpertSuggest.value || filteredExperts.value.length === 0) {
      return false
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      expertSuggestIndex.value = (expertSuggestIndex.value + 1) % filteredExperts.value.length
      return true
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      expertSuggestIndex.value = (expertSuggestIndex.value - 1 + filteredExperts.value.length)
        % filteredExperts.value.length
      return true
    }
    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
      e.preventDefault()
      applyExpertSuggest(filteredExperts.value[expertSuggestIndex.value])
      return true
    }
    if (e.key === 'Escape') {
      showExpertSuggest.value = false
      return true
    }
    return false
  }

  return {
    inputRef,
    expertCatalog,
    showExpertSuggest,
    expertSuggestIndex,
    filteredExperts,
    expertMentionAllowed,
    applyExpertSuggest,
    loadExpertCatalog,
    handleExpertKeydown,
  }
}
