// @vitest-environment happy-dom
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { ref, nextTick } from 'vue'
import { useChatScroll } from './useChatScroll'

/** 模拟 chat-scroll 容器：jsdom 无布局，手动维护 scrollHeight/clientHeight */
function makeScrollEl(clientHeight = 400) {
  const el = document.createElement('div')
  Object.defineProperty(el, 'clientHeight', { value: clientHeight, configurable: true })
  let contentHeight = 1000
  let top = 0
  Object.defineProperty(el, 'scrollHeight', { get: () => contentHeight, configurable: true })
  Object.defineProperty(el, 'scrollTop', {
    get: () => top,
    set: (v: number) => {
      top = v
      // 与浏览器一致：程序化写 scrollTop 会派发 scroll 事件
      el.dispatchEvent(new Event('scroll'))
    },
    configurable: true,
  })
  return {
    el,
    grow(delta: number) { contentHeight += delta },
  }
}

describe('useChatScroll 流式贴底跟随', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('贴底状态下：连续流式增长（长表格）应持续跟随到底', async () => {
    const { el, grow } = makeScrollEl()
    const { scrollRef, onChatScroll, scrollToBottom } = useChatScroll(ref(true))
    scrollRef.value = el
    // 初始贴底（真实环境会伴随 scroll 事件，由 setter 自动派发）
    el.scrollTop = el.scrollHeight - el.clientHeight
    onChatScroll()

    // 多轮流式增长：先增长（浏览器异步布局，scrollHeight 变化不触发 scroll 事件），
    // 再同步内容高度（对应 Vue nextTick 后模板 patch 完成，watcher 触发跟随），
    // rAF 内程序化贴底；长表格场景反复执行必须始终贴底。
    for (const delta of [500, 800, 300, 1200, 100]) {
      grow(delta)
      scrollToBottom(false)
      await vi.advanceTimersByTimeAsync(32)
      expect(el.scrollTop).toBe(el.scrollHeight - el.clientHeight)
    }
  })

  it('贴底状态下：跟随写底后再变高、scroll 事件带旧 scrollHeight 晚到，不应误判上滑', async () => {
    const { el, grow } = makeScrollEl()
    const { scrollRef, onChatScroll, scrollToBottom, chatScrollPinned } = useChatScroll(ref(true))
    scrollRef.value = el
    el.scrollTop = el.scrollHeight - el.clientHeight
    onChatScroll()

    // 流式增长一轮：rAF 跟随写底（setter 派发 scroll，syncScrollPinned 已同步 lastScrollTop）
    grow(500)
    scrollToBottom(false)
    await vi.advanceTimersByTimeAsync(32)
    expect(el.scrollTop).toBe(el.scrollHeight - el.clientHeight)

    // 模拟浏览器：跟随写底后内容继续变高（表格行逐条渲染），
    // 浏览器按渲染帧补发 scroll 事件；中间帧 scrollHeight 尚未 commit（dist≤1），
    // 末帧读到新 scrollHeight（top < lastScrollTop 且 dist>1）——整条链都不应误判上滑
    grow(300)
    onChatScroll()
    onChatScroll()
    expect(chatScrollPinned.value).toBe(true)

    // 后续增长必须继续跟随
    grow(400)
    scrollToBottom(false)
    await vi.advanceTimersByTimeAsync(32)
    expect(el.scrollTop).toBe(el.scrollHeight - el.clientHeight)
  })

  it('用户真实上滑（scrollTop 减小）仍应取消贴底', async () => {
    const { el, grow } = makeScrollEl()
    const { scrollRef, onChatScroll, scrollToBottom, chatScrollPinned } = useChatScroll(ref(true))
    scrollRef.value = el
    el.scrollTop = el.scrollHeight - el.clientHeight

    grow(500)
    scrollToBottom(false)
    await vi.advanceTimersByTimeAsync(32)
    onChatScroll()

    // 用户向上拖动滚动条（suppress 窗口内上滑 dist≤1 帧保持 pinned，窗口结束后再上滑）
    await vi.advanceTimersByTimeAsync(32)
    el.scrollTop -= 200
    el.dispatchEvent(new Event('scroll'))
    onChatScroll()
    expect(chatScrollPinned.value).toBe(false)

    grow(500)
    scrollToBottom(false)
    await vi.advanceTimersByTimeAsync(32)
    // 用户已接管：不再跟随
    expect(el.scrollTop).toBeLessThan(el.scrollHeight - el.clientHeight)
  })
})
