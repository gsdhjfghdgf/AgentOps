<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { profileHost, type ModelCatalog, type ModelProfile } from '../modelProfiles'
import AppIcon from './AppIcon.vue'
const emit = defineEmits<{ close: []; saved: [] }>()
const catalog = ref<ModelCatalog | null>(null), editing = ref('new')
const name = ref(''), url = ref(''), model = ref(''), key = ref('')
const keyConfigured = ref(false), clearKey = ref(false)
const loading = ref(true), busy = ref(''), message = ref(''), failed = ref(false), confirmDelete = ref(false)
const saved = computed(() => catalog.value?.profiles.find(p => p.id === editing.value))
const ready = computed(() => url.value.trim() && model.value.trim()
  && (clearKey.value || key.value.trim() || keyConfigured.value))
const dirty = computed(() => !saved.value || name.value !== saved.value.name || url.value !== saved.value.url
  || model.value !== saved.value.model || !!key.value || clearKey.value !== !saved.value.keyConfigured)
function choose(profile?: ModelProfile) {
  editing.value = profile?.id ?? 'new'
  name.value = profile?.name ?? ''; url.value = profile?.url ?? ''; model.value = profile?.model ?? ''
  keyConfigured.value = profile?.keyConfigured ?? false; key.value = ''
  clearKey.value = profile ? !profile.keyConfigured && !profile.keyUnavailable : false
  message.value = ''; confirmDelete.value = false
}
function apply(value: ModelCatalog, focus: string) {
  catalog.value = value
  choose(focus === 'default' ? value.defaultProfile : value.profiles.find(p => p.id === focus))
}
function payload(activate = false) {
  return { name: name.value.trim() || model.value.trim().slice(0, 100), url: url.value.trim(), model: model.value.trim(), apiKey: key.value,
    clearKey: clearKey.value, versionId: saved.value?.versionId, activate }
}
function tell(text: string, error = false) { message.value = text; failed.value = error }
function profilePath() { return '/api/model-settings/profiles' + (saved.value ? '/' + saved.value.id : '') }
watch([name, url, model, key, clearKey], () => { message.value = ''; confirmDelete.value = false })
function identityChanged() { key.value = ''; emit('close') }
onMounted(async () => {
  window.addEventListener('agent:identity-changed', identityChanged)
  try {
    const value = await api.get<ModelCatalog>('/api/model-settings')
    apply(value, value.profiles.length ? value.selectedProfileId : 'new')
  } catch (e) { tell((e as Error).message, true) }
  finally { loading.value = false }
})
onBeforeUnmount(() => { key.value = ''; window.removeEventListener('agent:identity-changed', identityChanged) })
async function test() {
  busy.value = 'test'; tell('正在检测连接和工具调用能力…')
  try {
    const result = await api.post<{ ok: boolean; message: string }>(profilePath() + '/test', payload())
    tell(result.message, !result.ok)
  } catch (e) { tell((e as Error).message, true) }
  finally { busy.value = '' }
}
async function save(activate: boolean) {
  busy.value = activate ? 'use' : 'save'
  try {
    const result = saved.value
      ? await api.put<ModelCatalog>(profilePath(), payload(activate))
      : await api.post<ModelCatalog>(profilePath(), payload(activate))
    apply(result, result.savedProfileId!); emit('saved'); await nextTick()
    tell(activate ? '已保存并使用。新任务使用此模型，进行中的任务继续使用原配置。' : '配置已保存，可随时在输入框的模型菜单中选择。')
  } catch (e) { tell((e as Error).message, true) }
  finally { busy.value = '' }
}
async function useSaved(id: string) {
  busy.value = 'select'
  try {
    const result = await api.put<ModelCatalog>('/api/model-settings/selection', { profileId: id })
    apply(result, id); emit('saved'); await nextTick(); tell('已切换。进行中的任务继续使用原配置。')
  } catch (e) { tell((e as Error).message, true) }
  finally { busy.value = '' }
}
async function remove() {
  busy.value = 'delete'
  try {
    const result = await api.del<ModelCatalog>(profilePath())
    apply(result, result.selectedProfileId); emit('saved'); await nextTick()
    tell('已从模型列表移除。进行中的任务仍保留其启动时的配置。')
  } catch (e) { tell((e as Error).message, true) }
  finally { busy.value = '' }
}
</script>

