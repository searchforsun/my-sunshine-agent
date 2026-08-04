import type { SkillCatalogIndexEntry } from '../api/skills'
import type { AgentCatalogIndexEntry } from '../api/agents'
import type { WorkflowCatalogEntry } from '../api/workflows'
import {
  type ChatMentionAllows,
  type ChatMentionCatalogs,
  type ChatMentionKind,
  type ChatMentionSegment,
  mentionPlainToken,
  mentionPrefix,
  segmentChatMentions,
} from './chatMention'
import { sandboxPathBasename, isLikelySandboxDir } from './sandboxPathChip'

export interface ComposerMentionContext {
  catalogs: ChatMentionCatalogs
  allows: ChatMentionAllows
}

function chipDataset(
  kind: ChatMentionKind,
  token: string,
  lineStart?: number,
  lineEnd?: number,
): Record<string, string> {
  const ds: Record<string, string> = { mentionKind: kind, mentionId: token }
  if (kind === 'path' && typeof lineStart === 'number' && lineStart > 0) {
    ds.lineStart = String(lineStart)
    if (typeof lineEnd === 'number' && lineEnd >= lineStart) {
      ds.lineEnd = String(lineEnd)
    }
  }
  return ds
}

/** path chip 展示名：test.py(120-125) / test.py(120)；无行范围退化为 basename */
export function sandboxPathChipLabel(
  token: string,
  labelText?: string,
  lineStart?: number,
  lineEnd?: number,
): string {
  const base = labelText || sandboxPathBasename(token)
  if (typeof lineStart !== 'number' || lineStart < 1) return base
  const end = typeof lineEnd === 'number' && lineEnd >= lineStart ? lineEnd : lineStart
  return end > lineStart ? `${base}(${lineStart}-${end})` : `${base}(${lineStart})`
}

const FILE_ICON_SVG =
  '<svg viewBox="0 0 512 512" width="13" height="13" aria-hidden="true"><path fill="currentColor" d="M428 224H288a48 48 0 0 1-48-48V36a4 4 0 0 0-4-4h-92a64 64 0 0 0-64 64v320a64 64 0 0 0 64 64h224a64 64 0 0 0 64-64V228a4 4 0 0 0-4-4Zm-92-180.1L411.9 176H336a8 8 0 0 1-8-8V43.9Z"/></svg>'
const FOLDER_ICON_SVG =
  '<svg viewBox="0 0 512 512" width="13" height="13" aria-hidden="true"><path fill="currentColor" d="M496 152a56 56 0 0 0-56-56H220.11a23.89 23.89 0 0 1-13.31-4L179 73.41A55.77 55.77 0 0 0 147.89 64H72a56 56 0 0 0-56 56v48a8 8 0 0 0 8 8h464a8 8 0 0 0 8-8ZM16 384a56 56 0 0 0 56 56h368a56 56 0 0 0 56-56V216a8 8 0 0 0-8-8H24a8 8 0 0 0-8 8Z"/></svg>'

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
  if (kind && mentionId) {
    const lineStart = el.dataset.lineStart ? Number(el.dataset.lineStart) : undefined
    const lineEnd = el.dataset.lineEnd ? Number(el.dataset.lineEnd) : undefined
    return mentionPlainToken(kind, mentionId, lineStart, lineEnd)
  }
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
        const lineStart = el.dataset.lineStart ? Number(el.dataset.lineStart) : undefined
        const lineEnd = el.dataset.lineEnd ? Number(el.dataset.lineEnd) : undefined
        const len = mentionPlainToken(kind, mentionId, lineStart, lineEnd).length
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

