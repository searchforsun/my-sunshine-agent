/**
 * 静态 Markdown DOM 增强器 — 为 markdown-it 渲染的静态内容补充代码头/Mermaid
 */
import { MermaidRenderer, resetMermaidSvgCacheForTheme } from './MermaidRenderer'
import { DEFAULT_CONFIG } from './types'
import { ensureMermaidInitialized, resetMermaidInitCache } from './mermaidConfig'
import { getCachedMermaidSvg } from './mermaidSvgCache'

// 确保全局 onclick 处理器已注册
import { registerGlobalHandlers, createMermaidToolButtons } from './globalHandlers'
import { createToolButton } from './toolIcons'
import { matchSandboxPathByIndex, isSandboxContainerPath } from '../../utils/sandboxPathChip'

registerGlobalHandlers()

const CP = (s: string) => `${DEFAULT_CONFIG.classPrefix}${s}`

// 共享实例，保证 mermaid 图表 ID 全局唯一
const sharedMermaidRenderer = new MermaidRenderer()

/** 为静态渲染的 .msg-md 容器补充代码块头部和 Mermaid 渲染 */
export function enhanceStaticMarkdown(
  container: HTMLElement,
  options?: { deferMermaid?: boolean; source?: string; basePath?: string },
): void {
  void options
  const basePath = options?.basePath ?? ''
  const pres = container.querySelectorAll('pre')

  for (let i = pres.length - 1; i >= 0; i--) {
    const pre = pres[i] as HTMLElement
    // 跳过已处理过的
    if (pre.querySelector(`.${CP('code-header')}`)) continue
    if (pre.closest(`.${CP('mermaid-wrapper')}`)) continue

    const code = pre.querySelector('code')
    if (!code) continue

    const cls = code.className || ''
    const langMatch = cls.match(/language-(\w+)/)
    const lang = langMatch ? langMatch[1] : ''
    const raw = code.textContent || ''

    if (lang === 'mermaid') {
      enhanceMermaidBlock(pre, raw)
    } else {
      enhanceCodeBlock(pre, lang, raw)
    }
  }

  renderPendingStaticMermaids(container)
  // 流式中的容器不做路径增强：v-html 每 chunk 重建 DOM，若每次重建后重新加高亮会闪烁；
  // 流式结束（无 .streaming）后由 enhanceAllStaticMarkdown / StaticMarkdown watch 统一增强。
  if (isStreamingContainer(container)) return
  // 路径增强采用视口内懒加载：可见容器立即增强，不可见容器由 observer 滚动时触发
  const rect = container.getBoundingClientRect()
  if (rect.bottom > -200 && rect.top < window.innerHeight + 200) {
    enhanceSandboxPathLinks(container, basePath)
    container.dataset.smdPathEnhanced = '1'
    if (basePath) container.dataset.smdBasePath = basePath
  } else {
    ensureSandboxPathObserver(basePath)
  }
}

/** 容器自身或祖先处于流式输出（.streaming）时返回 true */
function isStreamingContainer(el: HTMLElement): boolean {
  return el.classList.contains('streaming') || !!el.closest('.streaming')
}

function enhanceCodeBlock(pre: HTMLElement, lang: string, _raw: string): void {
  const head = document.createElement('div')
  head.className = CP('code-header')
  const label = document.createElement('span')
  label.className = CP('code-lang')
  label.textContent = lang || 'code'
  const tools = document.createElement('div')
  tools.className = CP('toolbtns')
  tools.appendChild(createToolButton(
    `${CP('toolbtn')} ${CP('toolbtn-copy')}`,
    'copy',
    '复制',
  ))
  head.append(label, tools)
  pre.insertBefore(head, pre.firstChild)
}

function enhanceMermaidBlock(pre: HTMLElement, source: string): void {
  const trimmed = source.trim()
  const wrap = document.createElement('div')
  wrap.className = CP('mermaid-wrapper')
  wrap.dataset.mermaidSource = trimmed

  const head = document.createElement('div')
  head.className = CP('mermaid-header')
  const label = document.createElement('span')
  label.className = CP('mermaid-label')
  label.textContent = 'mermaid'
  head.appendChild(label)
  wrap.appendChild(head)

  const cached = getCachedMermaidSvg(trimmed)
  if (cached) {
    wrap.appendChild(sharedMermaidRenderer.createChartEl(cached))
    head.appendChild(createMermaidToolButtons())
    pre.parentNode?.replaceChild(wrap, pre)
    return
  }

  const { el: placeholder } = sharedMermaidRenderer.createPlaceholder()
  wrap.appendChild(placeholder)
  pre.parentNode?.replaceChild(wrap, pre)
  renderMermaidChart(wrap, head, placeholder)
}

