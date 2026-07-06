/** 文档原始内容类型 — 与 rag-service DocumentSourceType 对齐 */
export type DocSourceType = 'markdown' | 'text' | 'pdf' | 'docx'

export interface DocSourceTypeOption {
  value: DocSourceType
  label: string
  description: string
  accept: string
  uploadHint: string
  placeholder: string
  inlineEditable: boolean
  markdownPreview: boolean
}

export const DOC_SOURCE_TYPE_OPTIONS: DocSourceTypeOption[] = [
  {
    value: 'markdown',
    label: 'Markdown',
    description: '在线编辑或上传 .md',
    accept: '.md,.markdown',
    uploadHint: '支持 .md / .markdown',
    placeholder: '请上传 Markdown 文件（.md）或直接编写内容。',
    inlineEditable: true,
    markdownPreview: true,
  },
  {
    value: 'text',
    label: '纯文本',
    description: '在线编辑或上传 .txt',
    accept: '.txt',
    uploadHint: '支持 .txt',
    placeholder: '请上传纯文本（.txt）或直接编写内容。',
    inlineEditable: true,
    markdownPreview: false,
  },
  {
    value: 'pdf',
    label: 'PDF',
    description: '上传 PDF，自动 OCR 解析',
    accept: '.pdf',
    uploadHint: '支持 .pdf',
    placeholder: '请上传 PDF 文件，系统将 OCR 解析为 Markdown。',
    inlineEditable: false,
    markdownPreview: true,
  },
  {
    value: 'docx',
    label: 'Word',
    description: '上传 .docx，自动解析',
    accept: '.docx',
    uploadHint: '支持 .docx',
    placeholder: '请上传 Word（.docx）文件，系统将解析为 Markdown。',
    inlineEditable: false,
    markdownPreview: true,
  },
]

export function resolveDocSourceType(raw?: string | null): DocSourceTypeOption {
  const hit = DOC_SOURCE_TYPE_OPTIONS.find((o) => o.value === raw)
  return hit ?? DOC_SOURCE_TYPE_OPTIONS[0]
}

/** 异步解析中的占位正文 */
export const DOC_PARSING_PLACEHOLDER = '解析中，请稍候…'

export function isDocPlaceholder(content: string, sourceType: DocSourceTypeOption): boolean {
  const trimmed = content.trim()
  return !trimmed || trimmed === sourceType.placeholder || trimmed === DOC_PARSING_PLACEHOLDER
}
