import { computed, nextTick, ref, watch, type Ref } from 'vue'
import { listSkillCatalogIndex, type SkillCatalogIndexEntry } from '../api/skills'
import { allowsSkillMention, type ExecutionPreference } from '../api/executionModes'
import type ComposerSkillInput from '../components/chat/ComposerSkillInput.vue'

/** Composer @ Skill 补全 */
export function useChatSkillMention(
  inputText: Ref<string>,
  preference: Ref<ExecutionPreference>,
  loading: Ref<boolean>,
) {
  const inputRef = ref<InstanceType<typeof ComposerSkillInput>>()
  const skillCatalog = ref<SkillCatalogIndexEntry[]>([])
  const showSkillSuggest = ref(false)
  const skillSuggestIndex = ref(0)
  const skillMentionStart = ref(-1)
  const skillQuery = ref('')

  const skillMentionAllowed = computed(() => allowsSkillMention(preference.value))
  const inputPlaceholder = computed(() =>
    skillMentionAllowed.value
      ? '发消息，Enter 发送；输入 @ 指定 Skill'
      : '发消息，Enter 发送',
  )

  const filteredSkills = computed(() => {
    const q = skillQuery.value.trim().toLowerCase()
    return skillCatalog.value
      .filter(s => s.enabled && (
        !q
        || s.id.toLowerCase().includes(q)
        || s.displayName.toLowerCase().includes(q)
      ))
      .slice(0, 8)
  })

  function refreshSkillMention(text: string) {
    if (!skillMentionAllowed.value) {
      showSkillSuggest.value = false
      return
    }
    const match = text.match(/@([\w\u4e00-\u9fff-]*)$/)
    if (!match || match.index == null) {
      showSkillSuggest.value = false
      return
    }
    skillMentionStart.value = match.index
    skillQuery.value = match[1]
    showSkillSuggest.value = skillCatalog.value.some(s => s.enabled)
    skillSuggestIndex.value = 0
  }

  watch(inputText, refreshSkillMention)
  watch(skillMentionAllowed, (allowed) => {
    if (!allowed) showSkillSuggest.value = false
  })

  function applySkillSuggest(skill: SkillCatalogIndexEntry) {
    if (skillMentionStart.value < 0) return
    const prefix = inputText.value.slice(0, skillMentionStart.value)
    inputText.value = `${prefix}@${skill.id} `
    showSkillSuggest.value = false
    nextTick(() => inputRef.value?.focus())
  }

  async function loadSkillCatalog() {
    try {
      skillCatalog.value = await listSkillCatalogIndex()
    } catch (e) {
      console.warn('[ChatView] skill catalog load failed', e)
    }
  }

  function handleSkillKeydown(e: KeyboardEvent, onSend: () => void) {
    if (showSkillSuggest.value && filteredSkills.value.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        skillSuggestIndex.value = (skillSuggestIndex.value + 1) % filteredSkills.value.length
        return true
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault()
        skillSuggestIndex.value = (skillSuggestIndex.value - 1 + filteredSkills.value.length)
          % filteredSkills.value.length
        return true
      }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
        e.preventDefault()
        applySkillSuggest(filteredSkills.value[skillSuggestIndex.value])
        return true
      }
      if (e.key === 'Escape') {
        showSkillSuggest.value = false
        return true
      }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      onSend()
      return true
    }
    return false
  }

  return {
    inputRef,
    skillCatalog,
    showSkillSuggest,
    skillSuggestIndex,
    filteredSkills,
    skillMentionAllowed,
    inputPlaceholder,
    applySkillSuggest,
    loadSkillCatalog,
    handleSkillKeydown,
  }
}
