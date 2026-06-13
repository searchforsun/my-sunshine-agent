/**
 * 流式 Markdown + Mermaid 渲染引擎
 */
import { StreamBuffer } from './StreamBuffer'
import { MarkdownStateMachine } from './MarkdownStateMachine'
import { MermaidRenderer } from './MermaidRenderer'
import { registerGlobalHandlers } from './globalHandlers'
import { createToolButton } from './toolIcons'
import { smoothRender } from './smoothMarkdown'
import { normalizeStreamingMarkdown } from './normalizeStreamingMarkdown'
import { streamSafeMarkdownRender } from './streamSafeMarkdown'
import { isPartialListMarker } from './listMarkers'
import { hasUnclosedBlockMath, isBlockMathContinuation } from './mathBlock'
import type { RendererConfig, ProcessResult, RenderBlock } from './types'
import { DEFAULT_CONFIG, RenderState } from './types'

export type { RendererConfig }

const CP = (s: string) => `${DEFAULT_CONFIG.classPrefix}${s}`

/** 与 highlight.js / markdown-it 对齐的语言别名 */
function normalizeCodeLang(lang: string): string {
  const l = lang.toLowerCase().trim()
  if (l === 'c++' || l === 'cc' || l === 'hpp') return 'cpp'
  if (l === 'c') return 'c'
  if (l === 'js') return 'javascript'
  if (l === 'ts') return 'typescript'
  if (l === 'py') return 'python'
  if (l === 'sh' || l === 'shell' || l === 'zsh') return 'bash'
  if (l === 'yml') return 'yaml'
  return l
}

export class StreamMarkdownRenderer {
  private buffer: StreamBuffer
  private stateMachine: MarkdownStateMachine
  private mermaidRenderer: MermaidRenderer
  private container: HTMLElement
  private config: RendererConfig
  private blocks: RenderBlock[] = []
  private pendingMermaids: Map<string, { content: string; placeholder: HTMLElement }> = new Map()
  private mermaidTimer: ReturnType<typeof setInterval> | null = null
  private isStreaming = false
  private lastBlockEl: HTMLElement | null = null
  private lastBlockWasGroup: boolean = false
  /** 列表/表格/引用等 replacePrev 组的锚点 DOM，避免被表格更新冲掉 lastBlockEl 后重复追加 */
  private groupEl: HTMLElement | null = null
  private currentCodePre: HTMLElement | null = null
  private currentCodeRaw: string = ''
  private currentCodeLang: string = ''

  /** 段落平滑缓冲区 —— 累积 NORMAL 状态的文本，统一平滑渲染到同一个 DOM 元素 */
  private paraBuf: string = ''
  private paraEl: HTMLElement | null = null

  /** 是否正在 mermaid 代码块内（防平滑渲染泄漏） */
  private inMermaidBlock: boolean = false

  /** 已与 DOM 同步的全文长度，用于增量 append */
  private syncedContent: string = ''

  private debounceTimer: ReturnType<typeof setTimeout> | null = null
  private lastRenderAt = 0

  constructor(container: HTMLElement, config: Partial<RendererConfig> = {}) {
    this.container = container
    this.config = { ...DEFAULT_CONFIG, ...config }
    this.buffer = new StreamBuffer()
    this.stateMachine = new MarkdownStateMachine()
    this.mermaidRenderer = new MermaidRenderer(config)
    registerGlobalHandlers()
  }

  processChunk(chunk: string): void {
    if (!chunk) return
    this.isStreaming = true
    const lines = this.buffer.append(chunk)
    if (lines) this.processLines(lines)
    this.scheduleMermaidPolling()
    // 处理缓冲区中未完成的行（段落平滑输出）
    this.flushPendingParagraph()
  }

  /** 与 store 中的完整 content 对齐，防抖全量重渲染（避免增量行解析丢失块级结构） */
  syncFromContent(full: string): void {
    if (full === this.syncedContent) return
    if (!full.startsWith(this.syncedContent)) {
      this.clear()
    }
    this.syncedContent = full
    this.isStreaming = true
    this.scheduleDebouncedRender()
  }

