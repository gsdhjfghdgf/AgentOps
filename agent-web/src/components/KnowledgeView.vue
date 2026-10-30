<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import AppIcon from './AppIcon.vue'
interface DocumentItem { documentId: string; title: string; activeVersion: number; chunkCount?: number }
interface MemoryItem { memoryKey: string; kind: string; content: string; authority: string; automatic?: boolean; evidence?: string }
interface UploadItem { name: string; status: string; progress: number; error?: string }
const docs = ref<DocumentItem[]>([])
const memories = ref<MemoryItem[]>([])
const autoEnabled = ref(true)
const mode = ref('rules')
const settingsBusy = ref(false)
const runtime = ref<{ provider: string; chatModel: string; embeddingProvider: string; embeddingModel: string; embeddingDimensions: number } | null>(null)
const checking = ref(false)
const connectionResult = ref('')
const reindexing = ref('')
async function checkConnection() {
  if (checking.value) return
  checking.value = true
  connectionResult.value = '正在检测聊天与向量服务…'
  try {
    const result = await api.post<{ chat: { ok: boolean; message: string }; embedding: { ok: boolean; message: string } }>('/api/runtime/check')
    connectionResult.value = `${result.chat.ok ? '✓' : '✗'} ${result.chat.message}；${result.embedding.ok ? '✓' : '✗'} ${result.embedding.message}`
  } catch (e) { connectionResult.value = (e as Error).message }
  finally { checking.value = false }
}
async function reindexDocument(id: string) {
  if (reindexing.value) return
  reindexing.value = id
  try {
    await api.post('/api/knowledge/documents/' + id + '/reindex')
    tell('已使用当前向量模型重建索引')
    await refresh()
  } catch (e) { tell((e as Error).message, true) }
  finally { reindexing.value = '' }
}
const dragging = ref(false)
const uploads = ref<UploadItem[]>([])
const importing = ref(false)
const docTitle = ref('')
const docContent = ref('')
const searchQuery = ref('')
const searchHits = ref<Array<{ title: string; content: string; score: number }>>([])
const searching = ref(false)
const notice = ref('')
const noticeError = ref(false)
const editing = ref<MemoryItem | null>(null)
const editContent = ref('')
const noteContent = ref('')
const noteKey = ref('manual:' + crypto.randomUUID())
const saving = ref(false)
const forgetTarget = ref<MemoryItem | null>(null)
let timer: ReturnType<typeof setInterval> | undefined
let mounted = true
let refreshing = false
const kindLabel = (kind: string) => ({ preference: '个人偏好', fact: '重要事实', experience: '经验与方法', episode: '重要经历' }[kind] || '记忆')
function tell(text: string, error = false) { notice.value = text; noticeError.value = error }
async function refresh() {
  if (refreshing || !mounted) return
  refreshing = true
  try {
    const [documents, memory, settings, model] = await Promise.all([
      api.get<{ documents: DocumentItem[] }>('/api/knowledge/documents'),
      api.get<{ memories: MemoryItem[] }>('/api/memories?limit=100'),
      api.get<{ enabled: boolean; mode: string }>('/api/memory-settings'),
      api.get<NonNullable<typeof runtime.value>>('/api/runtime'),
    ])
    if (!mounted) return
    docs.value = documents.documents
    memories.value = memory.memories
    if (!settingsBusy.value) autoEnabled.value = settings.enabled
    mode.value = settings.mode
    runtime.value = model
  } catch (e) { if (mounted) tell((e as Error).message, true) }
  finally { refreshing = false }
}
async function importFiles(files: File[]) {
  dragging.value = false
  if (importing.value || !files.length) return
  if (files.length > 10) { tell('一次最多导入 10 个文件', true); return }
  importing.value = true
  uploads.value = files.map(file => ({ name: file.name, status: '等待导入', progress: 0 }))
  try {
    for (let index = 0; index < files.length; index++) {
      const file = files[index], item = uploads.value[index]
      if (file.size > 20 * 1024 * 1024) { item.status = '导入失败'; item.error = '文件超过 20 MB'; continue }
      item.status = '正在上传'
      try {
        const result = await api.upload<{ chunkCount: number }>('/api/knowledge/documents/upload', file, percent => {
          item.progress = percent
          if (percent >= 100) item.status = '正在解析与建立索引'
        })
        item.progress = 100
        item.status = '已就绪 · ' + result.chunkCount + ' 个内容片段'
      } catch (e) { item.status = '导入失败'; item.error = (e as Error).message }
    }
    const failed = uploads.value.filter(item => item.error).length
    tell(failed ? failed + ' 个文件未导入，请查看具体原因' : '文档已导入，可以在对话中提问了', !!failed)
    await refresh()
  } finally { importing.value = false }
}
function fileChanged(event: Event) {
  const input = event.target as HTMLInputElement
  void importFiles(Array.from(input.files || []))
  input.value = ''
}
function dropped(event: DragEvent) { void importFiles(Array.from(event.dataTransfer?.files || [])) }
async function importText() {
  if (!docTitle.value.trim() || !docContent.value.trim() || importing.value) return
  importing.value = true
  try {
    await api.post('/api/knowledge/documents', { title: docTitle.value.trim(), content: docContent.value })
    docTitle.value = ''; docContent.value = ''
    tell('文本已导入，可以在对话中提问了')
    await refresh()
  } catch (e) { tell((e as Error).message, true) }
  finally { importing.value = false }
}
async function removeDoc(id: string) {
  try { await api.del('/api/knowledge/documents/' + id); await refresh(); tell('文档已移出知识库') }
  catch (e) { tell((e as Error).message, true) }
}
async function doSearch() {
  if (!searchQuery.value.trim()) return
  searching.value = true
  try {
    const result = await api.post<{ chunks: typeof searchHits.value }>('/api/knowledge/search', { query: searchQuery.value.trim(), limit: 5 })
    searchHits.value = result.chunks
    if (!result.chunks.length) tell('没有找到相关内容')
  } catch (e) { tell((e as Error).message, true) }
  finally { searching.value = false }
}
async function toggleAuto() {
  if (settingsBusy.value) return
  const next = !autoEnabled.value
  settingsBusy.value = true
  try { await api.put('/api/memory-settings', { enabled: next }); autoEnabled.value = next }
  catch (e) { tell((e as Error).message, true) }
  finally { settingsBusy.value = false }
}
function edit(memory: MemoryItem) { editing.value = memory; editContent.value = memory.content }
async function saveCorrection() {
  if (!editing.value || !editContent.value.trim()) return
  saving.value = true
  try {
    await api.post('/api/memories', { memoryKey: editing.value.memoryKey, kind: editing.value.kind, content: editContent.value.trim() })
    editing.value = null
    tell('记忆已纠正，后续自动整理不会覆盖你的修改')
    await refresh()
  } catch (e) { tell((e as Error).message, true) }
  finally { saving.value = false }
}
async function addNote() {
  if (!noteContent.value.trim()) return
  saving.value = true
  try {
    await api.post('/api/memories', { memoryKey: noteKey.value, kind: 'fact', content: noteContent.value.trim() })
    noteContent.value = ''; noteKey.value = 'manual:' + crypto.randomUUID()
    tell('补充记忆已保存')
    await refresh()
  } catch (e) { tell((e as Error).message, true) }
  finally { saving.value = false }
}
async function forget() {
  if (!forgetTarget.value) return
  try {
    await api.del('/api/memories/' + encodeURIComponent(forgetTarget.value.memoryKey))
    forgetTarget.value = null
    await refresh()
    tell('已遗忘这条记忆')
  } catch (e) { tell((e as Error).message, true) }
}
onMounted(() => { void refresh(); timer = setInterval(() => void refresh(), 5000) })
onBeforeUnmount(() => { mounted = false; clearInterval(timer) })
</script>

