<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import PlanDagGraph from './PlanDagGraph.vue'
import type { DagNodeView } from '../../utils/planGraph'

const props = defineProps<{
  nodes: DagNodeView[]
  selectedId?: string
  live?: boolean
  title?: string
  userQuery?: string
  loadingLabel?: string
}>()

const emit = defineEmits<{
  close: []
  select: [node: DagNodeView]
}>()

const viewportRef = ref<HTMLElement | null>(null)
const trackWrapRef = ref<HTMLElement | null>(null)

const scale = ref(1)
const panX = ref(0)
const panY = ref(0)
const dragging = ref(false)

const MIN_SCALE = 0.35
const MAX_SCALE = 2.5

let dragStartX = 0
let dragStartY = 0
let dragPanX = 0
let dragPanY = 0

const transformStyle = computed(() => ({
  transform: `translate3d(${Math.round(panX.value)}px, ${Math.round(panY.value)}px, 0)`,
}))

const scaleLabel = computed(() => `${Math.round(scale.value * 100)}%`)

function clampScale(v: number) {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, v))
}

function measureTrackEl(): HTMLElement | null {
  return trackWrapRef.value?.querySelector('.plan-dag-track') as HTMLElement | null
}

function centerView() {
  const vp = viewportRef.value
  const track = measureTrackEl()
  if (!vp || !track) return false
  const vpW = vp.clientWidth
  const vpH = vp.clientHeight
  if (vpW < 1 || vpH < 1) return false
  const tW = track.offsetWidth
  const tH = track.offsetHeight
  if (tW < 1 || tH < 1) return false
  scale.value = 1
  panX.value = (vpW - tW) / 2
  panY.value = (vpH - tH) / 2
  return true
}

function applyZoomAt(next: number, anchorX: number, anchorY: number) {
  const prev = scale.value
  const clamped = clampScale(next)
  if (prev === clamped) return
  const ux = (anchorX - panX.value) / prev
  const uy = (anchorY - panY.value) / prev
  panX.value = anchorX - ux * clamped
  panY.value = anchorY - uy * clamped
  scale.value = clamped
}

function scheduleCenterView() {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      centerView()
      bindAutoCenter()
    })
  })
}

let viewportObserver: ResizeObserver | null = null
let userAdjusted = false
let wheelFrame = 0
let wheelAnchorX = 0
let wheelAnchorY = 0

function bindAutoCenter() {
  viewportObserver?.disconnect()
  const vp = viewportRef.value
  if (!vp) return
  const tryCenter = () => {
    if (userAdjusted) return
    centerView()
  }
  viewportObserver = new ResizeObserver(tryCenter)
  viewportObserver.observe(vp)
}

function zoomBy(factor: number) {
  userAdjusted = true
  const vp = viewportRef.value
  const next = clampScale(scale.value * factor)
  if (!vp) {
    scale.value = next
    return
  }
  const rect = vp.getBoundingClientRect()
  applyZoomAt(next, rect.width / 2, rect.height / 2)
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  userAdjusted = true
  const vp = viewportRef.value
  if (!vp) return
  const rect = vp.getBoundingClientRect()
  wheelAnchorX = e.clientX - rect.left
  wheelAnchorY = e.clientY - rect.top
  const delta = -e.deltaY * 0.0012
  const next = clampScale(scale.value * (1 + delta))
  if (wheelFrame) cancelAnimationFrame(wheelFrame)
  wheelFrame = requestAnimationFrame(() => {
    wheelFrame = 0
    applyZoomAt(next, wheelAnchorX, wheelAnchorY)
  })
}

function onViewportPointerDown(e: PointerEvent) {
  const target = e.target as HTMLElement
  if (target.closest('.plan-dag-node') || target.closest('.plan-dag-toolbar')) return
  userAdjusted = true
  dragging.value = true
  dragStartX = e.clientX
  dragStartY = e.clientY
  dragPanX = panX.value
  dragPanY = panY.value
  viewportRef.value?.setPointerCapture(e.pointerId)
}

function onViewportPointerMove(e: PointerEvent) {
  if (!dragging.value) return
  panX.value = dragPanX + (e.clientX - dragStartX)
  panY.value = dragPanY + (e.clientY - dragStartY)
}

function onViewportPointerUp(e: PointerEvent) {
  if (!dragging.value) return
  dragging.value = false
  viewportRef.value?.releasePointerCapture(e.pointerId)
}

function resetView() {
  userAdjusted = false
  scheduleCenterView()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
  if (e.key === '0') resetView()
}

watch(
  () => props.nodes,
  () => {
    if (userAdjusted) return
    scheduleCenterView()
  },
  { deep: true },
)