<template>
  <div class="model-backdrop" @click.self="!busy && emit('close')" @keydown.esc="!busy && emit('close')">
    <section class="card model-dialog" role="dialog" aria-modal="true" aria-labelledby="model-title">
      <header><div class="model-symbol"><AppIcon name="spark" :size="22" /></div><div><h2 id="model-title">模型配置</h2><p>保存多个模型，按任务自由切换</p></div><button class="btn btn-ghost" aria-label="关闭模型配置" :disabled="!!busy" @click="emit('close')"><AppIcon name="close" /></button></header>
      <div class="model-layout">
        <aside aria-label="已保存模型">
          <div class="list-heading"><span>已保存模型 · {{ catalog?.profiles.length ?? 0 }}</span><button class="btn btn-ghost btn-sm add-model" :disabled="loading || !!busy" @click="choose()">＋ 新增</button></div>
          <button v-if="catalog" class="profile-row" :class="{ active: editing === 'default' }" :disabled="!!busy" @click="choose(catalog.defaultProfile)"><span><strong>系统默认</strong><small>{{ catalog.defaultProfile.model }}</small></span><AppIcon v-if="catalog.selectedProfileId === 'default'" name="check" :size="15" /></button>
          <button v-for="profile in catalog?.profiles" :key="profile.id" class="profile-row" :data-profile-id="profile.id" :class="{ active: editing === profile.id }" :disabled="!!busy" @click="choose(profile)"><span><strong>{{ profile.name }}</strong><small>{{ profile.model }}</small><small>{{ profileHost(profile) }}</small></span><AppIcon v-if="catalog?.selectedProfileId === profile.id" name="check" :size="15" /></button>
          <p v-if="!loading && !catalog?.profiles.length" class="empty-list">添加后会一直保存在这里，切换模型不会覆盖其他配置。</p>
        </aside>
        <div class="profile-editor">
          <template v-if="editing === 'default' && catalog">
            <h3>系统默认</h3><p class="intro">由后端管理的默认模型。新增的模型配置独立保存。</p>
            <dl><dt>模型名</dt><dd>{{ catalog.defaultProfile.model }}</dd><dt>服务地址</dt><dd>{{ catalog.defaultProfile.url || '演示服务' }}</dd></dl>
            <button class="btn btn-primary use-saved-model" :disabled="!!busy || catalog.selectedProfileId === 'default'" @click="useSaved('default')">{{ catalog.selectedProfileId === 'default' ? '正在使用' : '使用系统默认' }}</button>
            <p v-if="message" class="result" :class="{ failed }" role="status">{{ message }}</p>
          </template>
          <form v-else @submit.prevent="save(false)">
            <div class="editor-heading"><h3>{{ saved ? '编辑模型' : '新增模型' }}</h3><span v-if="saved && catalog?.selectedProfileId === saved.id" class="in-use">正在使用</span><button v-if="saved" type="button" class="btn btn-ghost btn-sm delete-profile" :disabled="!!busy" @click="confirmDelete = !confirmDelete">删除</button></div>
            <fieldset :disabled="loading || !!busy">
              <label for="model-label">配置名称（可选）</label><input id="model-label" v-model="name" class="field" maxlength="100" placeholder="留空使用模型名，例如：日常聊天、代码助手" autocomplete="off" />
              <label for="model-url">服务 URL</label><input id="model-url" v-model="url" class="field" type="url" required maxlength="2048" placeholder="https://api.example.com/v1" autocomplete="off" spellcheck="false" />
              <small>OpenAI 兼容接口，支持根地址、/v1 或完整的 /chat/completions 地址。</small>
              <label for="model-key">API Key <span v-if="keyConfigured" class="configured">此配置已设置</span></label>
              <small v-if="saved?.keyUnavailable" class="key-warning">此前密钥无法读取，请重新填写 Key 后保存。</small>
              <input id="model-key" v-model="key" class="field" type="password" :disabled="clearKey" maxlength="8192" :placeholder="keyConfigured ? '留空保留此配置的 Key；填写新值可替换' : '输入此模型服务的 API Key'" autocomplete="new-password" spellcheck="false" />
              <label class="no-key"><input v-model="clearKey" type="checkbox" />此服务无需 Key（例如本地服务）</label>
              <label for="model-name">模型名</label><input id="model-name" v-model="model" class="field" required maxlength="200" placeholder="填写服务商提供的模型标识" autocomplete="off" spellcheck="false" />
              <small>每个配置独立保存 Key，不回显。更换地址时请重新填写 Key。模型需要支持工具调用。</small>
            </fieldset>
            <div v-if="confirmDelete" class="delete-confirm"><p>移除“{{ saved?.name }}”？若正在使用，新任务将切换到系统默认。</p><button type="button" class="btn btn-sm" :disabled="!!busy" @click="confirmDelete = false">取消</button><button type="button" class="btn btn-sm" :disabled="!!busy" @click="remove">确认删除</button></div>
            <p v-if="message" class="result" :class="{ failed }" role="status">{{ message }}</p>
            <small>保存前自动检测连接、流式响应及工具调用；检测失败不会保存配置。</small>
            <div class="actions"><button type="button" class="btn" :disabled="loading || !!busy || !ready" @click="test">{{ busy === 'test' ? '检测中…' : '测试连接' }}</button><button type="submit" class="btn save-model" :disabled="loading || !!busy || !ready">{{ busy === 'save' ? '验证并保存中…' : '保存配置' }}</button><button type="button" class="btn btn-primary save-use-model" :disabled="loading || !!busy || !ready" @click="save(true)">{{ busy === 'use' ? '验证并保存中…' : '保存并使用' }}</button></div>
            <button v-if="saved && !dirty && catalog?.selectedProfileId !== saved.id" type="button" class="btn btn-ghost use-saved-model" :disabled="!!busy" @click="useSaved(saved.id)">使用已保存配置</button>
          </form>
        </div>
      </div>
      <footer><span>切换仅用于新任务 · 配置与选择会在重启后保留</span><span>向量模型由系统管理</span></footer>
    </section>
  </div>
