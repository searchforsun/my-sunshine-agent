/**
 * Markdown 语法状态机
 *
 * 策略：所有元素逐行立即输出（流畅），仅 Mermaid 和表格需要完整上下文
 */
import { RenderState } from './types'
import type { StateContext, ProcessResult } from './types'

const TABLE_SEP = /^\|[\s\-:|]+\|$/
const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s+/
const INDENT_RE = /^\s{2,}\S/

export class MarkdownStateMachine {
  private ctx: StateContext = {
    currentState: RenderState.NORMAL,
    fenceChar: '',
    fenceLength: 0,
    mermaidContent: '',
    tableRows: [],
    thinkDepth: 0,
    bufLines: [],
    bufType: null,
  }

  processLine(line: string): ProcessResult {
    if (this.ctx.currentState === RenderState.THINKING) {
      if (line === '') this.ctx.currentState = RenderState.NORMAL
      return { render: false, content: '' }
    }

    switch (this.ctx.currentState) {
      case RenderState.CODE_BLOCK:
        return this.processCodeBlockLine(line)
      case RenderState.MERMAID_BLOCK:
        return this.processMermaidLine(line)
      case RenderState.TABLE:
        return this.processTableLine(line)
      default:
        return this.processNormalLine(line)
    }
  }

  forceFlush(): ProcessResult {
    if (this.ctx.currentState === RenderState.TABLE && this.ctx.tableRows.length > 0) {
      const len = this.ctx.tableRows.length
      const c = this.ctx.tableRows.join('\n'); this.ctx.tableRows = []; this.ctx.currentState = RenderState.NORMAL
      return { render: true, content: c, type: len >= 2 ? 'table' : 'markdown' }
    }
    if (this.ctx.bufType) {
      this.ctx.bufType = null; this.ctx.bufLines = []
    }
    return { render: false, content: '' }
  }

  reset(): void {
    this.ctx = { currentState: RenderState.NORMAL, fenceChar: '', fenceLength: 0, mermaidContent: '', tableRows: [], thinkDepth: 0, bufLines: [], bufType: null }
  }

  // ═══════════════════════════════════════════

  private processNormalLine(line: string): ProcessResult {
    // 1. 代码块围栏
    const fenceMatch = line.match(/^(`{3,}|~{3,})(\S*)\s*$/)
    if (fenceMatch) {
      return this.flushBuf() || (() => {
        this.ctx.fenceChar = fenceMatch[1][0]
        this.ctx.fenceLength = fenceMatch[1].length
        const lang = fenceMatch[2].toLowerCase()
        if (lang === 'mermaid') {
          this.ctx.currentState = RenderState.MERMAID_BLOCK
          this.ctx.mermaidContent = ''
          return { render: true, content: '', type: 'mermaid_placeholder' }
        }
        this.ctx.currentState = RenderState.CODE_BLOCK
        return { render: true, content: '', type: 'code_block_start', lang: lang || '' }
      })()
    }

    // 2. 表格
    if (line.startsWith('|') && line.includes('|')) {
      const f = this.flushBuf()
      if (f) return f
      this.ctx.currentState = RenderState.TABLE
      this.ctx.tableRows = [line]
      return { render: false, content: '' }
    }

    // 3. 空行 — 引用/列表缓冲中的空行可能属于该组
    if (line === '') {
      if (this.ctx.bufType === 'blockquote') {
        this.ctx.bufLines.push(line)
        return { render: true, content: this.ctx.bufLines.join('\n'), type: 'markdown', replacePrev: true }
      }
      if (this.ctx.bufType === 'list') {
        this.ctx.bufLines.push(line)
        return { render: true, content: this.ctx.bufLines.join('\n'), type: 'markdown', replacePrev: true }
      }
      return this.flushBuf() || { render: true, content: '\n', type: 'markdown' }
    }

    // 4. 检测是否延续当前缓冲组
    if (this.ctx.bufType === 'blockquote' && line.startsWith('>')) {
      this.ctx.bufLines.push(line)
      return { render: true, content: this.ctx.bufLines.join('\n'), type: 'markdown', replacePrev: true }
    }
    if (this.ctx.bufType === 'list' && isListLine(line)) {
      this.ctx.bufLines.push(line)
      return { render: true, content: this.ctx.bufLines.join('\n'), type: 'markdown', replacePrev: true }
    }
    if (this.ctx.bufType === 'list' && isIndented(line)) {
      this.ctx.bufLines.push(line)
      return { render: true, content: this.ctx.bufLines.join('\n'), type: 'markdown', replacePrev: true }
    }

    // 5. 缓冲组结束 → 刷新
    const flushed = this.flushBuf()
    if (flushed) {
      // 用新行重新处理
      const next = this.processNormalLine(line)
      return next.render ? next : { render: false, content: '' }
    }

    // 6. 开始新缓冲组
    if (line.startsWith('>')) {
      this.ctx.bufType = 'blockquote'
      this.ctx.bufLines = [line]
      return { render: true, content: line, type: 'markdown', newGroup: true }
    }
    if (isListLine(line)) {
      this.ctx.bufType = 'list'
      this.ctx.bufLines = [line]
      return { render: true, content: line, type: 'markdown', newGroup: true }
    }

    // 7. 普通行
    return { render: true, content: line, type: 'markdown' }
  }

  /** 刷新缓冲组 */
  private flushBuf(): ProcessResult | null {
    if (!this.ctx.bufType) return null
    this.ctx.bufType = null
    this.ctx.bufLines = []
    return { render: false, content: '' }
  }

  private processCodeBlockLine(line: string): ProcessResult {
    const fence = this.ctx.fenceChar.repeat(this.ctx.fenceLength)
    if (line.trimStart().startsWith(fence)) {
      this.ctx.currentState = RenderState.NORMAL
      return { render: true, content: '', type: 'code_block_end' }
    }
    return { render: true, content: line, type: 'code_block_line' }
  }

  private processMermaidLine(line: string): ProcessResult {
    const fence = this.ctx.fenceChar.repeat(this.ctx.fenceLength)
    if (line.trimStart().startsWith(fence)) {
      this.ctx.currentState = RenderState.NORMAL
      const c = this.ctx.mermaidContent.trimEnd(); this.ctx.mermaidContent = ''
      return c ? { render: true, content: c, type: 'mermaid' } : { render: false, content: '' }
    }
    this.ctx.mermaidContent += line + '\n'
    return { render: false, content: '' }
  }

  private processTableLine(line: string): ProcessResult {
    if (TABLE_SEP.test(line) || (line.startsWith('|') && line.includes('|'))) {
      this.ctx.tableRows.push(line)
      // 至少要有表头+分隔行才输出（2 行），之后逐行更新
      if (this.ctx.tableRows.length >= 2) {
        return {
          render: true, content: this.ctx.tableRows.join('\n'), type: 'table',
          replacePrev: this.ctx.tableRows.length > 2,
          newGroup: this.ctx.tableRows.length === 2,
        }
      }
      return { render: false, content: '' }
    }
    // 表格结束
    this.ctx.currentState = RenderState.NORMAL
    this.ctx.tableRows = []
    // 处理下一行
    if (line !== '') {
      return this.processNormalLine(line)
    }
    return { render: false, content: '' }
  }
}

/** 行是否为列表项（- / * / + / 1. / 1) 开头） */
function isListLine(line: string): boolean {
  return LIST_RE.test(line)
}

/** 行是否缩进（列表项续行） */
function isIndented(line: string): boolean {
  return INDENT_RE.test(line)
}
