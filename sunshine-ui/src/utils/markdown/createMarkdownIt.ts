import MarkdownIt from 'markdown-it'
import markdownItHighlightjs from 'markdown-it-highlightjs'
import markdownItTaskLists from 'markdown-it-task-lists'
import { katex } from '@mdit/plugin-katex'
import type hljs from 'highlight.js/lib/core'

export function createMarkdownIt(hljsInstance: typeof hljs): MarkdownIt {
  return new MarkdownIt({
    html: true,
    breaks: true,
    linkify: true,
    typographer: true,
  })
    .use(markdownItHighlightjs, { hljs: hljsInstance })
    .use(markdownItTaskLists)
    .use(katex, {
      delimiters: 'dollars',
      katexOptions: {
        throwOnError: false,
        strict: 'ignore',
        output: 'html',
      },
    })
}
