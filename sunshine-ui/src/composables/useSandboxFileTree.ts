import { h, ref } from 'vue'
import { NIcon, type TreeDragInfo, type TreeOption } from 'naive-ui'
import {
  DocumentTextOutline,
  FolderOpenOutline,
  FolderOutline,
} from '@vicons/ionicons5'
import { listSandboxWorkspace, type SandboxFsNode } from '../api/sandboxWorkspace'
import { setSandboxPathDrag } from '../utils/sandboxPathChip'
import { formatFileSize } from '../utils/buildFileTree'

export interface UseSandboxFileTreeOptions {
  getConversationId: () => string
  onOpenFile: (path: string) => void | Promise<void>
}

export function useSandboxFileTree(options: UseSandboxFileTreeOptions) {
  const treeLoading = ref(false)
  const errorText = ref('')
  const treeData = ref<TreeOption[]>([])
  const expandedKeys = ref<string[]>([])
  const selectedKeys = ref<string[]>([])

  function dirIcon(expanded: boolean) {
    return () => h(NIcon, {
      component: expanded ? FolderOpenOutline : FolderOutline,
      size: 14,
      class: 'tree-icon-dir',
    })
  }

  function fileIcon() {
    return () => h(NIcon, {
      component: DocumentTextOutline,
      size: 14,
      class: 'tree-icon-file',
    })
  }

  function toOptions(entries: SandboxFsNode[]): TreeOption[] {
    const sorted = [...entries].sort((a, b) => {
      const ad = a.type === 'dir' ? 0 : 1
      const bd = b.type === 'dir' ? 0 : 1
      if (ad !== bd) return ad - bd
      return a.name.localeCompare(b.name)
    })
    return sorted.map((n) => {
      const isDir = n.type === 'dir'
      const size = typeof n.size === 'number' && n.size >= 0 ? n.size : null
      const opt: TreeOption = {
        key: n.path,
        label: n.name,
        isLeaf: !isDir,
        prefix: isDir ? dirIcon(false) : fileIcon(),
        suffix: !isDir && size != null
          ? () => h('span', { class: 'tree-size' }, formatFileSize(size))
          : undefined,
      }
      ;(opt as TreeOption & { path: string; isDir: boolean }).path = n.path
      ;(opt as TreeOption & { path: string; isDir: boolean }).isDir = isDir
      return opt
    })
  }

  async function fetchChildren(path: string): Promise<TreeOption[]> {
    const conversationId = options.getConversationId()
    if (!conversationId) return []
    const data = await listSandboxWorkspace(conversationId, path)
    return toOptions(data.entries ?? [])
  }

  async function loadRoots() {
    const conversationId = options.getConversationId()
    if (!conversationId) return
    treeLoading.value = true
    errorText.value = ''
    try {
      const [wsKids, skillKids] = await Promise.all([
        fetchChildren('/workspace').catch(() => [] as TreeOption[]),
        fetchChildren('/skills').catch(() => [] as TreeOption[]),
      ])
      treeData.value = [
        {
          key: '/workspace',
          label: 'workspace',
          isLeaf: false,
          path: '/workspace',
          isDir: true,
          children: wsKids,
          prefix: dirIcon(true),
        },
        {
          key: '/skills',
          label: 'skills',
          isLeaf: false,
          path: '/skills',
          isDir: true,
          children: skillKids,
          prefix: dirIcon(true),
        },
      ]
      expandedKeys.value = ['/workspace', '/skills']
    } catch (e) {
      errorText.value = e instanceof Error ? e.message : '加载失败'
      treeData.value = []
    } finally {
      treeLoading.value = false
    }
  }

  function findNode(nodes: TreeOption[], key: string): TreeOption | null {
    for (const n of nodes) {
      if (String(n.key) === key) return n
      if (n.children?.length) {
        const hit = findNode(n.children as TreeOption[], key)
        if (hit) return hit
      }
    }
    return null
  }

  /** 仅刷新指定根目录子树（保留另一根目录已加载内容） */
  async function reloadBranch(rootPath: '/workspace' | '/skills') {
    const conversationId = options.getConversationId()
    if (!conversationId) return
    if (!treeData.value.length) {
      await loadRoots()
      return
    }
    const root = findNode(treeData.value, rootPath)
    if (!root) {
      await loadRoots()
      return
    }
    root.children = await fetchChildren(rootPath)
    const expandedUnder = expandedKeys.value
      .filter((k) => k.startsWith(`${rootPath}/`))
      .sort((a, b) => a.length - b.length)
    for (const dir of expandedUnder) {
      const node = findNode(treeData.value, dir)
      if (node) node.children = await fetchChildren(dir)
    }
    treeData.value = [...treeData.value]
  }

  function treeNodeProps({ option }: { option: TreeOption }) {
    return {
      title: String((option as TreeOption & { path?: string }).path || option.key),
    }
  }

  function onTreeDragStart({ event, node }: TreeDragInfo) {
    const ext = node as TreeOption & { path?: string; isDir?: boolean }
    const path = (ext.path || String(node.key)).trim()
    if (!path.startsWith('/workspace') && !path.startsWith('/skills')) return
    if (!event.dataTransfer) return
    setSandboxPathDrag(event.dataTransfer, {
      path,
      name: String(node.label ?? path),
      isDir: !!ext.isDir || node.isLeaf === false,
    })
  }

  function denyTreeDrop() {
    return false
  }

  async function onLoad(option: TreeOption): Promise<void> {
    const path = String(option.key)
    try {
      option.children = await fetchChildren(path)
    } catch {
      option.children = []
    }
  }

  function onUpdateExpanded(keys: Array<string | number>) {
    expandedKeys.value = keys.map(String)
  }

  function onSelect(keys: Array<string | number>, option: Array<TreeOption | null>) {
    selectedKeys.value = keys.map(String)
    const opt = option[0]
    if (!opt) return
    const path = String(opt.key)
    const isDir = (opt as TreeOption & { isDir?: boolean }).isDir || !opt.isLeaf
    if (isDir) {
      const set = new Set(expandedKeys.value)
      if (set.has(path)) set.delete(path)
      else set.add(path)
      expandedKeys.value = [...set]
      return
    }
    void options.onOpenFile(path)
  }

  async function ensureExpanded(dirPath: string) {
    const find = (nodes: TreeOption[], key: string): TreeOption | null => {
      for (const n of nodes) {
        if (String(n.key) === key) return n
        if (n.children?.length) {
          const hit = find(n.children as TreeOption[], key)
          if (hit) return hit
        }
      }
      return null
    }
    const node = find(treeData.value, dirPath)
    if (!node) return
    if (!node.children || node.children.length === 0) {
      await onLoad(node)
      treeData.value = [...treeData.value]
    }
  }

  async function revealPath(focus: string) {
    if (!focus.startsWith('/workspace') && !focus.startsWith('/skills')) return
    const parts = focus.split('/').filter(Boolean)
    const ancestors: string[] = []
    let acc = ''
    for (let i = 0; i < parts.length - 1; i++) {
      acc += `/${parts[i]}`
      ancestors.push(acc)
    }
    for (const dir of ancestors) {
      await ensureExpanded(dir)
    }
    expandedKeys.value = [...new Set([...expandedKeys.value, ...ancestors])]
    const isLikelyFile = parts.length > 1 && (focus.includes('.') || !focus.endsWith('/'))
    if (isLikelyFile && focus !== '/workspace' && focus !== '/skills') {
      await options.onOpenFile(focus)
    }
  }

  function resetTree() {
    treeData.value = []
    expandedKeys.value = []
    selectedKeys.value = []
    errorText.value = ''
  }

  return {
    treeLoading,
    errorText,
    treeData,
    expandedKeys,
    selectedKeys,
    loadRoots,
    treeNodeProps,
    onTreeDragStart,
    denyTreeDrop,
    onLoad,
    onUpdateExpanded,
    onSelect,
    revealPath,
    resetTree,
    reloadBranch,
  }
}
