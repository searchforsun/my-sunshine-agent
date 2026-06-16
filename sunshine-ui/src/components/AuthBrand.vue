<script setup lang="ts">
import BrandMark from './BrandMark.vue'
</script>

<template>
  <div class="auth-brand">
    <BrandMark :size="36" />
    <span class="auth-brand-name">Sunshine<span class="auth-brand-ai"> AI</span></span>
  </div>
</template>

<style scoped>
.auth-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  margin-bottom: var(--gap-lg);
}
.auth-brand-name {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.45px;
  color: var(--sun-text);
  line-height: 1;
}
.auth-brand-ai {
  font-weight: 400;
  color: var(--sun-text-muted);
}
</style>
