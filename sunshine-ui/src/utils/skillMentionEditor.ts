import type { SkillCatalogIndexEntry } from '../api/skills'
import type { ExpertCatalogIndexEntry } from '../api/experts'
import type { WorkflowCatalogEntry } from '../api/workflows'
import {
  type ChatMentionAllows,
  type ChatMentionCatalogs,
  type ChatMentionKind,
  type ChatMentionSegment,
  mentionPlainToken,
  segmentChatMentions,
} from './chatMention'

export interface ComposerMentionContext {
  catalogs: ChatMentionCatalogs
  allows: ChatMentionAllows
}

function chipDataset(kind: ChatMentionKind, token: string): Record<string, string> {
  return { mentionKind: kind, mentionId: token }
}

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
  const kind = el.dataset.mentionKind as ChatMentionKind | undefined
  const mentionId = el.dataset.mentionId
  if (kind && mentionId) return mentionPlainToken(kind, mentionId)
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
  return plainTextFromFragment(pre.cloneContents()).length
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
      const kind = el.dataset.mentionKind as ChatMentionKind | undefined
      const mentionId = el.dataset.mentionId
      if (kind && mentionId) {
        const len = mentionPlainToken(kind, mentionId).length
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

function createMentionChipElement(kind: ChatMentionKind, token: string): HTMLSpanElement {
  const chip = document.createElement('span')
  chip.className = `mention-chip mention-chip--${kind}`
  chip.contentEditable = 'false'
  Object.entries(chipDataset(kind, token)).forEach(([k, v]) => {
    chip.dataset[k] = v
  })
  const prefix = document.createElement('span')
  prefix.className = 'mention-chip__prefix'
  prefix.textContent = kind === 'skill' ? '@' : kind === 'expert' ? '$' : '#'
  const label = document.createElement('span')
  label.className = 'mention-chip__label'
  label.textContent = token
  chip.append(prefix, label)
  return chip
}

export function renderEditorSegments(root: HTMLElement, segments: ChatMentionSegment[]): void {
  root.replaceChildren()
  const hasChip = segments.some(s => s.type !== 'text')
  if (!hasChip) {
    const text = segments[0]?.type === 'text' ? segments[0].value : ''
    if (text) root.appendChild(document.createTextNode(text))
    return
  }
  for (const seg of segments) {
    if (seg.type === 'text') {
      if (seg.value) root.appendChild(document.createTextNode(seg.value))
    } else if (seg.type === 'skill') {
      root.appendChild(createMentionChipElement('skill', seg.token))
    } else if (seg.type === 'expert') {
      root.appendChild(createMentionChipElement('expert', seg.token))
    } else {
      root.appendChild(createMentionChipElement('workflow', seg.token))
    }
  }
}

export function displaySegments(
  plain: string,
  ctx: ComposerMentionContext,
): ChatMentionSegment[] {
  const anyAllowed = ctx.allows.skill || ctx.allows.expert || ctx.allows.workflow
  if (!anyAllowed) return [{ type: 'text', value: plain }]
  return segmentChatMentions(plain, ctx.catalogs, ctx.allows)
}

export function shouldRenderChips(
  plain: string,
  ctx: ComposerMentionContext,
): boolean {
  const anyAllowed = ctx.allows.skill || ctx.allows.expert || ctx.allows.workflow
  if (!anyAllowed || !plain) return false
  return segmentChatMentions(plain, ctx.catalogs, ctx.allows).some(s => s.type !== 'text')
}

export function editorNeedsChipSync(
  root: HTMLElement,
  plain: string,
  ctx: ComposerMentionContext,
): boolean {
  const domChips = Array.from(root.querySelectorAll<HTMLElement>('[data-mention-kind][data-mention-id]'))
  const anyAllowed = ctx.allows.skill || ctx.allows.expert || ctx.allows.workflow
  if (!anyAllowed) {
    return domChips.length > 0
  }
  const segments = displaySegments(plain, ctx)
  const expected = segments.filter((s): s is Exclude<ChatMentionSegment, { type: 'text' }> => s.type !== 'text')
  if (expected.length === 0) {
    return domChips.length > 0
  }
  if (domChips.length !== expected.length) {
    return true
  }
  const domPairs = domChips.map(el => `${el.dataset.mentionKind}:${el.dataset.mentionId ?? ''}`)
  const expectedPairs = expected.map(s => `${s.type}:${s.token}`)
  if (domPairs.some((pair, i) => pair !== expectedPairs[i])) {
    return true
  }
  return plainTextFromEditor(root) !== plain
}

/** @deprecated 兼容旧调用 */
export type { ChatMentionSegment as SkillMentionSegment }

export function defaultMentionCatalogs(
  skills: SkillCatalogIndexEntry[] = [],
  experts: ExpertCatalogIndexEntry[] = [],
  workflows: WorkflowCatalogEntry[] = [],
): ChatMentionCatalogs {
  return { skills, experts, workflows }
}
