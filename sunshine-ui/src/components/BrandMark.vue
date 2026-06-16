<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    class="brand-mark-svg"
    aria-hidden="true"
  >
    <rect
      width="32"
      height="32"
      rx="9"
      class="brand-mark-bg"
      stroke-width="1"
    />
    <path
      class="brand-mark-glow"
      d="M16 9.2c-3.6 0-6.7 1.2-8.2 2.9 2.3-1 4.7-1.4 8.2-1.4s5.9.4 8.2 1.4C22.7 10.4 19.6 9.2 16 9.2Z"
    />
    <circle cx="16" cy="13.5" r="3.8" class="brand-mark-core" />
    <g class="brand-mark-rays" stroke-width="2.2" stroke-linecap="round">
      <line x1="13" y1="18.5" x2="12" y2="25" />
      <line x1="16" y1="18" x2="16" y2="25.5" />
      <line x1="19" y1="18.5" x2="20" y2="25" />
    </g>
  </svg>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ size?: number | string }>(), { size: 28 })
</script>

<style scoped>
.brand-mark-bg {
  fill: var(--brand-mark-bg, #212121);
  stroke: var(--brand-mark-border, #424242);
}
.brand-mark-glow {
  fill: var(--brand-mark-fg, #ececec);
  fill-opacity: 0.22;
}
.brand-mark-core {
  fill: var(--brand-mark-fg, #ececec);
  fill-opacity: 0.96;
}
.brand-mark-rays {
  stroke: var(--brand-mark-fg, #ececec);
  stroke-opacity: 0.96;
}
</style>
