/**
 * 流式 Markdown + Mermaid 渲染引擎
 */
import { StreamBuffer } from './StreamBuffer'
import { MarkdownStateMachine } from './MarkdownStateMachine'
import { MermaidRenderer } from './MermaidRenderer'
import { registerGlobalHandlers } from './globalHandlers'
import type { RendererConfig, ProcessResult, RenderBlock } from './types'
import { DEFAULT_CONFIG } from './types'

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
  }

  finish(): void {
    this.isStreaming = false
    const r = this.buffer.forceFlush(); if (r) this.processLines(r + '\n')
    const f = this.stateMachine.forceFlush(); if (f.render && f.content) this.appendBlock(f)
    this.clearTimers()
    this.renderAllPendingMermaids()
  }

  clear(): void {
    this.clearTimers()
    this.container.innerHTML = ''
    this.blocks = [] ; this.pendingMermaids.clear()
    this.lastBlockEl = null ; this.currentCodePre = null
    this.currentCodeRaw = '' ; this.currentCodeLang = ''
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
    if (result.type === 'mermaid_placeholder') { this.appendMermaidPlaceholder(); return }

    // ── 代码块开始 ──
    if (result.type === 'code_block_start') {
      this.lastBlockEl = null
      this.currentCodeLang = result.lang || ''
      this.currentCodeRaw = ''

      const pre = document.createElement('pre')
      const head = document.createElement('div')
      head.className = CP('code-header')
      head.innerHTML = `<span>${esc(result.lang || 'code')}</span><span class="${CP('code-copy')}" onclick="window.__smd_copyCode(this)" title="复制">◳</span>`
      pre.appendChild(head)

      const code = document.createElement('code')
      if (result.lang) code.className = `hljs language-${result.lang}`
      pre.appendChild(code)
      this.container.appendChild(pre)
      this.currentCodePre = pre
      return
    }

    // ── 代码块行 ──
    if (result.type === 'code_block_line') {
      if (this.currentCodePre) {
        const code = this.currentCodePre.querySelector('code')
        if (code) code.appendChild(document.createTextNode(result.content + '\n'))
        this.currentCodeRaw += result.content + '\n'
      }
      return
    }

    // ── 代码块结束 ──
    if (result.type === 'code_block_end') {
      if (this.currentCodePre) {
        const lang = this.currentCodeLang
        const raw = this.currentCodeRaw

        // 复制按钮
        const copyBtn = this.currentCodePre.querySelector(`.${CP('code-copy')}`)
        if (copyBtn) {
          copyBtn.addEventListener('click', () => {
            navigator.clipboard.writeText(raw).then(() => {
              (copyBtn as HTMLElement).textContent = '已复制'
              setTimeout(() => { (copyBtn as HTMLElement).textContent = '复制' }, 1500)
            }).catch(() => {})
          })
        }

        // highlight.js 重新渲染
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
        this.currentCodePre = null
        this.currentCodeRaw = ''
        this.currentCodeLang = ''
      }
      return
    }

    if (!result.content) return

    const block: RenderBlock = { id: `smd-${this.blocks.length}-${Date.now()}`, type: result.type === 'mermaid' ? 'mermaid' : 'markdown', content: result.content }
    if (block.type === 'mermaid') this.handleMermaidBlock(block)
    else this.handleMarkdownBlock(block, result.replacePrev, result.newGroup)
  }

  // ═══════════════════════ Mermaid ═══════════════════════

  private appendMermaidPlaceholder(): void {
    this.lastBlockEl = null
    const wrap = document.createElement('div')
    wrap.className = CP('mermaid-wrapper')

    const head = document.createElement('div')
    head.className = CP('mermaid-header')
    head.textContent = 'mermaid'
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
        (wrap as HTMLElement).dataset.mermaidSource = block.content
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
    // 工具栏追加到 header 内部（共用一行，按钮分组避免 space-between 居中）
    const header = wrap.querySelector(`.${CP('mermaid-header')}`)
    if (header) {
      header.innerHTML += `<span class="${CP('mermaid-toolbtns')}">
        <span class="${CP('mermaid-toolbtn')} smd-toolbtn-toggle" onclick="window.__smd_mermaidToggle(this)" title="源码 / 图表">◫</span>
        <span class="${CP('mermaid-toolbtn')} smd-toolbtn-zoom" onclick="window.__smd_mermaidZoom(this)" title="全屏">◰</span>
      </span>`
    }
  }

  // ═══════════════════════ Markdown ═══════════════════════

  private handleMarkdownBlock(block: RenderBlock, replacePrev?: boolean, newGroup?: boolean): void {
    const html = this.config.renderMarkdown ? this.config.renderMarkdown(block.content) : block.content

    // 新组开始：不合并到前一个元素
    if (newGroup) {
      this.lastBlockEl = null
      this.lastBlockWasGroup = false
    }

    // 引用/列表缓冲更新：替换上一个块的 innerHTML
    if (replacePrev && this.lastBlockEl) {
      this.lastBlockEl.innerHTML = html
      this.lastBlockWasGroup = true
      return
    }

    // 缓冲组刚结束：不合并到组元素，创建新块
    if (this.lastBlockWasGroup) {
      this.lastBlockEl = null
      this.lastBlockWasGroup = false
    }

    if (this.lastBlockEl && !html.startsWith('<h') && !html.startsWith('<hr') && !html.startsWith('\n')) {
      this.lastBlockEl.innerHTML += html; return
    }
    const el = document.createElement('div')
    el.className = CP('markdown-block')
    el.innerHTML = html
    this.container.appendChild(el)
    this.lastBlockEl = el
    block.element = el
    this.blocks.push(block)
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

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// 全局事件处理器已抽取至 globalHandlers.ts — StreamMarkdownRenderer 构造时注册
