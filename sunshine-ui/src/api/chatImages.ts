import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'

const MAX_EDGE = 2048
const JPEG_QUALITY = 0.85
const MAX_BYTES = 5 * 1024 * 1024

/** 上传前压缩：最长边 >2048 等比缩小 + JPEG 0.85；PNG 透明图与已达标图片原样保留 */
async function compressImage(file: File): Promise<File> {
  const keepPng = file.type === 'image/png' && await hasAlpha(file)
  if (file.size <= MAX_BYTES && keepPng) return file
  if (file.type === 'image/gif') return file // 动图不做 canvas 压缩
  const bitmap = await createImageBitmap(file)
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bitmap.width * scale)
  canvas.height = Math.round(bitmap.height * scale)
  const ctx = canvas.getContext('2d')!
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  const blob = await new Promise<Blob | null>(r =>
    canvas.toBlob(r, keepPng ? 'image/png' : 'image/jpeg', JPEG_QUALITY))
  bitmap.close()
  if (!blob || blob.size >= file.size) return file
  return new File([blob], file.name.replace(/\.\w+$/, keepPng ? '.png' : '.jpg'), {
    type: keepPng ? 'image/png' : 'image/jpeg',
  })
}

async function hasAlpha(file: File): Promise<boolean> {
  const bitmap = await createImageBitmap(file)
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 1
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(bitmap, 0, 0, 1, 1)
  bitmap.close()
  return ctx.getImageData(0, 0, 1, 1).data[3] < 255
}

/** 聊天图片上传：压缩后 multipart 直连 Gateway（同技能包上传路径），返回公网 URL */
export async function uploadChatImage(file: File): Promise<string> {
  const compressed = await compressImage(file)
  const form = new FormData()
  form.append('file', compressed)
  const res = await fetch(`${resolveBffStreamBase()}/api/chat/images`, {
    method: 'POST',
    headers: { ...apiHeaders() },
    body: form,
  })
  const body = await res.json()
  if (!res.ok || body?.code !== 200) throw new Error(body?.msg || '图片上传失败')
  return body.data.url as string
}
