/**
 * 流式 Markdown + Mermaid 渲染引擎 — 类型定义
 */

/** 渲染状态枚举 */
export enum RenderState {
  NORMAL = 'normal',
  THINKING = 'thinking',
  CODE_BLOCK = 'code_block',
  MERMAID_BLOCK = 'mermaid_block',
  TABLE = 'table',
  INLINE_PAIRS = 'inline_pairs',
}

/** 状态上下文 */
export interface StateContext {
  currentState: RenderState
  fenceChar: string
  fenceLength: number
  mermaidContent: string
  tableRows: string[]
  thinkDepth: number
  bufLines: string[]
  bufType: 'blockquote' | 'list' | null
  /** 已通过 processLine 落盘的缓冲行数，避免 forceFlush 重复 append */
  lastListEmitLen: number
  lastBlockquoteEmitLen: number
  lastTableEmitLen: number
}

/** 单行处理结果 */
export interface ProcessResult {
  /** 是否应该渲染 */
  render: boolean
  /** 渲染内容（已处理过的安全内容） */
  content: string
  /** 渲染类型 */
  type?: 'markdown' | 'mermaid' | 'mermaid_placeholder' | 'table'
    | 'code_block_start' | 'code_block_line' | 'code_block_end'
  /** 代码块语言（code_block_start 时附带） */
  lang?: string
  /** 围栏行粘连的同行代码（```javapublic → public…） */
  initialCodeLine?: string
  /** 替换上一个块的 innerHTML，而非追加（引用/列表逐行缓冲时用） */
  replacePrev?: boolean
  /** 新组开始，不应合并到前一个元素 */
  newGroup?: boolean
}

/** 渲染块 */
export interface RenderBlock {
  id: string
  type: 'markdown' | 'mermaid'
  content: string
  element?: HTMLElement
}

/** 内联标记对定义 */
export interface InlinePair {
  marker: string
  regex: RegExp
}

/** Markdown 文本 → HTML 渲染函数 */
export type MarkdownRenderFn = (markdown: string) => string

/** 渲染引擎配置 */
export interface RendererConfig {
  /** 防抖延迟 ms（默认 100） */
  debounceMs: number
  /** Mermaid 重试间隔 ms（默认 1500） */
  mermaidRetryMs: number
  /** 自定义 CSS 类名前缀 */
  classPrefix: string
  /** Markdown → HTML 渲染函数 */
  renderMarkdown?: MarkdownRenderFn
}

export const DEFAULT_CONFIG: RendererConfig = {
  debounceMs: 100,
  mermaidRetryMs: 1500,
  classPrefix: 'smd-',
}
