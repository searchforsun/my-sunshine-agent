/**
 * 流式 Markdown + Mermaid 渲染引擎
 */
import { StreamBuffer } from './StreamBuffer'
import { MarkdownStateMachine } from './MarkdownStateMachine'
import { MermaidRenderer } from './MermaidRenderer'
import { registerGlobalHandlers } from './globalHandlers'
import { createToolButton, flashCopied } from './toolIcons'
import { smoothRender } from './smoothMarkdown'
import type { RendererConfig, ProcessResult, RenderBlock } from './types'
import { DEFAULT_CONFIG, RenderState } from './types'

export type { RendererConfig }

const CP = (s: string) => `${DEFAULT_CONFIG.classPrefix}${s}`

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
  private currentCodePre: HTMLElement | null = null
  private currentCodeRaw: string = ''
  private currentCodeLang: string = ''

  /** 段落平滑缓冲区 —— 累积 NORMAL 状态的文本，统一平滑渲染到同一个 DOM 元素 */
  private paraBuf: string = ''
  private paraEl: HTMLElement | null = null

  /** 是否正在 mermaid 代码块内（防平滑渲染泄漏） */
  private inMermaidBlock: boolean = false

  constructor(container: HTMLElement, config: Partial<RendererConfig> = {}) {
    this.container = container
    this.config = { ...DEFAULT_CONFIG, ...config }
    this.buffer = new StreamBuffer()
    this.stateMachine = new MarkdownStateMachine()
    this.mermaidRenderer = new MermaidRenderer(config)
    registerGlobalHandlers()
  }

  processChunk(chunk: string): void {
    this.isStreaming = true
    const lines = this.buffer.append(chunk)
    if (lines) this.processLines(lines)
    this.scheduleMermaidPolling()
    // 处理缓冲区中未完成的行（段落平滑输出）
    this.flushPendingParagraph()
  }

  finish(): void {
    this.isStreaming = false
    // 强制输出缓冲区剩余内容
    const remaining = this.buffer.forceFlush()
    if (remaining) this.processLines(remaining)
    // 刷新状态机
    const f = this.stateMachine.forceFlush()
    if (f.render && f.content) this.appendBlock(f)
    // 刷新段落缓冲区
    this.commitParagraph()
    this.clearTimers()
    this.renderAllPendingMermaids()
    this.container.classList.add(CP('stream-done'))
  }

  clear(): void {
    this.clearTimers()
    this.container.classList.remove(CP('stream-done'))
    this.container.innerHTML = ''
    this.blocks = [] ; this.pendingMermaids.clear()
    this.lastBlockEl = null ; this.lastBlockWasGroup = false
    this.currentCodePre = null ; this.currentCodeRaw = '' ; this.currentCodeLang = ''
    this.paraBuf = '' ; this.paraEl = null ; this.inMermaidBlock = false
    this.buffer.reset() ; this.stateMachine.reset() ; this.mermaidRenderer.reset()
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

    // 代码块
    if (result.type === 'code_block_start') {
      this.commitParagraph()
      this.lastBlockEl = null
      this.currentCodeLang = result.lang || ''
      this.currentCodeRaw = ''
      const pre = document.createElement('pre')
      const head = document.createElement('div')
      head.className = CP('code-header')
      const label = document.createElement('span')
      label.className = CP('code-lang')
      label.textContent = result.lang || 'code'
      const tools = document.createElement('div')
      tools.className = CP('toolbtns')
      const copyBtn = createToolButton(`${CP('toolbtn')} ${CP('toolbtn-copy')}`, 'copy', '复制')
      tools.appendChild(copyBtn)
      head.append(label, tools)
      pre.appendChild(head)
      const code = document.createElement('code')
      if (result.lang) code.className = `hljs language-${result.lang}`
      pre.appendChild(code)
      this.container.appendChild(pre)
      this.currentCodePre = pre
      return
    }
    if (result.type === 'code_block_line') {
      if (this.currentCodePre) {
        const code = this.currentCodePre.querySelector('code')
        if (code) code.appendChild(document.createTextNode(result.content + '\n'))
        this.currentCodeRaw += result.content + '\n'
      }
      return
    }
    if (result.type === 'code_block_end') {
      if (this.currentCodePre) {
        const lang = this.currentCodeLang; const raw = this.currentCodeRaw
        const copyBtn = this.currentCodePre.querySelector(`.${CP('toolbtn-copy')}`)
        if (copyBtn) {
          copyBtn.addEventListener('click', () => {
            navigator.clipboard.writeText(raw).then(() => {
              flashCopied(copyBtn as HTMLElement)
            }).catch(() => {})
          })
        }
        if (lang && this.config.renderMarkdown) {
          const fence = '`'.repeat(3)
          const full = `${fence}${lang}\n${raw}${fence}`
          const highlighted = this.config.renderMarkdown(full)
          const tmp = document.createElement('div'); tmp.innerHTML = highlighted
          const newPre = tmp.querySelector('pre')
          const newCode = newPre?.querySelector('code')
          if (newPre && newCode) {
            const oldCode = this.currentCodePre.querySelector('code')
            if (oldCode) oldCode.replaceWith(newCode)
          }
        }
        this.currentCodePre = null; this.currentCodeRaw = ''; this.currentCodeLang = ''
      }
      return
    }

    if (!result.content) return
    // 跳过纯空行
    if (result.content === '\n') return

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
    else this.handleBlockElement(block, result.replacePrev, result.newGroup)
  }

  /** 段落文本：用 smoothRender 渲染，替换段落专属元素（不影响 lastBlockEl） */
  private renderParagraph(): void {
    if (!this.config.renderMarkdown) return
    const text = this.paraBuf.trim()
    if (!text || isPartialListMarker(text)) return
    const html = smoothRender(text, this.config.renderMarkdown!)
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
      try { this.paraEl.innerHTML = this.config.renderMarkdown(this.paraBuf) } catch { /* ignore */ }
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
    // 跳过块级标记和列表项（等完整行到达后由状态机处理）
    if (/^[\s>#|`~\-*+]/.test(pending)) return
    if (/^(\s*)([-*+]|\d+[.)])\s*/.test(pending)) return
    if (isPartialListMarker(pending)) return
    if (!this.config.renderMarkdown) return

    const renderMd = this.config.renderMarkdown
    const parts: string[] = []

    if (this.paraBuf && !isPartialListMarker(this.paraBuf)) {
      parts.push(renderMd(this.paraBuf.trim()))
    }

    const previewHtml = smoothRender(pending, renderMd)
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

  private handleBlockElement(block: RenderBlock, replacePrev?: boolean, newGroup?: boolean): void {
    const renderMd = this.config.renderMarkdown
    const html = renderMd
      ? (this.isStreaming && shouldSmoothRenderBlock(block.content)
        ? smoothRender(block.content, renderMd)
        : renderMd(block.content))
      : block.content
    if (newGroup) { this.lastBlockEl = null; this.lastBlockWasGroup = false }
    if (replacePrev && this.lastBlockEl) {
      this.lastBlockEl.innerHTML = html; this.lastBlockWasGroup = true; return
    }
    if (this.lastBlockWasGroup) { this.lastBlockEl = null; this.lastBlockWasGroup = false }
    const el = document.createElement('div')
    el.className = CP('markdown-block')
    el.innerHTML = html
    this.container.appendChild(el)
    this.lastBlockEl = el
    block.element = el
    this.blocks.push(block)
  }

  // ═══════════════════════ Mermaid ═══════════════════════

  private appendMermaidPlaceholder(): void {
    this.inMermaidBlock = true
    this.lastBlockEl = null
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
        this.mermaidRenderer.render(ph.id || `mmd-${Date.now()}`, block.content, ph)
        this.buildMermaidUI(wrap as HTMLElement, block.content)
        return
      }
    }
    const { id, el: ph } = this.mermaidRenderer.createPlaceholder()
    this.container.appendChild(ph)
    this.pendingMermaids.set(id, { content: block.content, placeholder: ph })
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
    if (this.mermaidTimer) return
    this.mermaidTimer = setInterval(() => {
      for (const id of this.pendingMermaids.keys()) this.renderMermaidChart(id)
    }, this.config.mermaidRetryMs)
  }

  private async renderMermaidChart(id: string): Promise<void> {
    const p = this.pendingMermaids.get(id); if (!p) return
    await this.mermaidRenderer.render(id, p.content, p.placeholder)
    this.pendingMermaids.delete(id)
  }

  private async renderAllPendingMermaids(): Promise<void> {
    for (const id of [...this.pendingMermaids.keys()]) await this.renderMermaidChart(id)
  }

  private clearTimers(): void {
    if (this.mermaidTimer) { clearInterval(this.mermaidTimer); this.mermaidTimer = null }
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
  return true
}

/** SSE 分块可能只收到 "1" / "2." 等不完整有序列表前缀，不应作为段落渲染 */
function isPartialListMarker(s: string): boolean {
  const t = s.trim()
  if (!t) return false
  if (/^\d+\.?\)?$/.test(t)) return true
  if (/^\d+[.)]\s*$/.test(t)) return true
  return false
}

/** 表格 / 列表等块级结构不做 smoothRender，避免 tail 污染整表 */
function shouldSmoothRenderBlock(content: string): boolean {
  const t = content.trimStart()
  if (t.startsWith('|')) return false
  if (/^(\s*)([-*+]|\d+[.)])\s/.test(t)) return false
  return true
}
