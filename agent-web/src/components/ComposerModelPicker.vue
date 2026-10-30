<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import { profileHost, selectedProfile, type ModelCatalog } from '../modelProfiles'
import AppIcon from './AppIcon.vue'
const emit = defineEmits<{ configure: [] }>()
const root = ref<HTMLElement | null>(null), catalog = ref<ModelCatalog | null>(null)
const open = ref(false), loading = ref(true), switching = ref(false), error = ref('')
const model = computed(() => catalog.value ? selectedProfile(catalog.value) : null)
let alive = true, request = 0
async function refresh() {
  const version = ++request
  try {
    const value = await api.get<ModelCatalog>('/api/model-settings')
    if (!alive || version !== request) return
    catalog.value = value; error.value = ''
  } catch (e) { if (alive && version === request) error.value = (e as Error).message }
  finally { if (alive && version === request) loading.value = false }
}
function configure() { open.value = false; emit('configure') }
async function select(id: string) {
  if (switching.value) return
  if (catalog.value?.selectedProfileId === id) { open.value = false; return }
  switching.value = true
  try {
    catalog.value = await api.put<ModelCatalog>('/api/model-settings/selection', { profileId: id })
    window.dispatchEvent(new Event('agent:model-settings-changed')); open.value = false
  } catch (e) { error.value = (e as Error).message }
  finally { switching.value = false }
}
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) open.value = false }
function changed() { void refresh() }
function identityChanged() { catalog.value = null; open.value = false; loading.value = true; void refresh() }
onMounted(() => {
  void refresh(); document.addEventListener('pointerdown', outside)
  window.addEventListener('agent:model-settings-changed', changed)
  window.addEventListener('agent:identity-changed', identityChanged)
})
onBeforeUnmount(() => {
  alive = false; request++; document.removeEventListener('pointerdown', outside)
  window.removeEventListener('agent:model-settings-changed', changed)
  window.removeEventListener('agent:identity-changed', identityChanged)
})
</script>

<template>
  <div ref="root" class="composer-model" @keydown.esc.stop="open = false">
    <button class="model-picker-toggle" type="button" aria-label="选择聊天模型" :aria-expanded="open" aria-haspopup="menu" :disabled="loading || switching" :title="model ? model.name + ' · ' + model.model : '选择模型'" @click="open = !open">
      <AppIcon name="spark" :size="14" /><span>{{ loading ? '加载模型…' : model?.id === 'default' ? model.model : model?.name || '选择模型' }}</span><AppIcon name="chevron" :size="11" class="model-chevron" :class="{ expanded: open }" />
    </button>
    <div v-if="open" class="model-picker-menu" role="menu" aria-label="聊天模型">
      <span class="menu-label">选择模型 · {{ catalog?.profiles.length ?? 0 }} 个已保存</span>
      <div class="model-options">
        <button v-if="catalog" role="menuitemradio" :aria-checked="catalog.selectedProfileId === 'default'" data-profile-id="default" type="button" :disabled="switching" @click="select('default')"><span><strong>系统默认</strong><small>{{ catalog.defaultProfile.model }}</small></span><AppIcon v-if="catalog.selectedProfileId === 'default'" name="check" :size="15" /></button>
        <button v-for="profile in catalog?.profiles" :key="profile.id" role="menuitemradio" :aria-checked="catalog?.selectedProfileId === profile.id" :data-profile-id="profile.id" type="button" :disabled="switching" @click="select(profile.id)"><span><strong>{{ profile.name }}</strong><small>{{ profile.model }} · {{ profileHost(profile) }}</small></span><AppIcon v-if="catalog?.selectedProfileId === profile.id" name="check" :size="15" /></button>
      </div>
      <button role="menuitem" type="button" class="configure-chat-model" @click="configure"><AppIcon name="settings" :size="15" /><span>管理模型…</span></button>
      <p v-if="error" role="alert" class="picker-error">{{ error }}</p><p class="menu-note">切换仅用于新任务，运行中的任务使用原配置</p>
    </div>
  </div>
</template>

<style scoped>
.composer-model { position:relative; min-width:0; }
.model-picker-toggle { display:flex; align-items:center; gap:7px; max-width:260px; padding:5px 8px; border:0; border-radius:7px; background:transparent; color:var(--txt-2); font:inherit; font-size:11px; cursor:pointer; }
.model-picker-toggle:hover,.model-picker-toggle[aria-expanded=true] { background:var(--ink-3); color:var(--txt-1); }
.model-picker-toggle>span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; } .model-picker-toggle:disabled { opacity:.6; cursor:wait; } .model-picker-toggle svg { flex-shrink:0; }
.model-chevron { transform:rotate(90deg); transition:transform .15s; } .model-chevron.expanded { transform:rotate(-90deg); }
.model-picker-menu { position:absolute; bottom:calc(100% + 10px); left:0; z-index:15; width:min(330px,calc(100vw - 70px)); padding:8px; border:1px solid var(--line); border-radius:12px; background:var(--ink-2); box-shadow:var(--shadow-2); color:var(--txt-1); }
.menu-label { display:block; font-size:10px; color:var(--txt-3); padding:7px 10px; } .model-options { max-height:280px; overflow:auto; padding-bottom:6px; margin-bottom:5px; border-bottom:1px solid var(--line); }
.model-picker-menu button { display:flex; align-items:center; gap:9px; width:100%; border:0; border-radius:7px; padding:10px; background:transparent; color:var(--txt-2); font:inherit; font-size:12px; text-align:left; cursor:pointer; }
.model-picker-menu button:hover { background:var(--ink-3); color:var(--txt-1); } .model-picker-menu button[aria-checked=true] { color:var(--green); background:var(--amber-soft); }
.model-options button>span { flex:1; min-width:0; } .model-options strong { display:block; font-size:12px; font-weight:500; overflow-wrap:anywhere; } .model-options small { display:block; margin-top:5px; font-size:10px; color:var(--txt-3); overflow-wrap:anywhere; } .model-options svg { flex-shrink:0; }
.menu-note { margin:8px 10px 4px; font-size:10px; color:var(--txt-3); line-height:1.6; } .picker-error { margin:8px 10px; font-size:11px; color:var(--red); overflow-wrap:anywhere; }
</style>
