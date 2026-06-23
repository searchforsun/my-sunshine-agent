/** 无依赖 store-only ZIP 打包 — 供文件夹上传使用 */

function crc32(buf: Uint8Array): number {
  let crc = 0xffffffff
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i]
    for (let j = 0; j < 8; j++) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0)
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

function u16(n: number): number[] {
  return [n & 0xff, (n >>> 8) & 0xff]
}

function u32(n: number): number[] {
  return [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff]
}

function concat(chunks: Uint8Array[]): Uint8Array {
  const total = chunks.reduce((s, c) => s + c.length, 0)
  const out = new Uint8Array(total)
  let off = 0
  for (const c of chunks) {
    out.set(c, off)
    off += c.length
  }
  return out
}

export async function zipFiles(entries: { path: string; data: Uint8Array }[]): Promise<Blob> {
  const parts: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  const dosTime = 0
  const dosDate = 0

  for (const entry of entries) {
    const name = new TextEncoder().encode(entry.path.replace(/\\/g, '/'))
    const data = entry.data
    const crc = crc32(data)
    const local = new Uint8Array([
      0x50, 0x4b, 0x03, 0x04, // local header
      20, 0, // version
      0, 0, // flags
      0, 0, // compression store
      ...u16(dosTime),
      ...u16(dosDate),
      ...u32(crc),
      ...u32(data.length),
      ...u32(data.length),
      ...u16(name.length),
      0, 0, // extra len
      ...name,
    ])
    parts.push(local, data)

    const cd = new Uint8Array([
      0x50, 0x4b, 0x01, 0x02,
      20, 0, 20, 0,
      0, 0, 0, 0,
      ...u16(dosTime),
      ...u16(dosDate),
      ...u32(crc),
      ...u32(data.length),
      ...u32(data.length),
      ...u16(name.length),
      0, 0, 0, 0, 0, 0,
      0, 0, 0, 0,
      ...u32(offset),
      ...name,
    ])
    central.push(cd)
    offset += local.length + data.length
  }

  const centralDir = concat(central)
  const end = new Uint8Array([
    0x50, 0x4b, 0x05, 0x06,
    0, 0, 0, 0,
    ...u16(entries.length),
    ...u16(entries.length),
    ...u32(centralDir.length),
    ...u32(offset),
    0, 0,
  ])
  return new Blob([concat([...parts, centralDir, end])], { type: 'application/zip' })
}

export async function zipFolderFiles(files: FileList): Promise<Blob> {
  const rawEntries: { path: string; data: Uint8Array }[] = []
  for (const file of Array.from(files)) {
    const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
    rawEntries.push({ path: rel.replace(/\\/g, '/'), data: new Uint8Array(await file.arrayBuffer()) })
  }
  const entries = stripCommonRootPrefix(rawEntries)
  return zipFiles(entries)
}

/** 去掉 webkitdirectory 选中的顶层文件夹前缀，如 compliance-check/SKILL.md → SKILL.md */
function stripCommonRootPrefix(entries: { path: string; data: Uint8Array }[]): { path: string; data: Uint8Array }[] {
  if (entries.length === 0) return entries
  const firstSlash = entries[0].path.indexOf('/')
  if (firstSlash <= 0) return entries
  const root = entries[0].path.slice(0, firstSlash + 1)
  if (!entries.every(e => e.path.startsWith(root))) return entries
  return entries.map(e => ({ path: e.path.slice(root.length), data: e.data }))
}
