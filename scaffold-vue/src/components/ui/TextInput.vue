<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from '@/components/Icon.vue'

const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  placeholder?: string
  type?: 'text' | 'password' | 'number' | 'email' | 'tel'
  icon?: string
  invalid?: boolean
  disabled?: boolean
  readonly?: boolean
  clearable?: boolean
  /** 密码框右侧显示眼睛按钮，点击在明文/密文之间切换 */
  revealable?: boolean
  multiline?: boolean
  rows?: number
  maxlength?: number
  autocomplete?: string
}>(), {
  type: 'text',
  rows: 3,
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number | null): void
  (e: 'enter'): void
  (e: 'blur'): void
}>()

const el = ref<HTMLInputElement | HTMLTextAreaElement | null>(null)

/** 眼睛按钮控制的明文状态：仅在 type=password 且开启 revealable 时生效 */
const revealed = ref(false)
const inputType = computed(() => (props.type === 'password' && revealed.value ? 'text' : props.type))

function onInput(event: Event) {
  const raw = (event.target as HTMLInputElement).value
  if (props.type === 'number') emit('update:modelValue', raw === '' ? null : Number(raw))
  else emit('update:modelValue', raw)
}

function clear() {
  emit('update:modelValue', props.type === 'number' ? null : '')
  el.value?.focus()
}

defineExpose({ focus: () => el.value?.focus() })
</script>

<template>
  <span :class="['input-shell', invalid && 'is-invalid', disabled && 'is-disabled', multiline && 'is-multiline']">
    <Icon v-if="icon" :name="icon" :size="15" />
    <textarea
      v-if="multiline"
      ref="el"
      :value="modelValue ?? ''"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :rows="rows"
      :maxlength="maxlength"
      :aria-invalid="invalid || undefined"
      @input="onInput"
      @blur="emit('blur')"
    />
    <input
      v-else
      ref="el"
      :type="inputType"
      :value="modelValue ?? ''"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :maxlength="maxlength"
      :autocomplete="autocomplete"
      :aria-invalid="invalid || undefined"
      @input="onInput"
      @keydown.enter="emit('enter')"
      @blur="emit('blur')"
    />
    <button
      v-if="type === 'password' && revealable"
      type="button"
      class="input-reveal"
      :aria-pressed="revealed"
      :aria-label="revealed ? '隐藏密码' : '显示密码'"
      :title="revealed ? '隐藏密码' : '显示密码'"
      :disabled="disabled"
      @mousedown.prevent
      @click="revealed = !revealed"
    >
      <Icon :name="revealed ? 'eyeOff' : 'eye'" :size="15" />
    </button>
    <button
      v-if="clearable && modelValue !== '' && modelValue !== null && modelValue !== undefined && !disabled"
      type="button"
      class="input-clear"
      aria-label="清空"
      @click="clear"
    >
      <Icon name="x" :size="14" />
    </button>
  </span>
</template>