  finish(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = null
    }
    this.lastRenderAt = 0
    this.isStreaming = false
    this.fullRerender(this.syncedContent, false)
    this.clearTimers()
    this.renderAllPendingMermaids()
    if (this.container instanceof HTMLElement) {
      this.container.classList.add(CP('stream-done'))
    }
  }

  clear(): void {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = null
    }
    this.lastRenderAt = 0
    this.resetInternalState()
    this.syncedContent = ''
    this.isStreaming = false
  }
  destroy(): void { this.clear() }

  // ═══════════════════════ process ═══════════════════════

  private processLines(lines: string): void {
    const arr = lines.split('\n')
    for (let i = 0; i < arr.length; i++) {
      if (i === arr.length - 1 && arr[i] === '') continue
      const r = this.stateMachine.processLine(arr[i])
      if (r.render) this.appendBlock(r)
    }
  }

  private appendBlock(result: ProcessResult): void {
    if (result.type === 'mermaid_placeholder') { this.commitParagraph(); this.appendMermaidPlaceholder(); return }

    const blockType = result.type
    // 代码块
    if (blockType === 'code_block_start') {
      this.commitParagraph()
      this.groupEl = null
      this.lastBlockEl = null
      this.currentCodeLang = normalizeCodeLang(result.lang || '')
      this.currentCodeRaw = ''
      const pre = document.createElement('pre')
      const head = document.createElement('div')
      head.className = CP('code-header')
      const label = document.createElement('span')
      label.className = CP('code-lang')
      label.textContent = result.lang || 'code'
      const tools = document.createElement('div')
      tools.className = CP('toolbtns')
      tools.appendChild(createToolButton(
        `${CP('toolbtn')} ${CP('toolbtn-copy')}`,
        'copy',
        '复制',
        'window.__smd_copyCode(this)',
      ))
      head.append(label, tools)
      pre.appendChild(head)
      const code = document.createElement('code')
      if (result.lang) code.className = `hljs language-${result.lang}`
      pre.appendChild(code)
      this.container.appendChild(pre)
      this.currentCodePre = pre
      if (result.initialCodeLine) {
        this.currentCodeRaw += result.initialCodeLine + '\n'
        this.refreshCodeHighlight()
      }
      return
    }
    if (blockType === 'code_block_line') {
      if (this.currentCodePre) {
        this.currentCodeRaw += result.content + '\n'
        this.refreshCodeHighlight()
      }
      return
    }
    if (blockType === 'code_block_end') {
      if (this.currentCodePre) {
        this.finalizeOpenCodeBlock()
      }
      return
    }

    if (!result.content) return
    // 空行：结束当前段落，避免多段合并为一个 <p>
    if (result.content === '\n') {
      if (hasUnclosedBlockMath(this.paraBuf)) {
        this.paraBuf += '\n'
        return
      }
      this.commitParagraph()
      return
    }

    // 段落级内容 → 累积到平滑缓冲区
    if (isParagraphResult(result)) {
      if (this.paraBuf) this.paraBuf += '\n'
      this.paraBuf += result.content
      this.renderParagraph()
      return
    }

    // 块级内容（标题、分隔线、引用、列表、表格等）→ 先刷新段落缓冲
    this.commitParagraph()

    const block: RenderBlock = { id: `smd-${this.blocks.length}-${Date.now()}`, type: result.type === 'mermaid' ? 'mermaid' : 'markdown', content: result.content }
    if (block.type === 'mermaid') this.handleMermaidBlock(block)
    else this.handleBlockElement(block, result.replacePrev, result.newGroup, blockType)
  }

  private renderMarkdownHtml(text: string, blockType?: ProcessResult['type']): string {
    const renderMd = this.config.renderMarkdown
    if (!renderMd || !text.trim()) return ''

    const isTable = blockType === 'table' || text.trimStart().startsWith('|')
    if (isTable) {
      return renderMd(text)
    }
    if (this.isStreaming) {
      return streamSafeMarkdownRender(text, renderMd, this.syncedContent)
    }
    return renderMd(text)
  }

  /** 段落文本：用 smoothRender 渲染，替换段落专属元素（不影响 lastBlockEl） */
  private renderParagraph(): void {
    if (!this.config.renderMarkdown) return
    const text = this.paraBuf.trim()
    if (!text || isPartialListMarker(text)) return
    const html = this.isStreaming
      ? streamSafeMarkdownRender(text, this.config.renderMarkdown, this.syncedContent)
      : smoothRender(text, this.config.renderMarkdown)
    if (!this.paraEl) {
      this.paraEl = document.createElement('div')
      this.paraEl.className = CP('markdown-block')
      this.container.appendChild(this.paraEl)
    }
    this.paraEl.innerHTML = html
  }

  /** 提交（清空）段落缓冲区 */
  private commitParagraph(): void {
    if (this.paraBuf && !isPartialListMarker(this.paraBuf) && this.paraEl && this.config.renderMarkdown) {
      try {
        const html = this.isStreaming
          ? streamSafeMarkdownRender(this.paraBuf, this.config.renderMarkdown, this.syncedContent)
          : this.config.renderMarkdown(this.paraBuf)
        this.paraEl.innerHTML = html
      } catch { /* ignore */ }
      this.lastBlockEl = null
    } else if (this.paraEl) {
      this.paraEl.remove()
    }
    this.paraBuf = ''
    this.paraEl = null
  }

  /** 处理缓冲区中未完成的行 —— 仅预览当前行，不拼接到已落盘 paraBuf */
  private flushPendingParagraph(): void {
    // 代码块/Mermaid 内不处理
    if (this.currentCodePre || this.inMermaidBlock) return
    const pending = this.buffer.peek()
    if (!pending) return
    // 跳过块级标记、围栏和列表项（等完整行到达后由状态机处理）
    if (/^[\s>#|`~\-*+]/.test(pending)) return
    if (/^\s*(`{1,}|~{3,})/.test(pending)) return
    if (/^\s*\$\$/.test(pending) || pending.includes('$$')) return
    if (/^\s*\$/.test(pending.trimStart())) return
    if (/^(\s*)([-*+]|\d+[.)])\s*/.test(pending)) return
    if (isPartialListMarker(pending)) return
    const mathCtx = `${this.paraBuf}\n${pending}`
    if (hasUnclosedBlockMath(mathCtx)) return
    if (isBlockMathContinuation(pending)) return
    if (!this.config.renderMarkdown) return

    const renderMd = this.config.renderMarkdown
    const parts: string[] = []

    if (this.paraBuf && !isPartialListMarker(this.paraBuf)) {
      parts.push(this.isStreaming
        ? streamSafeMarkdownRender(this.paraBuf.trim(), renderMd!, this.syncedContent)
        : renderMd!(this.paraBuf.trim()))
    }

    const previewHtml = this.isStreaming
      ? streamSafeMarkdownRender(pending, renderMd!, this.syncedContent)
      : smoothRender(pending, renderMd!)
    if (previewHtml) parts.push(previewHtml)

    if (parts.length === 0) return

    if (!this.paraEl) {
      this.paraEl = document.createElement('div')
      this.paraEl.className = CP('markdown-block')
      this.container.appendChild(this.paraEl)
    }
    this.paraEl.innerHTML = parts.join('')
  }

  // ═══════════════════════ 块级元素 ═══════════════════════

  private handleBlockElement(
    block: RenderBlock,
    replacePrev?: boolean,
    newGroup?: boolean,
    blockType?: ProcessResult['type'],
  ): void {
    const html = this.renderMarkdownHtml(block.content, blockType)
    if (!html) return

    if (replacePrev && this.groupEl) {
      this.groupEl.innerHTML = html
      this.lastBlockEl = this.groupEl
      this.lastBlockWasGroup = true
      return
    }

    if (newGroup) {
      this.groupEl = null
      this.lastBlockWasGroup = false
    }

    if (replacePrev && this.lastBlockEl) {
      this.lastBlockEl.innerHTML = html
      this.groupEl = this.lastBlockEl
      this.lastBlockWasGroup = true
      return
    }

    if (this.lastBlockWasGroup) {
      this.lastBlockEl = null
      this.lastBlockWasGroup = false
    }

    const el = document.createElement('div')
    el.className = CP('markdown-block')
    el.innerHTML = html
    this.container.appendChild(el)
    this.lastBlockEl = el
    this.groupEl = (newGroup || blockType === 'table') ? el : null
    this.lastBlockWasGroup = !!(newGroup || blockType === 'table')
    block.element = el
    this.blocks.push(block)
  }

  // ═══════════════════════ Mermaid ═══════════════════════

  private appendMermaidPlaceholder(): void {
    this.inMermaidBlock = true
    this.lastBlockEl = null
    this.groupEl = null
    const wrap = document.createElement('div')
    wrap.className = CP('mermaid-wrapper')
    const head = document.createElement('div')
    head.className = CP('mermaid-header')
    const label = document.createElement('span')
    label.className = CP('mermaid-label')
    label.textContent = 'mermaid'
    head.appendChild(label)
    wrap.appendChild(head)
    const { el: placeholder } = this.mermaidRenderer.createPlaceholder()
    wrap.appendChild(placeholder)
    this.container.appendChild(wrap)
  }

  private handleMermaidBlock(block: RenderBlock): void {
    const wrappers = this.container.querySelectorAll(`.${CP('mermaid-wrapper')}`)
    for (const wrap of wrappers) {
      const ph = wrap.querySelector(`.${CP('mermaid-loading')}`) as HTMLElement | null
      if (ph) {
        this.inMermaidBlock = false
        ;(wrap as HTMLElement).dataset.mermaidSource = block.content
        const phId = ph.id || `mmd-${Date.now()}`
        if (!ph.id) ph.id = phId
        this.pendingMermaids.set(phId, { content: block.content, placeholder: ph })
        if (!this.isStreaming) {
          void this.renderMermaidChart(phId)
        }
        return
      }
    }
    const { id, el: ph } = this.mermaidRenderer.createPlaceholder()
    this.pendingMermaids.set(id, { content: block.content, placeholder: ph })
    this.container.appendChild(ph)
    if (!this.isStreaming) void this.renderMermaidChart(id)
    this.blocks.push(block)
  }

  private buildMermaidUI(wrap: HTMLElement, source: string): void {
    wrap.dataset.mermaidSource = source
    const header = wrap.querySelector(`.${CP('mermaid-header')}`)
    if (header && !header.querySelector(`.${CP('toolbtn-toggle')}`)) {
      const tools = document.createElement('div')
      tools.className = CP('toolbtns')
      tools.appendChild(createToolButton(
        `${CP('toolbtn')} ${CP('toolbtn-toggle')} smd-toolbtn-toggle`,
        'source',
        '源码',
        'window.__smd_mermaidToggle(this)',
      ))
      tools.appendChild(createToolButton(
        `${CP('toolbtn')} ${CP('toolbtn-zoom')} smd-toolbtn-zoom`,
        'zoom',
        '全屏',
        'window.__smd_mermaidZoom(this)',
      ))
      header.appendChild(tools)
    }
  }

  // ═══════════════════════ 定时器 ═══════════════════════

  private scheduleMermaidPolling(): void {
    if (this.isStreaming) return
    if (this.mermaidTimer) return
    this.mermaidTimer = setInterval(() => {
      if (this.isStreaming) return
      for (const id of this.pendingMermaids.keys()) void this.renderMermaidChart(id)
    }, this.config.mermaidRetryMs)
  }

  private async renderMermaidChart(id: string): Promise<void> {
    if (this.isStreaming) return
    const p = this.pendingMermaids.get(id)
    if (!p) return
    const ok = await this.mermaidRenderer.render(id, p.content, p.placeholder)
    if (ok) {
      const wrap = p.placeholder.closest(`.${CP('mermaid-wrapper')}`) as HTMLElement | null
      if (wrap) this.buildMermaidUI(wrap, p.content)
      this.pendingMermaids.delete(id)
    }
  }

  private async renderAllPendingMermaids(): Promise<void> {
    for (const id of [...this.pendingMermaids.keys()]) {
      await this.renderMermaidChart(id)
    }
  }

  private clearTimers(): void {
    if (this.mermaidTimer) { clearInterval(this.mermaidTimer); this.mermaidTimer = null }
  }

  /** 流式期间按 debounceMs 节流的全量重渲染 */
  private scheduleDebouncedRender(): void {
    const now = Date.now()
    const elapsed = now - this.lastRenderAt
    const wait = Math.max(0, this.config.debounceMs - elapsed)

    if (this.debounceTimer) clearTimeout(this.debounceTimer)

    if (elapsed >= this.config.debounceMs) {
      this.fullRerender(this.syncedContent, true)
      this.lastRenderAt = now
      return
    }

    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = null
      this.fullRerender(this.syncedContent, true)
      this.lastRenderAt = Date.now()
    }, wait)
  }

  /** 从完整文本重建 DOM（流式/结束共用） */
  private fullRerender(full: string, streaming: boolean): void {
    this.resetInternalState()
    this.isStreaming = streaming
    this.syncedContent = full
    if (!full) return

    const normalized = normalizeStreamingMarkdown(full)
    this.ingestMarkdown(normalized)

    const f = this.stateMachine.forceFlush()
    if (f.render && f.content) this.appendBlock(f)

    this.commitParagraph()
    this.finalizeOpenCodeBlock()
    if (streaming) this.flushPendingParagraph()
    this.scheduleMermaidPolling()
  }

  /** 解析完整 Markdown 文本（不触发 processChunk 的二次 flush） */
  private ingestMarkdown(text: string): void {
    const lines = this.buffer.append(text)
    if (lines) this.processLines(lines)
    const remaining = this.buffer.forceFlush()
    if (remaining.trim() && !isPartialListMarker(remaining)) {
      const deferMath = this.isStreaming && (
        hasUnclosedBlockMath(this.syncedContent)
        || isBlockMathContinuation(remaining)
        || remaining.includes('$$')
      )
      if (!deferMath) {
        const r = this.stateMachine.processLine(remaining)
        if (r.render) this.appendBlock(r)
      }
    }
  }

  /** 重置渲染状态（保留 syncedContent 由调用方管理） */
  private resetInternalState(): void {
    this.clearTimers()
    if (this.container instanceof HTMLElement) {
      this.container.classList.remove(CP('stream-done'))
      this.container.innerHTML = ''
    }
    this.blocks = []
    this.pendingMermaids.clear()
    this.lastBlockEl = null
    this.lastBlockWasGroup = false
    this.groupEl = null
    this.currentCodePre = null
    this.currentCodeRaw = ''
    this.currentCodeLang = ''
    this.paraBuf = ''
    this.paraEl = null
    this.inMermaidBlock = false
    this.buffer.reset()
    this.stateMachine.reset()
    this.mermaidRenderer.reset()
  }

  /** 流式过程中增量高亮当前代码块 */
  private refreshCodeHighlight(): void {
    if (!this.currentCodePre) return
    const code = this.currentCodePre.querySelector('code')
    if (!code) return
    if (!this.currentCodeRaw && !this.currentCodeLang) {
      code.textContent = ''
      return
    }
    if (!this.config.renderMarkdown) {
      code.textContent = this.currentCodeRaw
      return
    }
    const fence = '`'.repeat(3)
    const lang = this.currentCodeLang
    const full = lang
      ? `${fence}${lang}\n${this.currentCodeRaw}\n${fence}`
      : `${fence}\n${this.currentCodeRaw}\n${fence}`
    try {
      const highlighted = this.config.renderMarkdown(full)
      const tmp = document.createElement('div')
      tmp.innerHTML = highlighted
      const newCode = tmp.querySelector('pre code')
      if (newCode) {
        code.innerHTML = newCode.innerHTML
        code.className = newCode.className
        if (this.isStreaming) this.appendCodeStreamTail(code)
        return
      }
    } catch { /* fall through */ }
    code.textContent = this.currentCodeRaw
    if (this.isStreaming) this.appendCodeStreamTail(code)
  }

  /** 代码块流式尾部渐隐：仅作用于最后一行末尾 */
  private appendCodeStreamTail(code: HTMLElement): void {
    code.querySelector('.smd-h-fade-tail')?.remove()
    const span = document.createElement('span')
    span.className = 'smd-h-fade-tail'
    span.textContent = '\u200b'
    code.appendChild(span)
  }

  /** 代码块结束或流结束时：绑定复制按钮并清理状态 */
  private finalizeOpenCodeBlock(): void {
    if (!this.currentCodePre) return
    this.refreshCodeHighlight()
    const code = this.currentCodePre.querySelector('code')
    code?.querySelector('.smd-h-fade-tail')?.remove()
    this.currentCodePre = null
    this.currentCodeRaw = ''
    this.currentCodeLang = ''
  }
}

function isParagraphResult(r: ProcessResult): boolean {
  if (r.type !== 'markdown') return false
  if (r.replacePrev || r.newGroup) return false
  const line = r.content
  if (line === '\n') return false // 空行不分入段落
  if (/^#{1,6}\s/.test(line)) return false
  if (/^(---|\*\*\*|___)/.test(line)) return false
  if (line.startsWith('>') || line.startsWith('|')) return false
  if (/^(\s*)([-*+]|\d+[.)])\s+/.test(line)) return false
  if (isPartialListMarker(line)) return false
  return true
}
