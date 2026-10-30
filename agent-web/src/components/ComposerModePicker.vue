<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { RunMode } from '../api'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ modelValue: RunMode; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [mode: RunMode] }>()
const root = ref<HTMLElement | null>(null), open = ref(false)
const modes: Array<{ value: RunMode; label: string; icon: string; description: string }> = [
  { value: 'chat', label: 'Chat', icon: 'chat', description: '轻量问答，可搜索网页、查询资料' },
  { value: 'plan', label: 'Plan', icon: 'check', description: '查阅资料、制定计划，不执行修改' },
  { value: 'build', label: 'Build', icon: 'spark', description: '按目标或已有计划执行任务' },
]
function choose(value: RunMode) { if (!props.disabled) emit('update:modelValue', value); open.value = false }
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) open.value = false }
watch(() => props.disabled, disabled => { if (disabled) open.value = false })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="mode-picker" @keydown.esc.stop="open = false">
    <button class="mode-toggle" :disabled="disabled" aria-label="选择对话模式" aria-haspopup="menu"
      :aria-expanded="open" :title="disabled ? '当前任务模式已固定，结束后可切换' : modes.find(m => m.value === modelValue)?.description"
      @click="open = !open"><AppIcon :name="modes.find(m => m.value === modelValue)?.icon || 'chat'" :size="13" />
      {{ modes.find(m => m.value === modelValue)?.label }}<AppIcon name="chevron" :size="11" /></button>
    <div v-if="open" class="mode-menu" role="menu" aria-label="对话模式">
      <button v-for="item in modes" :key="item.value" role="menuitemradio"
        :aria-checked="modelValue === item.value" :class="{ selected: modelValue === item.value }"
        @click="choose(item.value)"><AppIcon :name="item.icon" :size="16" />
        <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        <span class="mode-check" aria-hidden="true">{{ modelValue === item.value ? '✓' : '' }}</span></button>
    </div>
  </div>
</template>

<style scoped>
.mode-picker{position:relative;flex-shrink:0}.mode-toggle{display:flex;align-items:center;gap:6px;border:0;border-radius:7px;background:var(--ink-1);color:var(--txt-2);padding:6px 8px;font:inherit;font-size:12px;cursor:pointer}
.mode-toggle:hover{background:var(--ink-3);color:var(--txt-1)}.mode-toggle:disabled{opacity:.65;cursor:default}
.mode-menu{position:absolute;bottom:calc(100% + 10px);left:0;z-index:20;width:285px;max-width:75vw;padding:6px;background:var(--ink-2);border:1px solid var(--line);border-radius:12px;box-shadow:var(--shadow-2)}
.mode-menu button{display:flex;align-items:center;gap:12px;width:100%;text-align:left;border:0;border-radius:8px;background:transparent;color:var(--txt-2);padding:12px 10px;cursor:pointer}
.mode-menu button:hover,.mode-menu button.selected{background:var(--ink-1);color:var(--txt-1)}.mode-menu strong{display:block;font-size:13px;font-weight:600}.mode-menu small{display:block;margin-top:4px;font-size:11px;color:var(--txt-3)}.mode-check{margin-left:auto;color:var(--green)}
</style>