onMounted(() => {
  userAdjusted = false
  scheduleCenterView()
  window.addEventListener('keydown', onKeydown)
  const vp = viewportRef.value
  vp?.addEventListener('wheel', onWheel, { passive: false })
})

onUnmounted(() => {
  if (wheelFrame) cancelAnimationFrame(wheelFrame)
  viewportObserver?.disconnect()
  window.removeEventListener('keydown', onKeydown)
  const vp = viewportRef.value
  vp?.removeEventListener('wheel', onWheel)
})
</script>

<template>
  <div class="plan-dag-expand-layer" role="dialog" aria-modal="true" aria-label="执行计划放大视图">
    <header class="plan-dag-toolbar">
      <h3 class="toolbar-title">{{ title || '执行计划' }}</h3>
      <p v-if="userQuery" class="toolbar-user-query" :title="userQuery">{{ userQuery }}</p>
      <div class="toolbar-actions">
        <button type="button" class="dag-toolbar-btn" title="缩小" aria-label="缩小" @click="zoomBy(1 / 1.15)">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
        </button>
        <span class="toolbar-scale">{{ scaleLabel }}</span>
        <button type="button" class="dag-toolbar-btn" title="放大" aria-label="放大" @click="zoomBy(1.15)">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
        </button>
        <button type="button" class="dag-toolbar-btn" title="复原" aria-label="复原" @click="resetView">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7"/><polyline points="3 3 3 9 9 9"/></svg>
        </button>
        <button type="button" class="dag-toolbar-btn" title="退出放大" aria-label="退出放大" @click="emit('close')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
        </button>
      </div>
    </header>
    <div
      ref="viewportRef"
      class="plan-dag-viewport"
      :class="{ 'is-dragging': dragging, 'is-loading': loadingLabel }"
      @pointerdown="onViewportPointerDown"
      @pointermove="onViewportPointerMove"
      @pointerup="onViewportPointerUp"
      @pointercancel="onViewportPointerUp"
    >
      <div v-if="loadingLabel" class="plan-dag-viewport-overlay" role="status" aria-live="polite">
        <span class="plan-dag-spinner" aria-hidden="true" />
        <span>{{ loadingLabel }}</span>
      </div>
      <div ref="trackWrapRef" class="plan-dag-canvas" :style="transformStyle">
        <PlanDagGraph
          :nodes="nodes"
          :selected-id="selectedId"
          :live="live"
          :zoom="scale"
          fluid
          @select="emit('select', $event)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.plan-dag-expand-layer {
  position: absolute;
  inset: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--sun-black);
  isolation: isolate;
}

.plan-dag-toolbar {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.toolbar-title {
  margin: 0;
  font-size: var(--sun-font-md);
  font-weight: 600;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.toolbar-user-query {
  margin: 0;
  min-width: 0;
  padding: 0 8px;
  font-size: var(--sun-font-base);
  font-weight: 450;
  line-height: 1.4;
  color: var(--sun-text-secondary);
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.dag-toolbar-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-family: inherit;
  line-height: 0;
  transition: background 0.15s, color 0.15s;
}

.dag-toolbar-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.dag-toolbar-btn svg {
  flex-shrink: 0;
  display: block;
}

.toolbar-scale {
  min-width: 42px;
  text-align: center;
  font-size: 11px;
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
  padding: 0 4px;
}

.plan-dag-viewport {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  cursor: grab;
  touch-action: none;
  background-color: var(--sun-black);
  background-image:
    radial-gradient(circle at 1px 1px, color-mix(in srgb, var(--sun-border) 55%, transparent) 1px, transparent 0);
  background-size: 16px 16px;
  contain: paint;
}

.plan-dag-viewport-overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--sun-black);
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  pointer-events: auto;
}

.plan-dag-viewport.is-loading {
  pointer-events: none;
}

.plan-dag-viewport-overlay .plan-dag-spinner {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border: 2px solid color-mix(in srgb, var(--sun-text-muted) 28%, transparent);
  border-top-color: var(--sun-text-muted);
  border-radius: 50%;
  animation: plan-dag-spin 0.75s linear infinite;
}

@keyframes plan-dag-spin {
  to { transform: rotate(360deg); }
}

.plan-dag-viewport.is-dragging {
  cursor: grabbing;
}

.plan-dag-canvas {
  display: inline-block;
  position: relative;
  backface-visibility: hidden;
}

.plan-dag-canvas :deep(.plan-dag) {
  margin: 0;
  min-height: 120px;
  border: none;
  background: var(--sun-black);
  overflow: visible;
}
</style>
