<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NCard, NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/authStore'
import { useTheme } from '../composables/useTheme'
import AuthBrand from '../components/AuthBrand.vue'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()
const { theme, toggle: toggleTheme } = useTheme()
const isDark = computed(() => theme.value === 'dark')

const username = ref('')
const password = ref('')
const nickname = ref('')
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    await auth.register(username.value.trim(), password.value, nickname.value.trim() || undefined)
    message.success('注册成功，请登录')
    await router.replace('/login')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '注册失败')
  } finally {
    loading.value = false
  }
}

function goLogin() {
  void router.push('/login')
}
</script>

<template>
  <div class="auth-page">
    <button
      type="button"
      class="auth-theme-toggle"
      :title="isDark ? '切换浅色模式' : '切换深色模式'"
      @click="toggleTheme"
    >
      <svg v-if="isDark" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <circle cx="12" cy="12" r="5" />
        <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
      </svg>
      <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
      </svg>
    </button>

    <div class="auth-shell animate-in">
      <AuthBrand />
      <NCard title="注册账号" class="auth-card">
        <NForm @submit.prevent="onSubmit">
          <NFormItem label="用户名">
            <NInput v-model:value="username" placeholder="4-32 位字母数字下划线" autocomplete="username" />
          </NFormItem>
          <NFormItem label="昵称（可选）">
            <NInput v-model:value="nickname" placeholder="展示名称" />
          </NFormItem>
          <NFormItem label="密码">
            <NInput
              v-model:value="password"
              type="password"
              show-password-on="click"
              placeholder="8-64 位"
              autocomplete="new-password"
            />
          </NFormItem>
          <NButton type="primary" block :loading="loading" attr-type="submit">注册</NButton>
          <NButton quaternary block class="auth-form-secondary cursor-pointer" @click="goLogin">返回登录</NButton>
        </NForm>
      </NCard>
    </div>
  </div>
</template>
