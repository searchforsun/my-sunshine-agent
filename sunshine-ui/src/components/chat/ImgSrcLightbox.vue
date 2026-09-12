<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 图片轻量预览（代替 n-image 全屏预览）：
 * - n-image 预览工具栏固定且无法挂自定义按钮，故自建全屏层。
 * - 缩略图交互与 n-image 对齐：点击开预览；预览层 Esc/点背景关闭。
 * - 工具栏：缩放（±/滚轮）、复制、下载（落浏览器下载）、关闭。
 */
const props = defineProps<{
  src: string
  rawUrl?: string
  width?: number
  height?: number
}>()

const visible = ref(false)
const copied = ref(false)
const scale = ref(1)
let copyTimer: number | undefined

const MIN_SCALE = 0.25
const MAX_SCALE = 8
const SCALE_STEP = 1.25

// 下载文件名取 URL 路径尾段（日期目录/对象名），无则回退固定名
const downloadName = computed(() => {
  const raw = props.rawUrl || props.src
  const tail = raw.split('/').filter(Boolean).pop()
  return tail || 'image.png'
})

function open() {
  scale.value = 1
  visible.value = true
}
function close() { visible.value = false }

function zoomIn() { scale.value = Math.min(MAX_SCALE, scale.value * SCALE_STEP) }
function zoomOut() { scale.value = Math.max(MIN_SCALE, scale.value / SCALE_STEP) }

function onWheel(e: WheelEvent) {
  if (!visible.value) return
  e.preventDefault()
  if (e.deltaY < 0) zoomIn()
  else zoomOut()
}

async function copyImage() {
  try {
    const res = await fetch(props.src)
    const blob = await res.blob()
    // PNG 才能进剪贴板 image 类型：jpeg 先转码，失败降级复制 URL
    const payload = blob.type === 'image/png' ? blob : await toPngBlob(blob)
    await navigator.clipboard.write([new ClipboardItem({ [payload.type]: payload })])
    copied.value = true
    if (copyTimer) window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => { copied.value = false }, 1600)
  } catch {
    await navigator.clipboard.writeText(props.src).catch(() => {})
  }
}

async function toPngBlob(blob: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(blob)
  const canvas = document.createElement('canvas')
  canvas.width = bitmap.width
  canvas.height = bitmap.height
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0)
  bitmap.close()
  return new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(b => (b ? resolve(b) : reject(new Error('toBlob failed'))), 'image/png'))
}

/** 下载：同源代理地址 fetch 为 blob 后走 a[download]，浏览器直接落下载文件 */
async function downloadImage() {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(await (await fetch(props.src)).blob())
  a.download = downloadName.value
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(a.href)
}

function onKeydown(e: KeyboardEvent) {
  if (!visible.value) return
  if (e.key === 'Escape') close()
  if (e.key === '=' || e.key === '+') zoomIn()
  if (e.key === '-') zoomOut()
  if (e.key === '0') { scale.value = 1 }
}

// 预览层挂载期间接管滚轮缩放，避免页面滚动干扰
watch(visible, v => {
  if (v) window.addEventListener('wheel', onWheel, { passive: false })
  else window.removeEventListener('wheel', onWheel)
})

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('wheel', onWheel)
  if (copyTimer) window.clearTimeout(copyTimer)
})
</script>

<template>
  <img
    class="img-src-lightbox-thumb"
    :src="src"
    :style="{ width: `${width ?? 56}px`, height: `${height ?? 56}px` }"
    alt=""
    @click="open"
  >
  <Teleport to="body">
    <div v-if="visible" class="img-src-lightbox-mask" @click.self="close">
      <div class="img-src-lightbox-toolbar">
        <button type="button" class="img-src-lightbox-btn" title="缩小" @click="zoomOut">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="3" y1="8" x2="13" y2="8" /></svg>
        </button>
        <span class="img-src-lightbox-scale" title="缩放比例（点此复位）" @click="scale = 1">{{ Math.round(scale * 100) }}%</span>
        <button type="button" class="img-src-lightbox-btn" title="放大" @click="zoomIn">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="8" y1="3" x2="8" y2="13" /><line x1="3" y1="8" x2="13" y2="8" /></svg>
        </button>
        <span class="img-src-lightbox-divider" />
        <button type="button" class="img-src-lightbox-btn" :title="copied ? '已复制' : '复制图片'" @click="copyImage">
          <svg v-if="copied" width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M13.5 4.5L6 12 2.5 8.5" /></svg>
          <svg v-else width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="8" height="8" rx="1.5" /><path d="M10.5 5.5V3.5A1.5 1.5 0 0 0 9 2H3.5A1.5 1.5 0 0 0 2 3.5V9a1.5 1.5 0 0 0 1.5 1.5h2" /></svg>
        </button>
        <button type="button" class="img-src-lightbox-btn" title="下载" @click="downloadImage">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 2v8" /><path d="M4.5 6.5L8 10l3.5-3.5" /><path d="M2.5 12.5h11" /></svg>
        </button>
        <button type="button" class="img-src-lightbox-btn" title="关闭" @click="close">
          <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><line x1="4" y1="4" x2="12" y2="12" /><line x1="12" y1="4" x2="4" y2="12" /></svg>
        </button>
      </div>
      <img
        class="img-src-lightbox-full"
        :src="src"
        :style="{ transform: `scale(${scale})` }"
        alt=""
        @click="close"
      >
    </div>
  </Teleport>
</template>

<style scoped>
.img-src-lightbox-thumb {
  border-radius: 6px;
  object-fit: cover;
  cursor: zoom-in;
  display: block;
}

.img-src-lightbox-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(0, 0, 0, 0.82);
  display: flex;
  align-items: center;
  justify-content: center;
}

.img-src-lightbox-full {
  max-width: 92vw;
  max-height: 92vh;
  object-fit: contain;
  border-radius: 4px;
  transition: transform 0.12s ease-out;
}

.img-src-lightbox-toolbar {
  position: fixed;
  top: 16px;
  right: 20px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border-radius: 8px;
  background: rgba(30, 30, 30, 0.9);
}

.img-src-lightbox-btn {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.85);
  cursor: pointer;
}

.img-src-lightbox-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.img-src-lightbox-scale {
  min-width: 44px;
  text-align: center;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
  cursor: pointer;
  user-select: none;
}

.img-src-lightbox-divider {
  width: 1px;
  height: 16px;
  margin: 0 4px;
  background: rgba(255, 255, 255, 0.2);
}
</style>