function createMentionChipElement(
  kind: ChatMentionKind,
  token: string,
  labelText?: string,
  lineStart?: number,
  lineEnd?: number,
): HTMLSpanElement {
  const chip = document.createElement('span')
  chip.className = `mention-chip mention-chip--${kind}`
  chip.contentEditable = 'false'
  Object.entries(chipDataset(kind, token, lineStart, lineEnd)).forEach(([k, v]) => {
    chip.dataset[k] = v
  })
  const display = kind === 'path'
    ? sandboxPathChipLabel(token, labelText, lineStart, lineEnd)
    : token
  chip.title = kind === 'path' ? token : display
  if (kind === 'path') {
    const isDir = isLikelySandboxDir(token)
    chip.dataset.pathIsDir = isDir ? '1' : '0'
    chip.classList.add('mention-chip--clickable')
    const icon = document.createElement('span')
    icon.className = 'mention-chip__icon'
    icon.innerHTML = isDir ? FOLDER_ICON_SVG : FILE_ICON_SVG
    chip.appendChild(icon)
  } else {
    const prefixChar = mentionPrefix(kind)
    if (prefixChar) {
      const prefix = document.createElement('span')
      prefix.className = 'mention-chip__prefix'
      prefix.textContent = prefixChar
      chip.appendChild(prefix)
    }
  }
  const label = document.createElement('span')
  label.className = 'mention-chip__label'
  label.textContent = display
  chip.appendChild(label)
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
    } else if (seg.type === 'agent') {
      root.appendChild(createMentionChipElement('agent', seg.token))
    } else if (seg.type === 'workflow') {
      root.appendChild(createMentionChipElement('workflow', seg.token))
    } else if (seg.type === 'path') {
      root.appendChild(createMentionChipElement('path', seg.token, seg.label, seg.lineStart, seg.lineEnd))
    }
  }
}

export function displaySegments(
  plain: string,
  ctx: ComposerMentionContext,
): ChatMentionSegment[] {
  return segmentChatMentions(plain, ctx.catalogs, ctx.allows)
}

export function shouldRenderChips(
  plain: string,
  ctx: ComposerMentionContext,
): boolean {
  if (!plain) return false
  return segmentChatMentions(plain, ctx.catalogs, ctx.allows).some(s => s.type !== 'text')
}

export function editorNeedsChipSync(
  root: HTMLElement,
  plain: string,
  ctx: ComposerMentionContext,
): boolean {
  const domChips = Array.from(root.querySelectorAll<HTMLElement>('[data-mention-kind][data-mention-id]'))
  const segments = displaySegments(plain, ctx)
  const expected = segments.filter((s): s is Exclude<ChatMentionSegment, { type: 'text' }> => s.type !== 'text')
  if (expected.length === 0) {
    return domChips.length > 0
  }
  if (domChips.length !== expected.length) {
    return true
  }
  const domPairs = domChips.map(el => {
    const start = el.dataset.lineStart ?? ''
    const end = el.dataset.lineEnd ?? ''
    return `${el.dataset.mentionKind}:${el.dataset.mentionId ?? ''}:${start}:${end}`
  })
  const expectedPairs = expected.map(s => {
    const start = s.type === 'path' && s.lineStart ? String(s.lineStart) : ''
    const end = s.type === 'path' && s.lineEnd ? String(s.lineEnd) : ''
    return `${s.type}:${s.token}:${start}:${end}`
  })
  if (domPairs.some((pair, i) => pair !== expectedPairs[i])) {
    return true
  }
  return plainTextFromEditor(root) !== plain
}

/** 在 caret 处插入纯文本（含 path token），并补两侧空格 */
export function insertPlainAtOffset(plain: string, offset: number, insert: string): {
  next: string
  caret: number
} {
  const before = plain.slice(0, Math.max(0, offset))
  const after = plain.slice(Math.max(0, offset))
  const needBefore = before.length > 0 && !/\s$/.test(before)
  const needAfter = after.length > 0 && !/^\s/.test(after)
  const chunk = `${needBefore ? ' ' : ''}${insert}${needAfter ? ' ' : ''}`
  return { next: before + chunk + after, caret: before.length + chunk.length }
}

/** @deprecated 兼容旧调用 */
export type { ChatMentionSegment as SkillMentionSegment }

export function defaultMentionCatalogs(
  skills: SkillCatalogIndexEntry[] = [],
  agents: AgentCatalogIndexEntry[] = [],
  workflows: WorkflowCatalogEntry[] = [],
): ChatMentionCatalogs {
  return { skills, agents, workflows }
}
