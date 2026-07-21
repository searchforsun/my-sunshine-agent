import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  extractSandboxExecCommand,
  extractSandboxSearchRoot,
  hasExpandableContent,
  inferSandboxSearchRoot,
  isSandboxExecStep,
  isSandboxToolStep,
  parseSandboxPathList,
  resolveSandboxFocusPath,
  resolveStepExpandInner,
  resolveStepHeaderText,
  shouldShiftSummaryOnExpand,
} from './processingStepsDisplay'

function sandboxStep(partial: Partial<ProcessingStep> & { id: string }): ProcessingStep {
  return {
    phase: 'tool',
    lifecycle: 'done',
    label: '调用工具 读文件',
    summary: { after: 'hello.py' },
    metadata: { sandboxPath: '/skills/demo/scripts/hello.py' },
    detail: 'print("hi")\n',
    ...partial,
  }
}

describe('sandbox tool timeline display', () => {
  it('detects sandbox__* tool step ids by prefix', () => {
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sandbox__read@123' }))).toBe(true)
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sandbox__exec@9' }))).toBe(true)
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sandbox__future@1' }))).toBe(true)
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sdk__finance__list@1' }))).toBe(false)
  })

  it('cancelled exec: header trusts after; expand command from detail (lifecycle, not 已取消 text)', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@9',
      label: '执行命令',
      lifecycle: 'paused',
      summary: { before: '准备执行命令', active: '正在执行 sleep 120', after: '用户取消' },
      detail: 'sleep 120',
      metadata: { cancellable: true },
    })
    expect(resolveStepHeaderText(step)).toBe('用户取消')
    expect(extractSandboxExecCommand(step)).toBe('sleep 120')
    expect(hasExpandableContent(step)).toBe(true)
    expect(resolveStepExpandInner(step)).toBe('')
  })

  it('paused without after: header empty (no legacy active fallback)', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@10',
      label: '调用工具 执行命令',
      lifecycle: 'paused',
      summary: { active: '已取消' },
      detail: 'sleep 120',
      metadata: { cancellable: true },
    })
    expect(resolveStepHeaderText(step)).toBe('')
  })

  it('header shows backend summary as-is; focus uses metadata.sandboxPath', () => {
    const step = sandboxStep({ id: 'tool-sandbox__read@1' })
    expect(resolveStepHeaderText(step)).toBe('hello.py')
    expect(resolveSandboxFocusPath(step)).toBe('/skills/demo/scripts/hello.py')
    expect(shouldShiftSummaryOnExpand(step)).toBe(false)
    const longCmd =
      'python3 -c "import csv; total=0.0; ' + 'x'.repeat(80) + '"'
    const exec = sandboxStep({
      id: 'tool-sandbox__exec@2',
      label: '调用工具 执行命令',
      summary: { after: longCmd },
      metadata: {},
      detail: 'ok',
    })
    const header = resolveStepHeaderText(exec)
    expect(header.length).toBeLessThanOrEqual(43)
    expect(header.endsWith('…')).toBe(true)
    expect(extractSandboxExecCommand(exec)).toBe(longCmd)
  })

  it('parses glob path list relative to search root', () => {
    const entries = parseSandboxPathList(
      '/skills/sandbox-coding-demo/SKILL.md\n/skills/sandbox-coding-demo/scripts/hello.py\n',
      '/skills',
    )
    expect(entries.map(e => e.name)).toEqual([
      'sandbox-coding-demo/SKILL.md',
      'sandbox-coding-demo/scripts/hello.py',
    ])
    expect(entries[0].path).toBe('/skills/sandbox-coding-demo/SKILL.md')
    const nested = parseSandboxPathList(
      '/skills/sandbox-coding-demo/scripts/hello.py\n',
      '/skills/sandbox-coding-demo',
    )
    expect(nested[0].name).toBe('scripts/hello.py')
    expect(inferSandboxSearchRoot(entries.map(e => e.path))).toBe('/skills')
  })

  it('glob header trusts backend after with search root', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__glob@1',
      label: '调用工具 查找文件',
      summary: { after: '**/* · /skills' },
      metadata: { sandboxSearchRoot: '/skills' },
      detail: '/skills/sandbox-coding-demo/SKILL.md\n/skills/sandbox-coding-demo/scripts/hello.py\n',
    })
    expect(resolveStepHeaderText(step)).toBe('**/* · /skills')
    expect(extractSandboxSearchRoot(step.summary?.after)).toBe('/skills')
  })

  it('grep header trusts backend pattern-only after', () => {
    const grep = sandboxStep({
      id: 'tool-sandbox__grep@1',
      label: '调用工具 搜索内容',
      summary: { after: 'hello' },
      metadata: {},
      detail: 'sandbox-coding-demo/scripts/hello.py:8: print("hello")\n',
    })
    expect(resolveStepHeaderText(grep)).toBe('hello')
  })

  it('returns raw expand body without code fence', () => {
    const step = sandboxStep({ id: 'tool-sandbox__read@1', detail: 'line1\nline2' })
    expect(resolveStepExpandInner(step)).toBe('line1\nline2')
  })

  it('extracts exec command and keeps raw output for expand panel', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@1',
      label: '调用工具 执行命令',
      summary: { after: 'ls -la /skills' },
      metadata: {},
      detail: 'total 0\ndrwxr-xr-x 1 root root 0 Jul 16 03:32 .\n',
    })
    expect(isSandboxExecStep(step)).toBe(true)
    expect(extractSandboxExecCommand(step)).toBe('ls -la /skills')
    expect(resolveStepExpandInner(step)).toBe('total 0\ndrwxr-xr-x 1 root root 0 Jul 16 03:32 .')
  })

  it('edit step with metadata.editDiff is expandable even without detail', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__edit@1',
      label: '调用工具 编辑文件',
      summary: { after: 'hello.py +1 -1' },
      detail: '',
      metadata: {
        sandboxPath: '/skills/demo/scripts/hello.py',
        editDiff: {
          path: '/skills/demo/scripts/hello.py',
          contextRadius: 3,
          lines: [
            { kind: 'del', text: 'old', oldLine: 2, newLine: null },
            { kind: 'add', text: 'new', oldLine: null, newLine: 2 },
          ],
        },
      },
    })
    expect(resolveStepExpandInner(step)).toBe('')
    expect(hasExpandableContent(step)).toBe(true)
  })

  it('HITL awaiting edit with metadata.editDiff is expandable', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__edit@2',
      label: '调用工具 编辑文件',
      lifecycle: 'running',
      summary: { active: '待确认编辑 hello.py' },
      detail: '',
      metadata: {
        hitlStatus: 'awaiting',
        hitlToken: 'tok-edit-1',
        sandboxPath: '/skills/demo/scripts/hello.py',
        editDiff: {
          path: '/skills/demo/scripts/hello.py',
          lines: [{ kind: 'add', text: 'pending', oldLine: null, newLine: 1 }],
        },
      },
    })
    expect(hasExpandableContent(step)).toBe(true)
  })
})
