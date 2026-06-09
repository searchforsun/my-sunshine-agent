/**
 * 流式 Markdown + Mermaid 渲染引擎
 *
 * 用法:
 *   import { StreamMarkdownRenderer } from '@/utils/stream-markdown'
 *   const renderer = new StreamMarkdownRenderer(container)
 *   renderer.processChunk('# Hello\n')
 *   renderer.finish()
 */

export { StreamMarkdownRenderer } from './StreamMarkdownRenderer'
export { StreamBuffer } from './StreamBuffer'
export { MarkdownStateMachine } from './MarkdownStateMachine'
export { MermaidRenderer } from './MermaidRenderer'
export { RenderState } from './types'
export type { RendererConfig, ProcessResult, StateContext, RenderBlock } from './types'
