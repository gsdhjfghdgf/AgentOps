<script setup lang="ts">
import { computed } from 'vue'
import { sessionStatus } from '../composables/sessionStatus'
const props = defineProps<{ status?: string }>()
const state = computed(() => sessionStatus(props.status))
</script>
<template>
  <span v-if="state" class="session-run-indicator" :class="state.kind" :data-run-state="status"
    role="status" :aria-label="state.label" :title="state.label">
    <svg v-if="state.spinning" class="session-spinner" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="8" cy="8" r="5.5" fill="none" stroke="currentColor" stroke-width="1.6" opacity=".2" />
      <path d="M8 2.5a5.5 5.5 0 0 1 5.5 5.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
    </svg>
    <span v-else class="session-state-dot" aria-hidden="true">{{ state.kind === 'paused' ? 'Ⅱ' : state.kind === 'stopping' ? '■' : '•' }}</span>
  </span>
</template>
<style scoped>
.session-run-indicator{display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;width:16px;height:16px;color:var(--txt-3)}
.session-spinner{animation:session-spin 1s linear infinite}.waiting{color:#a36e26}.stopping{color:#a05e57}.session-state-dot{font-size:20px;line-height:1}.stopping .session-state-dot{font-size:9px}
.paused{color:var(--txt-3)}.paused .session-state-dot{font-size:12px;font-weight:600}
@keyframes session-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.session-spinner{animation:none}}
</style>