<template>
  <div class="knowledge-page">
    <header class="page-heading">
      <div><span class="eyebrow">YOUR PERSONAL CONTEXT</span><h1>知识与记忆</h1><p>你的文档提供依据，对话中的重要信息成为下一次协作的起点。</p></div>
      <button class="btn btn-sm" @click="refresh">刷新</button>
    </header>
    <p v-if="notice" class="notice" :class="{ error: noticeError }" role="status">{{ notice }}</p>
    <section v-if="runtime" class="card model-status" aria-label="模型配置">
      <div><strong>{{ runtime.provider === 'mock' ? '演示模式' : runtime.chatModel }}</strong><span>向量 · {{ runtime.embeddingProvider }} / {{ runtime.embeddingModel }} · {{ runtime.embeddingDimensions }} 维</span><small v-if="connectionResult" role="status">{{ connectionResult }}</small></div>
      <button class="btn btn-sm" :disabled="checking" @click="checkConnection">{{ checking ? '检测中…' : '检测连接' }}</button>
    </section>
    <div class="knowledge-grid">
      <section class="card knowledge-pane">
        <div class="pane-heading"><span class="pane-icon"><AppIcon name="book" :size="21" /></span><div><h2>知识库</h2><p>导入文档，让回答有据可查</p></div><span class="count">{{ docs.length }} 份文档</span></div>
        <label class="upload-zone" :class="{ dragging, disabled: importing }" @dragover.prevent="dragging=true" @dragleave.prevent="dragging=false" @drop.prevent="dropped">
          <input type="file" accept=".pdf,.docx,.doc,.txt,.md,.csv" multiple :disabled="importing" @change="fileChanged" />
          <span class="upload-icon"><AppIcon name="plus" :size="26" /></span>
          <strong>{{ importing ? '正在处理文档…' : '拖入文档，或点击选择文件' }}</strong>
          <span>PDF、Word、TXT、Markdown、CSV · 每个文件最大 20 MB</span>
          <small>自动解析文字、分块并建立索引。扫描件请先进行 OCR。</small>
        </label>
        <div v-if="uploads.length" class="upload-list" aria-live="polite">
          <div v-for="(item,index) in uploads" :key="index" class="upload-item">
            <div><strong>{{ item.name }}</strong><span :class="{ 'error-text': item.error }">{{ item.error || item.status }}</span></div>
            <progress v-if="!item.error && !item.status.startsWith('已就绪')" :value="item.progress" max="100"></progress>
          </div>
        </div>
        <details class="alternative"><summary>也可以粘贴文本</summary><form @submit.prevent="importText"><input v-model="docTitle" class="field" placeholder="给这份文本起个名字" maxlength="240" aria-label="文本标题" /><textarea v-model="docContent" class="field" rows="5" placeholder="粘贴需要加入知识库的内容…" aria-label="文本正文"></textarea><button class="btn btn-primary btn-sm" :disabled="importing || !docTitle.trim() || !docContent.trim()">导入文本</button></form></details>
        <div class="documents">
          <div v-for="doc in docs" :key="doc.documentId" class="document-row">
            <span class="file-icon"><AppIcon name="book" :size="17" /></span><div><strong>{{ doc.title }}</strong><span>v{{ doc.activeVersion }} · {{ doc.activeVersion ? '可用于对话检索' : '尚未就绪' }}<template v-if="doc.chunkCount"> · {{ doc.chunkCount }} 个片段</template></span></div>
            <button class="btn btn-ghost btn-sm" :disabled="!!reindexing || importing" @click="reindexDocument(doc.documentId)">{{ reindexing === doc.documentId ? '索引中…' : '重建索引' }}</button>
            <button class="btn btn-ghost btn-sm" :disabled="!!reindexing || importing" @click="removeDoc(doc.documentId)">移除</button>
          </div>
          <p v-if="!docs.length" class="empty-note">还没有文档。导入后，直接在对话中询问相关问题。</p>
        </div>
        <form class="search" @submit.prevent="doSearch"><input v-model="searchQuery" class="field" placeholder="试着搜索文档中的一个问题…" aria-label="搜索知识库" /><button class="btn btn-sm" :disabled="searching || !searchQuery.trim()">{{ searching ? '检索中' : '检索' }}</button></form>
        <div v-for="(hit,index) in searchHits" :key="index" class="search-hit"><strong>{{ hit.title }}</strong><p>{{ hit.content }}</p></div>
      </section>

      <section class="card memory-pane">
        <div class="pane-heading"><span class="pane-icon memory-icon"><AppIcon name="spark" :size="21" /></span><div><h2>长期记忆</h2><p>重要信息，逐渐沉淀</p></div><span class="count">{{ memories.length }} 条</span></div>
        <div class="auto-setting">
          <div><strong>自动整理对话中的记忆</strong><p>任务完成后保留偏好、稳定事实和经验，<br />无需填写记忆键，也不会保存每一句对话。</p></div>
          <button class="switch" :class="{ on: autoEnabled }" role="switch" :aria-checked="autoEnabled" aria-label="自动整理记忆" :disabled="settingsBusy" @click="toggleAuto"><span></span></button>
        </div>
        <p v-if="mode==='rules'" class="mode-note">当前为演示模式：自动提取常见偏好与“记住”指令。配置真实模型后，将由 AI 总结。</p>
        <p v-else class="mode-note">由当前配置的 AI 模型自动总结。你可以随时纠正、遗忘或关闭自动整理。</p>
        <div class="memory-list">
          <article v-for="memory in memories" :key="memory.memoryKey" class="memory-item">
            <div class="memory-meta"><span>{{ kindLabel(memory.kind) }}</span><span>{{ memory.authority === 'explicit' ? memory.automatic ? '已纠正' : '手动补充' : '自动整理' }}</span></div>
            <p>{{ memory.content }}</p>
            <details v-if="memory.evidence" class="evidence"><summary>查看对话依据</summary><blockquote>{{ memory.evidence }}</blockquote></details>
            <div class="memory-actions"><button @click="edit(memory)">纠正</button><button @click="forgetTarget=memory">遗忘</button></div>
          </article>
          <div v-if="!memories.length" class="memory-empty"><AppIcon name="spark" :size="30" /><strong>有价值的信息，会慢慢留下来</strong><p>试着在对话中说“以后请用中文回答”。<br />任务完成后，这里会自动出现整理后的记忆。</p></div>
        </div>
        <details class="alternative"><summary>手动补充一条记忆</summary><form @submit.prevent="addNote"><textarea v-model="noteContent" class="field" rows="3" maxlength="2000" placeholder="还有什么是希望我长期记住的？" aria-label="补充记忆内容"></textarea><button class="btn btn-sm" :disabled="saving || !noteContent.trim()">保存补充</button></form></details>
      </section>
    </div>
    <div v-if="editing || forgetTarget" class="overlay" @click.self="editing=null; forgetTarget=null">
      <form v-if="editing" class="card dialog" @submit.prevent="saveCorrection"><h3>纠正记忆</h3><p>你的修改会优先于自动提取。</p><textarea v-model="editContent" class="field" rows="5" maxlength="2000" aria-label="纠正记忆内容"></textarea><p v-if="noticeError" class="error-text">{{ notice }}</p><div><button type="button" class="btn" @click="editing=null">取消</button><button class="btn btn-primary" :disabled="saving || !editContent.trim()">保存修改</button></div></form>
      <div v-else class="card dialog" role="alertdialog" aria-modal="true"><h3>遗忘这条记忆？</h3><p>遗忘后不再用于后续对话，也不会被旧的自动任务重新写入。</p><div><button class="btn" @click="forgetTarget=null">取消</button><button class="btn btn-danger" @click="forget">确认遗忘</button></div></div>
    </div>
  </div>
