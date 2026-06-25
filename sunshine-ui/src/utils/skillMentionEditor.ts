import type { SkillCatalogIndexEntry } from '../api/skills'
import { segmentSkillMentions, type SkillMentionSegment } from './skillMention'

export function plainTextFromEditor(root: HTMLElement): string {
  let out = ''
  for (const node of Array.from(root.childNodes)) {
    out += plainTextFromNode(node)
  }
  return out
}

function plainTextFromNode(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.textContent ?? ''
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return ''
  const el = node as HTMLElement
  const skillId = el.dataset.skillId
  if (skillId) return `@${skillId}`
  let out = ''
  for (const child of Array.from(node.childNodes)) {
    out += plainTextFromNode(child)
  }
  return out
}

export function getCaretPlainOffset(root: HTMLElement): number {
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0) return plainTextFromEditor(root).length
  const range = sel.getRangeAt(0)
  if (!root.contains(range.startContainer)) {
    return plainTextFromEditor(root).length
  }
  const pre = range.cloneRange()
  pre.selectNodeContents(root)
  pre.setEnd(range.startContainer, range.startOffset)
  return plainTextFromFragment(pre.cloneContents())
}

function plainTextFromFragment(frag: DocumentFragment): string {
  let out = ''
  for (const node of Array.from(frag.childNodes)) {
    out += plainTextFromNode(node)
  }
  return out
}

export function setCaretPlainOffset(root: HTMLElement, offset: number): void {
  const sel = window.getSelection()
  if (!sel) return
  const range = document.createRange()
  let pos = 0
  const target = Math.max(0, offset)

  function walk(node: Node): boolean {
    if (node.nodeType === Node.TEXT_NODE) {
      const len = node.textContent?.length ?? 0
      if (pos + len >= target) {
        range.setStart(node, target - pos)
        range.collapse(true)
        return true
      }
      pos += len
      return false
    }
    if (node.nodeType === Node.ELEMENT_NODE) {
      const el = node as HTMLElement
      if (el.dataset.skillId) {
        const len = `@${el.dataset.skillId}`.length
        if (pos + len >= target) {
          if (target <= pos) {
            range.setStartBefore(el)
          } else {
            range.setStartAfter(el)
          }
          range.collapse(true)
          return true
        }
        pos += len
        return false
      }
    }
    for (const child of Array.from(node.childNodes)) {
      if (walk(child)) return true
    }
    return false
  }

  if (!walk(root)) {
    range.selectNodeContents(root)
    range.collapse(false)
  }
  sel.removeAllRanges()
  sel.addRange(range)
}

function createSkillChipElement(token: string): HTMLSpanElement {
  const chip = document.createElement('span')
  chip.className = 'skill-mention-chip'
  chip.contentEditable = 'false'
  chip.dataset.skillId = token
  const at = document.createElement('span')
  at.className = 'skill-mention-chip__at'
  at.textContent = '@'
  const label = document.createElement('span')
  label.className = 'skill-mention-chip__label'
  label.textContent = token
  chip.append(at, label)
  return chip
}

export function renderEditorSegments(root: HTMLElement, segments: SkillMentionSegment[]): void {
  root.replaceChildren()
  const hasSkill = segments.some(s => s.type === 'skill')
  if (!hasSkill) {
    const text = segments[0]?.type === 'text' ? segments[0].value : ''
    if (text) root.appendChild(document.createTextNode(text))
    return
  }
  for (const seg of segments) {
    if (seg.type === 'text') {
      if (seg.value) root.appendChild(document.createTextNode(seg.value))
    } else {
      root.appendChild(createSkillChipElement(seg.token))
    }
  }
}

export function displaySegments(
  plain: string,
  allowsSkillMention: boolean,
  catalog: SkillCatalogIndexEntry[],
): SkillMentionSegment[] {
  if (!allowsSkillMention) return [{ type: 'text', value: plain }]
  return segmentSkillMentions(plain, catalog)
}

export function shouldRenderChips(
  plain: string,
  allowsSkillMention: boolean,
  catalog: SkillCatalogIndexEntry[],
): boolean {
  if (!allowsSkillMention || !plain) return false
  return segmentSkillMentions(plain, catalog).some(s => s.type === 'skill')
}

/** DOM 已与 plain 中 skill 段对齐时不再整棵重建，避免打断 IME 中文输入 */
export function editorNeedsChipSync(
  root: HTMLElement,
  plain: string,
  allowsSkillMention: boolean,
  catalog: SkillCatalogIndexEntry[],
): boolean {
  const domChips = Array.from(root.querySelectorAll<HTMLElement>('[data-skill-id]'))
  if (!allowsSkillMention) {
    return domChips.length > 0
  }
  const segments = displaySegments(plain, true, catalog)
  const expectedSkills = segments.filter((s): s is Extract<SkillMentionSegment, { type: 'skill' }> => s.type === 'skill')
  if (expectedSkills.length === 0) {
    return domChips.length > 0
  }
  if (domChips.length !== expectedSkills.length) {
    return true
  }
  const domIds = domChips.map(el => el.dataset.skillId ?? '')
  const expectedIds = expectedSkills.map(s => s.token)
  if (domIds.some((id, i) => id !== expectedIds[i])) {
    return true
  }
  return plainTextFromEditor(root) !== plain
}
