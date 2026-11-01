<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from '@/components/Icon.vue'

/* 菜单打开方式为「嵌入网站」时的承载页：整页 iframe。
   地址不直接展示，刷新与新窗口入口收在右上角，平时半透明、悬停才明显。 */
const props = defineProps<{ url?: string }>()

const loading = ref(true)
const frameKey = ref(0)
const target = computed(() => props.url ?? '')

function reload() {
  loading.value = true
  frameKey.value += 1
}
</script>

<template>
  <div class="page">
    <div class="m-surface panel reveal iframe-shell">
      <div class="iframe-tools">
        <button class="iframe-tool" type="button" title="重新加载" @click="reload">
          <Icon name="refresh" :size="15" />
        </button>
        <a class="iframe-tool" :href="target" target="_blank" rel="noopener noreferrer" title="在新窗口打开">
          <Icon name="upload" :size="15" />
        </a>
      </div>
      <div class="iframe-body">
        <div v-if="loading" class="iframe-hint">正在载入…</div>
        <iframe
          :key="frameKey"
          class="iframe-el"
          :src="target"
          referrerpolicy="no-referrer"
          @load="loading = false"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.iframe-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 132px);
  min-height: 420px;
  padding: 0;
  overflow: hidden;
}

.iframe-tools {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  display: flex;
  gap: 4px;
  opacity: 0.2;
  transition: opacity 0.2s ease;
}

.iframe-shell:hover .iframe-tools,
.iframe-tools:focus-within {
  opacity: 0.95;
}

.iframe-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  color: var(--ink-soft, #6b7280);
  cursor: pointer;
  background: var(--m-surface-solid, rgba(255, 255, 255, 0.82));
  border: 1px solid var(--line, rgba(0, 0, 0, 0.08));
  border-radius: 7px;
  backdrop-filter: blur(6px);
}

.iframe-tool:hover {
  color: var(--ink, #111827);
}

.iframe-body {
  position: relative;
  flex: 1;
  min-height: 0;
}

.iframe-hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--ink-soft, #6b7280);
}

.iframe-el {
  width: 100%;
  height: 100%;
  border: 0;
}
</style>
