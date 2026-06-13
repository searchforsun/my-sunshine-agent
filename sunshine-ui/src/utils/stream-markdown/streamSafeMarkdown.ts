/**
 * 流式渲染专用 — 截断未闭合/误闭合块级公式，避免 KaTeX 分裂
 */
import { smoothRender } from './smoothMarkdown'
import { prepareStreamingMarkdown } from './mathBlock'

export {
  hasUnclosedBlockMath,
  isBlockMathContinuation,
  prepareStreamingMarkdown,
  trimFalseClosedBlockMath,
  trimIncompleteBlockMath,
  trimPrematureBlockMathClose,
} from './mathBlock'

export function streamSafeMarkdownRender(
  text: string,
  renderMd: (md: string) => string,
  fullText?: string,
): string {
  const prepared = prepareStreamingMarkdown(text, fullText)
  if (!prepared.trim()) return ''
  return smoothRender(prepared, renderMd)
}