function renderPendingStaticMermaids(container: HTMLElement): void {
  const wraps = container.querySelectorAll(`.${CP('mermaid-wrapper')}`)
  for (const wrap of wraps) {
    const el = wrap as HTMLElement
    const placeholder = el.querySelector(`.${CP('mermaid-loading')}`) as HTMLElement | null
    if (!placeholder) continue
    const head = el.querySelector(`.${CP('mermaid-header')}`) as HTMLElement | null
    if (!head) continue
    renderMermaidChart(el, head, placeholder)
  }
}

/** 获取当前工作区根路径（由 ChatView 注入到 window.__smd_sandboxRoot） */
function getSandboxRoot(): string {
  return (window as any).__smd_sandboxRoot ?? ''
}

/** 将路径增强为蓝色可点击链接（写入 data-sandbox-path 属性） */
function applyPathAttrs(el: HTMLElement, displayPath: string, resolvedPath: string): void {
  el.dataset.sandboxPathEnhanced = '1'
  el.setAttribute('data-sandbox-path', resolvedPath)
  el.setAttribute('data-sandbox-path-raw', displayPath)
  el.classList.add('smd-sandbox-path')
  el.setAttribute('title', resolvedPath)
  el.setAttribute('role', 'link')
  el.setAttribute('tabindex', '0')
}

/**
 * 将 inline code、纯文本节点和 <a> 标签中的沙箱路径增强为蓝色可点击链接。
 * 支持绝对路径（/workspace/... /skills/...）和相对路径（src/App.vue）。
 * 相对路径结合 window.__smd_sandboxRoot 解析为绝对路径。
 * basePath：文件预览场景的当前文件路径，用于解析 markdown 标准链接 [text](relative/path) 的相对 href。
 */
function enhanceSandboxPathLinks(container: HTMLElement, basePath = ''): void {
  // 1. inline code 元素
  const codes = container.querySelectorAll('code:not(pre code)')
  for (const code of codes) {
    const el = code as HTMLElement
    if (el.dataset.sandboxPathEnhanced) continue
    const text = el.textContent?.trim() ?? ''
    if (!text) continue
    const root = getSandboxRoot()
    const { resolved } = matchSandboxPathByIndex(text, root)
    if (!resolved) continue
    applyPathAttrs(el, text, resolved)
  }

  // 2. 纯文本节点：遍历 p/li/td 等元素中的 TextNode，将路径片段包裹为 <code class="smd-sandbox-path">
  const textContainers = container.querySelectorAll('p, li, td, dd, dt, blockquote')
  for (const host of textContainers) {
    enhanceTextNodesInElement(host as HTMLElement)
  }

  // 3. <a> 标签：将指向工作区相对文件的 markdown 标准链接拦截为沙箱路径打开
  enhanceAnchorLinks(container, basePath)
}

