import { test, expect } from '@playwright/test'

const FULL = `块级公式：

$$\\int_a^b f(x)dx$$

### 图片

![示例](https://example.com/a.png)

### 链接

[GitHub](https://github.com)

测试结束。`

test.describe('块级公式流式不分裂', () => {
  test('流式过程中不出现分离的 (x)dx 正文', async ({ page }) => {
    await page.goto('/chat')

    const result = await page.evaluate(async (fullText) => {
      const { runStreamMathIncrementalTest } = await import(
        '/src/utils/stream-markdown/streamTestHarness.ts'
      )
      return runStreamMathIncrementalTest(fullText)
    }, FULL)

    expect(result.leaked).toBe(false)
    expect(result.katexBlocks).toBe(1)
  })
})
