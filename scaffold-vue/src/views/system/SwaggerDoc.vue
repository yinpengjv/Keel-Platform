<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { systemApi } from '@/api'
import { toastError } from '@/composables/toast'

/* 接口文档承载页：先取一次性票据，再让 iframe 凭票据换会话 Cookie 打开 Swagger UI。
   权限 system:swagger:view 由后端票据接口与 SwaggerAccessFilter 双重校验。 */
const src = ref('')
const error = ref('')
const loading = ref(true)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { url } = await systemApi.users.swaggerTicket()
    src.value = url
  } catch (e) {
    error.value = '访问票据获取失败：请确认账号有 system:swagger:view 权限'
    toastError(e, '接口文档打开失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="m-surface panel reveal swagger-shell">
      <div v-if="error" class="swagger-error">
        <p class="swagger-error-text">{{ error }}</p>
        <button class="swagger-retry" type="button" @click="load">重试</button>
      </div>
      <div v-else-if="!src" class="swagger-hint">正在获取访问票据…</div>
      <iframe v-else class="swagger-frame" :src="src" title="接口文档" />
    </div>
  </div>
</template>

<style scoped>
.swagger-shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 132px);
  min-height: 420px;
  padding: 0;
  overflow: hidden;
}

.swagger-frame {
  flex: 1;
  width: 100%;
  border: 0;
  background: #fff;
}

.swagger-hint,
.swagger-error {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--ink-soft, #6b7280);
  font-size: 13px;
}

.swagger-error {
  flex-direction: column;
}

.swagger-error-text {
  margin: 0;
}

.swagger-retry {
  padding: 6px 18px;
  color: var(--ink, #111827);
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
}

.swagger-retry:hover {
  border-color: var(--accent, #185fa5);
}
</style>