</template>

<style scoped>
.model-backdrop { position:fixed; inset:0; z-index:40; display:grid; place-items:center; padding:24px; background:#17241a50; backdrop-filter:blur(4px); }
.model-dialog { width:min(890px,100%); max-height:calc(100vh - 48px); overflow:auto; padding:26px; box-shadow:var(--shadow-2); }
header { display:flex; align-items:center; gap:13px; } header h2 { margin:0; font-size:21px; letter-spacing:-.5px; } header p { margin:5px 0 0; font-size:12px; color:var(--txt-3); } header button { margin-left:auto; }
.model-symbol { width:44px; height:44px; display:grid; place-items:center; border-radius:13px; background:var(--amber-soft); color:var(--amber); }
.model-layout { display:grid; grid-template-columns:225px minmax(0,1fr); gap:26px; margin-top:24px; } aside { border-right:1px solid var(--line); padding-right:18px; max-height:560px; overflow:auto; }
.list-heading { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; font-size:11px; color:var(--txt-3); }
.profile-row { display:flex; align-items:center; justify-content:space-between; gap:8px; width:100%; text-align:left; border:1px solid transparent; background:transparent; color:var(--txt-2); border-radius:10px; padding:12px 10px; cursor:pointer; margin-bottom:5px; }
.profile-row:hover { background:var(--ink-3); } .profile-row.active { background:var(--amber-soft); border-color:var(--line); color:var(--green); }
.profile-row>span { min-width:0; } .profile-row strong { display:block; font-size:12px; font-weight:600; overflow-wrap:anywhere; } .profile-row small { display:block; margin-top:5px; font-size:10px; color:var(--txt-3); overflow-wrap:anywhere; } .profile-row svg { flex-shrink:0; }
.empty-list { font-size:11px; color:var(--txt-3); line-height:1.9; padding:8px; } h3 { margin:0; font-size:15px; } .editor-heading { display:flex; align-items:center; gap:10px; } .delete-profile { margin-left:auto; color:var(--red); } .in-use,.configured { color:var(--green); font-size:10px; font-weight:400; }
.intro { font-size:12px; color:var(--txt-2); line-height:1.8; margin:18px 0; } fieldset { border:0; margin:0; padding:0; min-width:0; } fieldset>label { display:flex; align-items:center; gap:8px; margin:17px 0 8px; font-size:12px; font-weight:500; }
.field { width:100%; height:41px; font-size:13px; } small { display:block; color:var(--txt-3); font-size:11px; line-height:1.7; margin-top:7px; } fieldset>.no-key { font-size:11px; font-weight:400; color:var(--txt-2); margin:10px 0; } .no-key input { accent-color:var(--green); }
.actions { display:flex; justify-content:flex-end; flex-wrap:wrap; gap:8px; margin-top:24px; } .use-saved-model { margin-top:14px; } .result { font-size:12px; line-height:1.8; background:var(--amber-soft); color:var(--green); padding:11px 13px; border-radius:8px; margin:18px 0 0; overflow-wrap:anywhere; } .result.failed { color:var(--red); background:var(--red-soft); }
footer { display:flex; flex-wrap:wrap; justify-content:space-between; gap:12px; border-top:1px solid var(--line); padding-top:16px; margin-top:24px; color:var(--txt-3); font-size:11px; } .delete-confirm { margin-top:15px; padding:12px; border:1px solid var(--line); border-radius:8px; font-size:12px; } .delete-confirm p { line-height:1.8; margin:0 0 10px; } .delete-confirm button+button { margin-left:8px; } dl { font-size:12px; } dt { color:var(--txt-3); margin-top:20px; } dd { margin:8px 0 0; overflow-wrap:anywhere; }
@media(max-width:650px) { .model-backdrop { padding:12px; } .model-dialog { padding:20px; max-height:calc(100vh - 24px); } .model-layout { grid-template-columns:1fr; gap:18px; } aside { border-right:0; border-bottom:1px solid var(--line); padding:0 0 12px; max-height:190px; } }
</style>
