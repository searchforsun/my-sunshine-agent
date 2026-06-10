/**
 * 流式缓冲区管理器
 * 核心职责：解决 SSE 分块传输导致的语法单元不完整问题
 *   - 只输出完整行（以 \n 结尾），不完整行保留在缓冲区
 *   - 防止缓冲区无限增长（超过 MAX_BUFFER_SIZE 强制刷新）
 *   - 流结束时强制刷新所有剩余内容
 */
export class StreamBuffer {
  private buffer = ''
  private readonly MIN_FLUSH = 100
  private readonly MAX_BUFFER = 1024 * 20 // 20KB

  /** 追加数据块，返回完整行 */
  append(chunk: string): string {
    this.buffer += chunk
    return this.flushCompleteLines()
  }

  /** 强制刷新所有剩余内容 */
  forceFlush(): string {
    const remaining = this.buffer
    this.buffer = ''
    return remaining
  }

  /** 刷新当前缓冲区中的不完整行（用于平滑流式） */
  flushPartial(): string {
    const partial = this.buffer
    this.buffer = ''
    return partial
  }

  /** 只刷新完整行，保留不完整行 */
  private flushCompleteLines(): string {
    // 找到最后一个完整行的边界
    const lastNL = this.buffer.lastIndexOf('\n')
    if (lastNL < 0) {
      // 没有任何完整行
      if (this.buffer.length > this.MAX_BUFFER) {
        // 缓冲区溢出保护
        const overflow = this.buffer.slice(0, this.buffer.length - this.MIN_FLUSH)
        this.buffer = this.buffer.slice(this.buffer.length - this.MIN_FLUSH)
        return overflow
      }
      return ''
    }

    const complete = this.buffer.substring(0, lastNL + 1)
    this.buffer = this.buffer.substring(lastNL + 1)

    // 缓冲区溢出保护
    if (this.buffer.length > this.MAX_BUFFER) {
      const overflow = this.buffer.slice(0, this.buffer.length - this.MIN_FLUSH)
      this.buffer = this.buffer.slice(this.buffer.length - this.MIN_FLUSH)
      return complete + overflow
    }

    return complete
  }

  /** 查看缓冲区中未完成的内容（不消费） */
  peek(): string {
    return this.buffer
  }

  /** 缓冲区中是否有未处理的数据 */
  get hasPending(): boolean {
    return this.buffer.length > 0
  }

  /** 重置缓冲区 */
  reset(): void {
    this.buffer = ''
  }
}
