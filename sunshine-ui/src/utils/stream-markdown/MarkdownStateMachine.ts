/**
 * Markdown 语法状态机
 *
 * 策略：所有元素逐行立即输出（流畅），仅 Mermaid 和表格需要完整上下文
 */
import { RenderState } from './types'
import type { StateContext, ProcessResult } from './types'
import { parseGluedFenceLang } from './normalizeStreamingMarkdown'
import { isIndented, isListLine, isPartialListMarker } from './listMarkers'

const TABLE_SEP = /^\|[\s\-:|]+\|$/

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
    lastListEmitLen: 0,
    lastBlockquoteEmitLen: 0,
    lastTableEmitLen: 0,
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
      if (len >= 2 && len === this.ctx.lastTableEmitLen) {
        this.ctx.tableRows = []
        this.ctx.currentState = RenderState.NORMAL
        return { render: false, content: '' }
      }
      const c = this.ctx.tableRows.join('\n')
      this.ctx.tableRows = []
      this.ctx.currentState = RenderState.NORMAL
      this.ctx.lastTableEmitLen = 0
      if (len >= 2) {
        return { render: true, content: c, type: 'table', replacePrev: true }
      }
      return { render: false, content: '' }
    }
    if (this.ctx.bufType === 'list' && this.ctx.bufLines.length > 0) {
      const len = this.ctx.bufLines.length
      if (len >= 2 && len === this.ctx.lastListEmitLen) {
        this.ctx.bufType = null
        this.ctx.bufLines = []
        return { render: false, content: '' }
      }
      const c = this.ctx.bufLines.join('\n')
      this.ctx.bufType = null
      this.ctx.bufLines = []
      this.ctx.lastListEmitLen = 0
      if (len >= 2) {
        return { render: true, content: c, type: 'markdown', replacePrev: true }
      }
      return { render: true, content: c, type: 'markdown', newGroup: true }
    }
    if (this.ctx.bufType === 'blockquote' && this.ctx.bufLines.length > 0) {
      const len = this.ctx.bufLines.length
      if (len >= 2 && len === this.ctx.lastBlockquoteEmitLen) {
        this.ctx.bufType = null
        this.ctx.bufLines = []
        return { render: false, content: '' }
      }
      const c = this.ctx.bufLines.join('\n')
      this.ctx.bufType = null
      this.ctx.bufLines = []
      this.ctx.lastBlockquoteEmitLen = 0
      if (len >= 2) {
        return { render: true, content: c, type: 'markdown', replacePrev: true }
      }
      return { render: true, content: c, type: 'markdown', newGroup: true }
    }
    if (this.ctx.bufType) {
      this.ctx.bufType = null
      this.ctx.bufLines = []
    }
    return { render: false, content: '' }
  }

  reset(): void {
    this.ctx = {
      currentState: RenderState.NORMAL,
      fenceChar: '',
      fenceLength: 0,
      mermaidContent: '',
      tableRows: [],
      thinkDepth: 0,
      bufLines: [],
      bufType: null,
      lastListEmitLen: 0,
      lastBlockquoteEmitLen: 0,
      lastTableEmitLen: 0,
    }
  }

  // ═══════════════════════════════════════════

  private processNormalLine(line: string): ProcessResult {
    // 1. 代码块围栏（允许行首空白、围栏后可选空白再跟语言）
    const fenceMatch = line.match(/^\s*(`{3,}|~{3,})\s*(\S*)\s*$/)
    if (fenceMatch) {
      this.flushBuf()
      this.ctx.fenceChar = fenceMatch[1][0]
      this.ctx.fenceLength = fenceMatch[1].length
      const { lang, remainder } = parseGluedFenceLang(fenceMatch[2])
      if (lang === 'mermaid') {
        this.ctx.currentState = RenderState.MERMAID_BLOCK
        this.ctx.mermaidContent = ''
        return { render: true, content: '', type: 'mermaid_placeholder' }
      }
      this.ctx.currentState = RenderState.CODE_BLOCK
      return {
        render: true, content: '', type: 'code_block_start',
        lang: lang || '', initialCodeLine: remainder || undefined,
      }
    }

    // 2. 表格
    if (line.startsWith('|') && line.includes('|')) {
      this.flushBuf()
      this.ctx.currentState = RenderState.TABLE
      this.ctx.tableRows = [line]
      return { render: false, content: '' }
    }

    // 3. 空行 — 引用/列表缓冲中的空行可能属于该组
    if (line === '') {
      if (this.ctx.bufType === 'blockquote') {
        this.ctx.bufLines.push(line)
        if (this.ctx.bufLines.length === 1) {
          return { render: false, content: '' }
        }
        if (this.ctx.bufLines.length === 2) {
          return this.bufRender(this.ctx.bufLines.join('\n'), false, true)
        }
        return this.bufRender(this.ctx.bufLines.join('\n'), true, false)
      }
      if (this.ctx.bufType === 'list') {
        this.ctx.bufLines.push(line)
        if (this.ctx.bufLines.length === 2) {
          return this.bufRender(this.ctx.bufLines.join('\n'), false, true)
        }
        return this.bufRender(this.ctx.bufLines.join('\n'), true, false)
      }
      this.flushBuf()
      return { render: true, content: '\n', type: 'markdown' }
    }

    // 4. 检测是否延续当前缓冲组
    if (this.ctx.bufType === 'blockquote' && line.startsWith('>')) {
      this.ctx.bufLines.push(line)
      if (this.ctx.bufLines.length === 2) {
        return this.bufRender(this.ctx.bufLines.join('\n'), false, true)
      }
      return this.bufRender(this.ctx.bufLines.join('\n'), true, false)
    }
    if (this.ctx.bufType === 'list' && isListLine(line)) {
      this.ctx.bufLines.push(line)
      if (this.ctx.bufLines.length === 2) {
        return this.bufRender(this.ctx.bufLines.join('\n'), false, true)
      }
      return this.bufRender(this.ctx.bufLines.join('\n'), true, false)
    }
    if (this.ctx.bufType === 'list' && isIndented(line)) {
      this.ctx.bufLines.push(line)
      if (this.ctx.bufLines.length === 2) {
        return this.bufRender(this.ctx.bufLines.join('\n'), false, true)
      }
      return this.bufRender(this.ctx.bufLines.join('\n'), true, false)
    }

    // 5. 缓冲组结束 → 刷新后重新处理
    if (this.ctx.bufType) {
      if (this.ctx.bufType === 'list' && isPartialListMarker(line)) {
        return { render: false, content: '' }
      }
      this.flushBuf()
      return this.processNormalLine(line)
    }

    // 6. 开始新缓冲组
    if (line.startsWith('>')) {
      this.ctx.bufType = 'blockquote'
      this.ctx.bufLines = [line]
      return { render: false, content: '' }
    }
    if (isListLine(line)) {
      this.ctx.bufType = 'list'
      this.ctx.bufLines = [line]
      return { render: false, content: '' }
    }

    if (isPartialListMarker(line)) {
      return { render: false, content: '' }
    }

    // 7. 普通行
    return { render: true, content: line, type: 'markdown' }
  }

  /** 清空列表/引用缓冲（内容已通过 replacePrev 落盘，此处仅重置状态） */
  private flushBuf(): void {
    if (!this.ctx.bufType) return
    this.ctx.bufType = null
    this.ctx.bufLines = []
    this.ctx.lastListEmitLen = 0
    this.ctx.lastBlockquoteEmitLen = 0
  }

  private markBufEmit(): void {
    if (this.ctx.bufType === 'list') {
      this.ctx.lastListEmitLen = this.ctx.bufLines.length
    } else if (this.ctx.bufType === 'blockquote') {
      this.ctx.lastBlockquoteEmitLen = this.ctx.bufLines.length
    }
  }

  private bufRender(content: string, replacePrev: boolean, newGroup: boolean): ProcessResult {
    this.markBufEmit()
    return { render: true, content, type: 'markdown', replacePrev, newGroup }
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
        this.ctx.lastTableEmitLen = this.ctx.tableRows.length
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