/** 解析相对路径：结合当前文件所在目录（basePath 的父目录）解析 href */
function resolveRelativeHref(href: string, basePath: string): string {
  const root = getSandboxRoot()
  if (!root) return ''
  // 绝对路径（/workspace/... /skills/...）：直接用 matchSandboxPathByIndex 处理
  if (isSandboxContainerPath(href)) {
    return matchSandboxPathByIndex(href, root).resolved
  }
  // 跳过外部链接、锚点、mailto、data 等
  if (/^(https?:|mailto:|tel:|data:|javascript:|#)/i.test(href)) return ''
  const cleanHref = href.split('#')[0].split('?')[0].trim()
  if (!cleanHref) return ''
  // 有 basePath（文件预览场景）：相对当前文件所在目录解析
  if (basePath && isSandboxContainerPath(basePath)) {
    const dir = basePath.lastIndexOf('/') > 0 ? basePath.slice(0, basePath.lastIndexOf('/')) : basePath
    const resolved = `${dir}/${cleanHref}`.replace(/\/+/g, '/')
    const { resolved: matched } = matchSandboxPathByIndex(resolved, root)
    if (matched) return matched
    // 退而求其次：相对 workspace root 解析
    const { resolved: rootRel } = matchSandboxPathByIndex(cleanHref, root)
    if (rootRel) return rootRel
    return ''
  }
  // 无 basePath（对话区场景）：相对 workspace root 解析
  const { resolved: rootRel } = matchSandboxPathByIndex(cleanHref, root)
  return rootRel
}

/** 将 <a> 标签的相对路径 href 增强为沙箱文件打开 */
function enhanceAnchorLinks(container: HTMLElement, basePath: string): void {
  const anchors = container.querySelectorAll('a[href]')
  for (const a of anchors) {
    const el = a as HTMLAnchorElement
    if (el.dataset.sandboxPathEnhanced) continue
    const href = el.getAttribute('href') ?? ''
    if (!href) continue
    const resolved = resolveRelativeHref(href, basePath)
    if (!resolved) continue
    el.dataset.sandboxPathEnhanced = '1'
    el.setAttribute('data-sandbox-path', resolved)
    el.setAttribute('data-sandbox-path-raw', href)
    el.classList.add('smd-sandbox-path')
    el.setAttribute('title', resolved)
    el.removeAttribute('href')
  }
}

/** 在元素的纯文本子节点中扫描路径，将其替换为可点击 code 元素 */
function enhanceTextNodesInElement(host: HTMLElement): void {
  // 跳过已处理过的容器
  if (host.dataset.sandboxTextScanned) return
  host.dataset.sandboxTextScanned = '1'

  const walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT, {
    acceptNode(node: Text): number {
      // 跳过已在 code/a 标签内的文本
      const parent = node.parentElement
      if (!parent) return NodeFilter.FILTER_REJECT
      if (parent.closest('code, a, pre, .smd-sandbox-path')) return NodeFilter.FILTER_REJECT
      const text = node.nodeValue ?? ''
      if (!text || text.length < 3) return NodeFilter.FILTER_REJECT
      return NodeFilter.FILTER_ACCEPT
    },
  })

  const targets: Text[] = []
  let current: Node | null
  while ((current = walker.nextNode())) targets.push(current as Text)

  const root = getSandboxRoot()
  for (const textNode of targets) {
    const text = textNode.nodeValue ?? ''
    if (!text) continue
    // 正则匹配：绝对沙箱路径 / 多段相对路径（含尾斜杠目录，如 example/）/ 单段带扩展名文件名
    const re = /(\/(?:workspace|skills)(?:\/[^\s`<>|"{}()]+)?)|((?:[A-Za-z0-9._-]+\/){1,}[A-Za-z0-9._-]*\/?)|([A-Za-z0-9._-]+\.[A-Za-z0-9]{1,12})/g
    let lastIndex = 0
    let m: RegExpExecArray | null
    const fragments: Node[] = []
    let hasMatch = false
    while ((m = re.exec(text)) !== null) {
      hasMatch = true
      // 前面的纯文本
      if (m.index > lastIndex) {
        fragments.push(document.createTextNode(text.slice(lastIndex, m.index)))
      }
      const matched = m[1] || m[2] || m[3]
      const { resolved } = matchSandboxPathByIndex(matched, root)
      if (resolved) {
        const code = document.createElement('code')
        code.textContent = matched
        applyPathAttrs(code, matched, resolved)
        fragments.push(code)
      } else {
        fragments.push(document.createTextNode(matched))
      }
      lastIndex = m.index + matched.length
    }
    if (!hasMatch) continue
    // 尾部纯文本
    if (lastIndex < text.length) {
      fragments.push(document.createTextNode(text.slice(lastIndex)))
    }
    // 替换原文本节点
    const parent = textNode.parentElement
    if (parent && fragments.length) {
      for (const frag of fragments) parent.insertBefore(frag, textNode)
      parent.removeChild(textNode)
    }
  }
}

function renderMermaidChart(wrap: HTMLElement, head: HTMLElement, placeholder: HTMLElement): void {
  const source = wrap.dataset.mermaidSource || ''
  if (!source.trim()) return
  void sharedMermaidRenderer.mount(placeholder, source).then((ok) => {
    // 错误态已直接展示源码，不挂工具栏按钮
    if (ok && wrap.isConnected && !wrap.querySelector(`.${CP('mermaid-error')}`)
      && !head.querySelector(`.${CP('toolbtn-toggle')}`)) {
      head.appendChild(createMermaidToolButtons())
    }
  })
}

/** 主题切换：清缓存 → 按新主题重绘 → 写回缓存 */
export async function reRenderStaticMermaids(): Promise<void> {
  resetMermaidSvgCacheForTheme()
  resetMermaidInitCache()
  ensureMermaidInitialized()

  const tasks: Promise<boolean>[] = []
  const wrappers = document.querySelectorAll(`.${DEFAULT_CONFIG.classPrefix}mermaid-wrapper`)
  for (const wrap of wrappers) {
    const el = wrap as HTMLElement
    const source = el.dataset.mermaidSource?.trim()
    if (!source) continue

    const chart = el.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement | null
    const error = el.querySelector(`.${CP('mermaid-error')}`) as HTMLElement | null
    const body = chart || error
    if (!body) continue

    tasks.push(sharedMermaidRenderer.renderInto(body, source))
  }
  await Promise.all(tasks)
}

/**
 * 索引就绪后重新增强已渲染消息中的沙箱路径链接。
 * 清除纯文本容器的已扫描标记，使索引未就绪时漏掉的相对路径能被重新扫描并增强。
 * 已增强为 code 链接的节点不受影响（walker 会跳过 .smd-sandbox-path 内的文本）。
 * 采用视口内懒加载：仅对当前可见容器立即增强，不可见容器由 IntersectionObserver 滚动时触发。
 */
export function reEnhanceAllSandboxPathLinks(): void {
  const containers = document.querySelectorAll('.msg-md:not(.streaming)')
  for (const el of containers) {
    if (!(el instanceof HTMLElement)) continue
    // 清除纯文本容器的已扫描标记，允许重新扫描
    el.querySelectorAll('[data-sandbox-text-scanned]').forEach((host) => {
      delete (host as HTMLElement).dataset.sandboxTextScanned
    })
    // 删除 inline code 的已增强标记，使索引就绪后能重新匹配
    el.querySelectorAll('code:not(pre code)').forEach((code) => {
      delete (code as HTMLElement).dataset.sandboxPathEnhanced
    })
    // 恢复 <a> 标签的 href，清除增强标记，使索引就绪后能重新匹配
    el.querySelectorAll('a[data-sandbox-path-enhanced]').forEach((a) => {
      const anchor = a as HTMLAnchorElement
      const raw = anchor.getAttribute('data-sandbox-path-raw')
      if (raw) anchor.setAttribute('href', raw)
      anchor.removeAttribute('data-sandbox-path')
      anchor.removeAttribute('data-sandbox-path-raw')
      anchor.classList.remove('smd-sandbox-path')
      anchor.removeAttribute('title')
      delete anchor.dataset.sandboxPathEnhanced
    })
  }
  // 先立即增强当前视口内可见的容器
  enhanceVisibleSandboxPathLinks()
  // 注册 observer，滚动到视口时再增强其余容器
  ensureSandboxPathObserver()
}

/** 视口内可见容器立即增强路径链接 */
function enhanceVisibleSandboxPathLinks(): void {
  const containers = document.querySelectorAll('.msg-md:not(.streaming)')
  const vh = window.innerHeight
  for (const el of containers) {
    if (!(el instanceof HTMLElement)) continue
    const rect = el.getBoundingClientRect()
    // 容器与视口有交集（含一定预加载缓冲）
    if (rect.bottom > -200 && rect.top < vh + 200) {
      const basePath = (el as HTMLElement).dataset.smdBasePath ?? ''
      enhanceSandboxPathLinks(el, basePath)
      el.dataset.smdPathEnhanced = '1'
    }
  }
}

let sandboxPathObserver: IntersectionObserver | null = null

/**
 * 全局 IntersectionObserver：消息容器进入视口时才增强路径链接。
 * 避免对长对话历史全量扫描 DOM，提升滚动性能。
 * basePath 用于文件预览场景，持久化到容器 data-smd-base-path。
 */
function ensureSandboxPathObserver(basePath = ''): void {
  if (sandboxPathObserver) {
    sandboxPathObserver.disconnect()
  } else if (typeof IntersectionObserver !== 'undefined') {
    sandboxPathObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const el = entry.target as HTMLElement
            const bp = el.dataset.smdBasePath ?? ''
            enhanceSandboxPathLinks(el, bp)
            el.dataset.smdPathEnhanced = '1'
            sandboxPathObserver?.unobserve(el)
          }
        }
      },
      { rootMargin: '200px 0px' },
    )
  }
  if (!sandboxPathObserver) {
    // 降级：不支持 IntersectionObserver 时全量增强
    document.querySelectorAll('.msg-md:not(.streaming):not([data-smd-path-enhanced])').forEach((el) => {
      if (el instanceof HTMLElement) {
        const bp = el.dataset.smdBasePath ?? ''
        enhanceSandboxPathLinks(el, bp)
      }
    })
    return
  }
  document.querySelectorAll('.msg-md:not(.streaming):not([data-smd-path-enhanced])').forEach((el) => {
    if (el instanceof HTMLElement) {
      if (basePath && !el.dataset.smdBasePath) el.dataset.smdBasePath = basePath
      sandboxPathObserver!.observe(el)
    }
  })
}