</template>

<style scoped>
.model-status { max-width:1240px; margin:0 auto 20px; display:flex; gap:16px; align-items:center; justify-content:space-between; }
.model-status div { display:flex; flex-direction:column; gap:6px; min-width:0; }
.model-status span,.model-status small { color:var(--txt-2); font-size:12px; overflow-wrap:anywhere; }
.knowledge-page { padding:34px 40px; overflow:auto; flex:1; }
.page-heading { display:flex; align-items:center; justify-content:space-between; max-width:1240px; margin:0 auto 28px; }
.eyebrow { font-size:9px; color:var(--amber); letter-spacing:2px; font-weight:600; }
h1 { font-size:28px; font-weight:550; letter-spacing:-.8px; margin:8px 0; }
.page-heading p { margin:0; font-size:12px; color:var(--txt-2); }
.knowledge-grid { display:grid; grid-template-columns:1.1fr 1fr; align-items:start; gap:24px; max-width:1240px; margin:auto; }
.knowledge-pane,.memory-pane { padding:25px; box-shadow:none; }
.pane-heading { display:flex; align-items:center; gap:12px; margin-bottom:24px; }
.pane-icon { display:grid; place-items:center; width:42px; height:42px; border-radius:12px; color:var(--blue); background:var(--blue-soft); }
.memory-icon { color:var(--amber); background:var(--amber-soft); }
h2 { font-size:16px; font-weight:600; margin:0; }
.pane-heading p { color:var(--txt-3); font-size:11px; margin:3px 0 0; }
.count { margin-left:auto; font-size:10px; color:var(--txt-3); }
.upload-zone { display:flex; flex-direction:column; align-items:center; gap:10px; text-align:center; cursor:pointer; border:1px dashed var(--line-strong); border-radius:12px; padding:25px 14px; background:var(--ink-0); transition:border-color .15s,background .15s; }
.upload-zone:hover,.upload-zone.dragging { border-color:var(--amber); background:var(--amber-soft); }
.upload-zone input { position:absolute; width:1px; height:1px; opacity:0; }
.upload-zone:focus-within { outline:2px solid var(--amber); outline-offset:3px; }
.upload-zone.disabled { opacity:.6; cursor:wait; }
.upload-icon { color:var(--amber); }
.upload-zone strong { font-size:13px; font-weight:500; }
.upload-zone > span:not(.upload-icon),.upload-zone small { color:var(--txt-3); font-size:10px; line-height:1.7; }
.alternative { margin-top:18px; color:var(--txt-2); font-size:11px; }
.alternative summary { cursor:pointer; }
.alternative form { display:grid; gap:10px; margin-top:12px; }
.alternative form button { justify-self:start; }
.documents { margin-top:22px; }
.document-row { display:flex; align-items:center; gap:10px; padding:13px 0; border-bottom:1px solid var(--line); }
.file-icon { color:var(--blue); flex-shrink:0; }
.document-row > div { flex:1; min-width:0; }
.document-row strong { display:block; font-size:12px; font-weight:500; overflow-wrap:anywhere; }
.document-row span:not(.file-icon) { font-size:10px; color:var(--txt-3); }
.empty-note { color:var(--txt-3); font-size:11px; padding:12px 0; }
.search { display:flex; gap:8px; margin-top:20px; }
.search .field { font-size:12px; }
.search-hit { margin-top:12px; padding:14px; border-radius:9px; background:var(--ink-0); font-size:12px; }
.search-hit p { font-size:11px; color:var(--txt-2); white-space:pre-wrap; max-height:180px; overflow:auto; }
.auto-setting { display:flex; justify-content:space-between; gap:15px; padding:17px; border-radius:12px; background:var(--ink-0); border:1px solid var(--line); }
.auto-setting strong { font-size:12px; font-weight:500; }
.auto-setting p { color:var(--txt-3); font-size:11px; margin:7px 0 0; }
.switch { width:36px; height:21px; border:0; padding:3px; border-radius:20px; background:var(--line-strong); flex-shrink:0; cursor:pointer; margin-top:2px; }
.switch span { display:block; height:15px; width:15px; border-radius:50%; background:#fff; transition:transform .18s; }
.switch.on { background:var(--amber-fill); }
.switch.on span { transform:translateX(15px); }
.mode-note { color:var(--txt-3); font-size:10px; line-height:1.8; padding:0 5px; margin:12px 0 22px; }
.memory-item { padding:16px 0; border-bottom:1px solid var(--line); }
.memory-meta { display:flex; justify-content:space-between; color:var(--txt-3); font-size:10px; }
.memory-meta span:first-child { color:var(--amber); }
.memory-item > p { font-size:13px; line-height:1.85; margin:10px 0; }
.memory-actions { display:flex; justify-content:flex-end; gap:15px; }
.memory-actions button { border:0; padding:0; background:none; color:var(--txt-3); font-size:10px; cursor:pointer; }
.memory-actions button:hover { color:var(--amber); }
.evidence { font-size:10px; color:var(--txt-3); margin:8px 0; }
.evidence summary { cursor:pointer; }
.evidence blockquote { margin:8px 0; padding:8px 12px; border-left:2px solid var(--line-strong); color:var(--txt-2); }
.memory-empty { display:flex; flex-direction:column; align-items:center; gap:15px; text-align:center; padding:40px 10px; color:var(--txt-3); }
.memory-empty strong { font-size:12px; font-weight:500; color:var(--txt-2); }
.memory-empty p { font-size:11px; line-height:1.9; margin:0; }
.upload-list { display:grid; gap:10px; margin-top:12px; }
.upload-item { padding:10px 12px; background:var(--ink-0); border-radius:8px; }
.upload-item > div { display:flex; flex-wrap:wrap; justify-content:space-between; gap:6px; }
.upload-item strong { font-size:11px; font-weight:500; overflow-wrap:anywhere; }
.upload-item span { font-size:10px; color:var(--txt-3); }
.upload-item progress { width:100%; height:4px; accent-color:var(--amber); }
.notice { max-width:1240px; margin:0 auto 18px; padding:12px 16px; border-radius:9px; font-size:12px; background:var(--green-soft); color:var(--green); }
.notice.error { background:var(--red-soft); color:var(--red); }
.error-text { color:var(--red)!important; }
.overlay { position:fixed; inset:0; z-index:30; display:grid; place-items:center; background:#17241a50; backdrop-filter:blur(4px); }
.dialog { width:min(480px,90vw); padding:26px; }
.dialog h3 { margin:0 0 12px; font-size:18px; font-weight:550; }
.dialog p { font-size:12px; color:var(--txt-2); }
.dialog > div { display:flex; justify-content:flex-end; gap:8px; margin-top:20px; }
@media(max-width:1050px) { .knowledge-page { padding:25px; } .knowledge-grid { gap:16px; } .knowledge-pane,.memory-pane { padding:20px; } }
@media(max-width:850px) { .knowledge-grid { grid-template-columns:1fr; } }
</style>
