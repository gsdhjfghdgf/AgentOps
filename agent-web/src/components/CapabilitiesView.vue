<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import AppIcon from './AppIcon.vue'
import SkillDependencies from './SkillDependencies.vue'
interface Tool { name: string; description: string; effect: string; enabled: boolean; askApproval: boolean; approvalRequired: boolean }
interface Skill { id: string; name: string; description: string; keywords: string; content: string; enabled: boolean; builtin: boolean; sourceUrl: string }
interface WorkspaceFile { path: string; bytes: number; sha256: string }
interface Preview { selectionRequired?: boolean; candidates?: Array<{ name: string; url: string }>; url: string; revision: string; sha256: string; name: string; description: string; content: string; files: Array<{ path: string; bytes: number }> }
const dependencySkill=ref<Skill|null>(null)
function dependencyPreview(value:Record<string,unknown>){dependencySkill.value=null;preview.value=value as unknown as Preview}
const tools = ref<Tool[]>([]), skills = ref<Skill[]>([]), files = ref<WorkspaceFile[]>([])
const section = ref('tools'), busy = ref(''), notice = ref(''), failed = ref(false), expanded = ref('')
const search = ref(''), skillForm = ref(false), downloadUrl = ref(''), preview = ref<Preview | null>(null)
const name = ref(''), description = ref(''), keywords = ref(''), instructions = ref('')
const remove = ref<Skill | null>(null), selectedFile = ref<{ path: string; content: string } | null>(null)
const labels: Record<string,string> = { sales_query:'销售数据查询',report_generate:'销售报告',send_email:'邮件发送（演示）',knowledge_search:'知识库检索',calculator:'精确计算',current_time:'当前时间',csv_analyze:'CSV统计',knowledge_list:'知识文档列表',knowledge_read:'阅读知识文档',memory_list:'查看长期记忆',memory_store:'保存长期记忆',memory_forget:'遗忘记忆',text_file_create:'生成文本文件',workspace_list:'工作区文件列表',workspace_read:'读取工作区文件',workspace_search:'搜索代码与文本',workspace_write:'写入工作区文件',command_run:'执行终端命令',web_read:'阅读公网网页',web_search:'搜索网页',skill_list:'查看已安装技能',skill_preview:'预览GitHub技能',skill_install:'安装到Agent技能库',skill_use:'加载技能到当前任务',skill_dependencies:'检查技能依赖',runtime_install_node:'安装私有Node',task_notes:'保存阶段发现',plan_update:'更新任务计划',mcp_connections:'查看MCP连接',mcp_tools:'发现MCP工具',mcp_call:'调用MCP工具',report_export:'生成正式文件',project_propose_batch:'批量文件修改预览',sandbox_run:'隔离终端命令',project_list:'项目目录',project_read:'读取项目文件',project_search:'搜索项目',project_write:'修改项目文件',project_patch:'修改项目片段',project_undo:'撤销项目修改',project_git_status:'Git状态',project_git_diff:'Git差异',browser_open:'打开真实浏览器',browser_snapshot:'读取动态网页',browser_click:'点击网页元素',browser_fill:'填写网页',browser_screenshot:'浏览器截图',browser_close:'关闭浏览器' }
function tell(text: string, error = false) { notice.value = text; failed.value = error }
async function refresh() {
  try {
    const [catalog, workspace] = await Promise.all([api.get<{ tools: Tool[]; skills: Skill[] }>('/api/capabilities'), api.get<{ files: WorkspaceFile[] }>('/api/workspace/files')])
    tools.value = catalog.tools; skills.value = catalog.skills.slice().sort((a,b) => Number(a.builtin)-Number(b.builtin)); files.value = workspace.files
  } catch (e) { tell((e as Error).message, true) }
}
async function toolPreference(tool: Tool, enabled: boolean, askApproval: boolean) {
  busy.value = tool.name
  try { await api.put('/api/capabilities/tools/' + tool.name, { enabled, askApproval }); await refresh(); tell('工具权限已更新，执行前会重新检查。') }
  catch (e) { tell((e as Error).message, true) } finally { busy.value = '' }
}
async function skillEnabled(skill: Skill) {
  busy.value = skill.id
  try { await api.put('/api/skills/' + encodeURIComponent(skill.id) + '/enabled', { enabled: !skill.enabled }); await refresh() }
  catch (e) { tell((e as Error).message, true) } finally { busy.value = '' }
}
function edit(skill?: Skill) { name.value = skill?.name || ''; description.value = skill?.description || ''; keywords.value = skill?.keywords || ''; instructions.value = skill?.content || ''; skillForm.value = true }
async function saveSkill() {
  busy.value = 'save-skill'
  try { await api.post('/api/skills', { name: name.value, description: description.value, keywords: keywords.value, content: instructions.value }); skillForm.value = false; await refresh(); tell('技能已保存。可在对话中用 $技能名指定，Agent 也可根据任务加载已启用技能。') }
  catch (e) { tell((e as Error).message, true) } finally { busy.value = '' }
}
async function loadSkill(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return
  if (file.size > 384000) { tell('技能文件过大', true); return }
  const text = await file.text(); instructions.value = text
  name.value = text.match(/^name:\s*(.+)$/m)?.[1]?.replace(/^['"]|['"]$/g, '') || file.name.replace(/\.md$/i, '')
  description.value = text.match(/^description:\s*(.+)$/m)?.[1]?.slice(0, 500) || '导入的技能'
  keywords.value = text.match(/^keywords:\s*(.+)$/m)?.[1] || name.value
  skillForm.value = true; input.value = ''
}
async function deleteSkill() {
  if (!remove.value) return
  busy.value = 'delete-skill'
  try { await api.del('/api/skills/' + remove.value.id.replace('user:', '')); remove.value = null; await refresh(); tell('技能已移除。已下载的工作区文件会保留。') }
  catch (e) { tell((e as Error).message, true) } finally { busy.value = '' }
}
async function previewSkill() {
  busy.value = 'preview'; preview.value = null
  try { preview.value = await api.post<Preview>('/api/skills/preview-url', { url: downloadUrl.value.trim() }) }
  catch (e) { tell((e as Error).message, true) } finally { busy.value = '' }
}
async function downloadSkill() {
  if (!preview.value) return
  busy.value = 'download'
  try {
    const result = await api.post<{ downloadedFiles: number }>('/api/skills/import-url', { url: preview.value.url, revision: preview.value.revision, sha256: preview.value.sha256 })
    preview.value = null; await refresh(); tell('技能已导入并启用，下载了 ' + result.downloadedFiles + ' 个文本文件；未执行任何脚本。')
  } catch (e) { tell((e as Error).message, true) } finally { busy.value = '' }
}
async function uploadFiles(event: Event) {
  const input = event.target as HTMLInputElement; busy.value = 'upload'
  let imported = 0, skipped = 0
  const existing = new Set(files.value.map(file => file.path))
  const extensions = new Set('txt md csv json java py js mjs cjs svg ts sql html css yaml yml sh ps1 xml properties c cpp h go rs kt kts gradle toml vue tsx jsx ini config r rb php cs swift lock bat cmd'.split(' '))
  const names = new Set(['LICENSE', 'NOTICE', 'Makefile', 'Dockerfile', '.gitignore', '.gitattributes', '.editorconfig', '.dockerignore'])
  try {
    for (const file of Array.from(input.files || [])) {
      const path = file.webkitRelativePath || file.name
      if (/(^|\/)(\.git|node_modules|target|dist|build|\.venv|venv|__pycache__)(\/|$)/.test(path)
        || file.size > 256000 || existing.has(path) || existing.size >= 200
        || (!extensions.has(file.name.split('.').pop()?.toLowerCase() || '') && !names.has(file.name))) { skipped++; continue }
      await api.put('/api/workspace/file', { path, content: await file.text() })
      imported++; existing.add(path)
    }
    tell('已导入 ' + imported + ' 个文件。' + (skipped ? '已跳过 ' + skipped + ' 个依赖、构建文件、已有文件或不支持的文件。' : 'Agent 可读取和搜索。'))
  } catch (e) { tell((e as Error).message, true) } finally { await refresh(); input.value = ''; busy.value = '' }
}
async function readFile(file: WorkspaceFile) {
  try { selectedFile.value = await api.get<{ path: string; content: string }>('/api/workspace/file?path=' + encodeURIComponent(file.path)) }
  catch (e) { tell((e as Error).message, true) }
}
function downloadFile() {
  if (!selectedFile.value) return
  const url = URL.createObjectURL(new Blob([selectedFile.value.content], { type: 'text/plain;charset=utf-8' })), anchor = document.createElement('a')
  anchor.href = url; anchor.download = selectedFile.value.path.split('/').pop() || 'file.txt'; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
onMounted(() => void refresh())
</script>

<template>
  <div class="capabilities-page">
    <header class="cap-heading"><div><span class="eyebrow">MAKE IT YOUR OWN</span><h1>工具与技能</h1><p>管理 Agent 的工具、技能和任务授权。</p></div><button class="btn btn-sm" @click="refresh">刷新</button></header>
    <p v-if="notice" class="notice" :class="{ error: failed }" role="status">{{ notice }}</p>
    <nav class="cap-tabs"><button v-for="tab in [{id:'tools',label:'工具与权限',count:tools.length},{id:'skills',label:'技能库',count:skills.length},{id:'workspace',label:'工作区文件',count:files.length}]" :key="tab.id" class="btn btn-sm" :class="{ selected: section === tab.id }" @click="section = tab.id">{{ tab.label }} <span>{{ tab.count }}</span></button></nav>
    <section v-if="section === 'tools'" class="tools-section">
      <div class="card cap-guide"><AppIcon name="check" :size="21" /><div><strong>权限由你决定</strong><p>联网、主机命令、隔离命令和浏览器交互各按任务授权一次，可在任务工作台撤销。项目内文件读写直接执行；其他写操作按工具规则批准。主机终端默认关闭且使用系统用户权限；隔离终端使用虚拟机容器，无网络、无主机目录挂载。</p></div></div>
      <input v-model="search" class="field cap-search" placeholder="搜索工具名称或用途…" aria-label="搜索工具" />
      <div class="tool-grid"><article v-for="tool in tools.filter(t => (labels[t.name] + t.name + t.description).toLowerCase().includes(search.toLowerCase()))" :key="tool.name" class="card tool-card" :data-tool="tool.name"><div class="tool-card-head"><span class="cap-icon"><AppIcon :name="tool.effect === 'read_only' ? 'search' : 'activity'" :size="18" /></span><div><h3>{{ labels[tool.name] || tool.name }}</h3><small>{{ tool.name }}</small></div><input type="checkbox" class="enable-tool" :aria-label="'启用工具 ' + tool.name" :checked="tool.enabled" :disabled="!!busy" @change="toolPreference(tool, ($event.target as HTMLInputElement).checked, tool.askApproval)" /></div><p>{{ tool.description }}</p><div class="tool-card-footer"><span class="risk-label">{{ tool.effect === 'read_only' ? '读取' : tool.effect === 'non_idempotent_write' ? '系统 / 外部操作' : '写入' }}</span><span v-if="tool.name.startsWith('project_')" class="small muted">项目内直接执行</span><select v-else class="field" :aria-label="tool.name + ' 授权方式'" :value="tool.askApproval ? 'ask' : 'auto'" :disabled="!!busy || tool.approvalRequired" @change="toolPreference(tool, tool.enabled, ($event.target as HTMLSelectElement).value === 'ask')"><option v-if="!tool.approvalRequired" value="auto">自动允许</option><option value="ask">{{ ['web_read','web_search','skill_preview','command_run','browser_open','browser_click','browser_fill'].includes(tool.name) ? '每个任务询问一次' : '每次询问' }}</option></select></div></article></div>
    </section>
    <section v-else-if="section === 'skills'">
      <div class="card download-skill"><div><h2>安装 GitHub 技能到本 Agent</h2><p>粘贴仓库、技能目录或 SKILL.md 链接，预览后安装并启用。支持100个以内的文本附件、总计8MB；安装不执行脚本，也不代表外部依赖已就绪。</p></div><form @submit.prevent="previewSkill"><input v-model="downloadUrl" class="field" type="url" required placeholder="https://github.com/owner/repo/tree/main/skills/example" aria-label="技能GitHub链接" /><button class="btn btn-primary" :disabled="!!busy">{{ busy === 'preview' ? '读取中…' : '预览技能' }}</button></form></div>
      <div class="skill-actions"><span class="small muted">已安装技能可由 Agent 按任务加载，也可用 $技能名指定。技能提供工作流指导，不扩大工具权限。</span><label class="btn btn-sm">导入 SKILL.md<input type="file" accept=".md" hidden @change="loadSkill" /></label><button class="btn btn-sm" @click="edit()">创建技能</button></div>
      <div class="skill-grid"><article v-for="skill in skills" :key="skill.id" class="card skill-card"><div class="skill-head"><AppIcon name="spark" :size="19" /><h3>{{ skill.name }}</h3><span>{{ skill.builtin ? '内置' : skill.sourceUrl ? '已安装' : '自定义' }}</span><input type="checkbox" :checked="skill.enabled" :aria-label="'启用技能 ' + skill.name" :disabled="!!busy" @change="skillEnabled(skill)" /></div><p>{{ skill.description }}</p><small>触发词 · {{ skill.keywords || skill.name }}</small><div class="skill-buttons"><button class="btn btn-ghost btn-sm check-dependencies" @click="dependencySkill=skill">依赖 / 版本</button><button class="btn btn-ghost btn-sm" @click="expanded = expanded === skill.id ? '' : skill.id">{{ expanded === skill.id ? '收起说明' : '查看说明' }}</button><button v-if="!skill.builtin" class="btn btn-ghost btn-sm" @click="edit(skill)">编辑</button><button v-if="!skill.builtin" class="btn btn-ghost btn-sm" @click="remove = skill">移除</button></div><pre v-if="expanded === skill.id" class="content-preview">{{ skill.content }}</pre></article></div>
    </section>
    <section v-else class="card workspace-section"><h2>专属工作区</h2><p>上传代码或文本，Agent 可读取、搜索并申请修改。文件工具仅访问当前账号的专属目录。支持常见文本与代码扩展名，每个文件256KB，最多200个文件；已有文件不会直接覆盖。</p><div class="workspace-actions"><label class="btn btn-primary btn-sm">上传文件<input hidden type="file" multiple :disabled="!!busy" @change="uploadFiles" /></label><label class="btn btn-sm">上传代码目录<input hidden type="file" multiple webkitdirectory :disabled="!!busy" @change="uploadFiles" /></label></div><div v-for="file in files" :key="file.path" class="workspace-file"><span>{{ file.path }}</span><small>{{ (file.bytes / 1024).toFixed(1) }} KB</small><button class="btn btn-sm" @click="readFile(file)">查看 / 下载</button></div><p v-if="!files.length" class="empty-note">尚无文件。上传后可以要求 Agent 阅读项目、搜索函数或提出修改。</p></section>
    <SkillDependencies v-if="dependencySkill" :skill="dependencySkill" @close="dependencySkill=null" @updated="refresh" @preview="dependencyPreview" />
    <div v-if="skillForm || preview || remove || selectedFile" class="cap-overlay" @click.self="!busy && (skillForm = false, preview = null, remove = null, selectedFile = null)">
      <form v-if="skillForm" class="card cap-dialog" @submit.prevent="saveSkill"><h2>编辑技能</h2><label>名称<input v-model="name" class="field" required maxlength="100" /></label><label>说明<input v-model="description" class="field" required maxlength="500" /></label><label>触发词<input v-model="keywords" class="field" maxlength="500" placeholder="逗号分隔，例如：会议,纪要" /></label><label>技能内容<textarea v-model="instructions" class="field" rows="12" required maxlength="98000"></textarea></label><p v-if="failed" class="error-text">{{ notice }}</p><div class="dialog-actions"><button type="button" class="btn" @click="skillForm = false">取消</button><button class="btn btn-primary" :disabled="!!busy">保存技能</button></div></form>
      <div v-else-if="preview?.selectionRequired" class="card cap-dialog"><h2>选择要安装的技能</h2><p>仓库包含多个技能，请选择其中一个。</p><button v-for="candidate in preview.candidates" :key="candidate.url" class="btn" :disabled="!!busy" @click="downloadUrl=candidate.url; previewSkill()">{{ candidate.name }}</button><button class="btn" @click="preview=null">取消</button></div>
      <div v-else-if="preview" class="card cap-dialog"><h2>{{ preview.name }}</h2><p>{{ preview.description }}</p><small>固定版本 {{ preview.revision.slice(0, 12) }} · {{ preview.files.length }} 个允许的文本附件</small><details><summary>查看下载文件</summary><p v-for="file in preview.files" :key="file.path" class="small muted">{{ file.path }} · {{ file.bytes }} bytes</p></details><pre class="content-preview">{{ preview.content }}</pre><p class="small muted">导入后可用于任务。脚本执行须启用命令工具，并在当前任务内取得终端授权。</p><p v-if="failed" class="error-text">{{ notice }}</p><div class="dialog-actions"><button class="btn" :disabled="!!busy" @click="preview = null">取消</button><button class="btn btn-primary" :disabled="!!busy" @click="downloadSkill">{{ busy === 'download' ? '下载中…' : '安装到本Agent并启用' }}</button></div></div>
      <div v-else-if="selectedFile" class="card cap-dialog"><h2>{{ selectedFile.path }}</h2><pre class="content-preview">{{ selectedFile.content }}</pre><div class="dialog-actions"><button class="btn" @click="selectedFile = null">关闭</button><button class="btn btn-primary" @click="downloadFile">下载文件</button></div></div>
      <div v-else-if="remove" class="card cap-dialog"><h2>移除 {{ remove.name }}？</h2><p>移除技能说明与匹配规则，工作区文件会保留。</p><div class="dialog-actions"><button class="btn" @click="remove = null">取消</button><button class="btn btn-danger" :disabled="!!busy" @click="deleteSkill">确认移除</button></div></div>
    </div>
  </div>
</template>

<style scoped>
.capabilities-page { padding:32px 40px; overflow:auto; flex:1; min-height:0; }.cap-heading { display:flex; align-items:center; justify-content:space-between; margin-bottom:24px; }.cap-heading h1 { font-size:25px; margin:10px 0; }.cap-heading p { color:var(--txt-3); font-size:12px; }.cap-tabs { display:flex; gap:8px; margin:20px 0; }.cap-tabs .selected { background:var(--amber-soft); color:var(--green); }.cap-tabs span { margin-left:7px; opacity:.65; }.cap-guide { display:flex; gap:14px; align-items:start; margin-bottom:18px; }.cap-guide strong { font-size:13px; }.cap-guide p,.download-skill p,.workspace-section>p { margin:8px 0 0; font-size:12px; line-height:1.8; color:var(--txt-3); }.cap-guide svg { color:var(--green); flex-shrink:0; }.cap-search { max-width:380px; margin-bottom:18px; }.tool-grid,.skill-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:16px; }.tool-card,.skill-card { min-width:0; }.tool-card-head,.skill-head { display:flex; align-items:center; gap:10px; }.tool-card-head h3,.skill-head h3 { margin:0; font-size:13px; font-weight:600; }.tool-card-head small { display:block; font-size:10px; color:var(--txt-3); margin-top:5px; }.tool-card-head input,.skill-head input { margin-left:auto; accent-color:var(--green); width:16px; height:16px; flex-shrink:0; }.cap-icon { display:grid; place-items:center; width:32px; height:32px; border-radius:8px; color:var(--green); background:var(--amber-soft); flex-shrink:0; }.tool-card>p,.skill-card>p { color:var(--txt-3); font-size:11px; line-height:1.9; min-height:58px; }.tool-card-footer { display:flex; justify-content:space-between; align-items:center; gap:10px; border-top:1px solid var(--line); padding-top:12px; }.tool-card-footer select { width:auto; font-size:11px; padding:5px 8px; }.risk-label { font-size:10px; color:var(--txt-3); }.download-skill form { display:flex; gap:10px; margin-top:18px; }.download-skill .field { flex:1; }.download-skill button { flex-shrink:0; }.download-skill h2,.workspace-section h2 { font-size:17px; margin:0; }.skill-actions { display:flex; align-items:center; gap:8px; margin:18px 0; flex-wrap:wrap; }.skill-actions>span { flex:1; font-size:11px; }.skill-head span { font-size:10px; color:var(--txt-3); }.skill-card>small { color:var(--txt-3); font-size:10px; }.skill-buttons { display:flex; gap:6px; margin-top:15px; }.content-preview { white-space:pre-wrap; overflow-wrap:anywhere; max-height:340px; overflow:auto; background:var(--ink-1); border:1px solid var(--line); padding:14px; border-radius:8px; font:12px/1.8 var(--font-mono); }.workspace-actions { display:flex; gap:10px; margin:20px 0; }.workspace-file { display:flex; align-items:center; gap:12px; padding:12px 0; border-bottom:1px solid var(--line); font-size:12px; }.workspace-file>span { flex:1; overflow-wrap:anywhere; }.workspace-file small { color:var(--txt-3); }.cap-overlay { position:fixed; inset:0; z-index:35; padding:24px; display:grid; place-items:center; background:#17241a50; backdrop-filter:blur(4px); }.cap-dialog { width:min(720px,100%); max-height:calc(100vh - 48px); overflow:auto; padding:28px; }.cap-dialog h2 { margin-top:0; font-size:19px; }.cap-dialog p { font-size:12px; color:var(--txt-2); line-height:1.8; }.cap-dialog label { display:block; margin:14px 0; font-size:12px; }.cap-dialog .field { display:block; width:100%; margin-top:7px; }.dialog-actions { display:flex; justify-content:flex-end; gap:10px; margin-top:22px; }.notice { font-size:12px; padding:12px 14px; border-radius:8px; background:var(--amber-soft); color:var(--green); }.notice.error,.error-text { color:var(--red); }
@media(max-width:1200px) { .tool-grid,.skill-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
</style>
