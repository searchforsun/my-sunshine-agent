/** 将 skill 文件列表构建为可展开的树形结构 */

export interface FileTreeNode {
  key: string
  label: string
  isDir: boolean
  size: number
  children: FileTreeNode[]
}

export interface FileEntryLike {
  path: string
  size: number
  directory: boolean
}

export function buildFileTree(entries: FileEntryLike[]): FileTreeNode[] {
  const root: FileTreeNode[] = []
  const map = new Map<string, FileTreeNode>()

  function ensureNode(fullPath: string, label: string, isDir: boolean, size = 0): FileTreeNode {
    let node = map.get(fullPath)
    if (!node) {
      node = { key: fullPath, label, isDir, size, children: [] }
      map.set(fullPath, node)
      const slash = fullPath.lastIndexOf('/')
      if (slash === -1) {
        root.push(node)
      } else {
        const parentPath = fullPath.slice(0, slash)
        const parentLabel = parentPath.slice(parentPath.lastIndexOf('/') + 1)
        const parent = ensureNode(parentPath, parentLabel, true)
        parent.children.push(node)
      }
    }
    if (!isDir) node.size = size
    return node
  }

  for (const entry of entries) {
    const parts = entry.path.split('/')
    let built = ''
    for (let i = 0; i < parts.length; i++) {
      built = i === 0 ? parts[i] : `${built}/${parts[i]}`
      const isLast = i === parts.length - 1
      ensureNode(built, parts[i], isLast ? entry.directory : true, isLast && !entry.directory ? entry.size : 0)
    }
  }

  function sortNodes(nodes: FileTreeNode[]) {
    nodes.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      return a.label.localeCompare(b.label)
    })
    nodes.forEach(n => sortNodes(n.children))
  }
  sortNodes(root)
  return root
}

export function collectDirKeys(nodes: FileTreeNode[]): string[] {
  const keys: string[] = []
  function walk(list: FileTreeNode[]) {
    for (const n of list) {
      if (n.isDir) {
        keys.push(n.key)
        walk(n.children)
      }
    }
  }
  walk(nodes)
  return keys
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
