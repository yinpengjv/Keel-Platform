<script setup lang="ts">
import { useRoute } from 'vue-router'
import EmptyState from '@/components/ui/EmptyState.vue'

/* 菜单指向了未注册的组件标识时的占位页：
   不再静默丢菜单，而是明确告诉使用者缺什么。 */
const route = useRoute()
const component = String(route.meta.component ?? '')
</script>

<template>
  <div class="page">
    <div class="m-surface panel reveal" style="padding: 48px 20px">
      <EmptyState
        title="该菜单尚未绑定页面"
        :desc="component
          ? `组件标识「${component}」未在 src/data/pages.ts 注册，请先新增页面并登记后再访问。`
          : '菜单缺少组件标识，请在菜单管理中补全。'"
        icon="layers"
      />
    </div>
  </div>
</template>
