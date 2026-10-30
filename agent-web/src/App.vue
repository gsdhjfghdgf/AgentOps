<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChatView from './components/ChatView.vue'
import SessionRunIndicator from './components/SessionRunIndicator.vue'
const KnowledgeView = defineAsyncComponent(() => import('./components/KnowledgeView.vue'))
const EvalView = defineAsyncComponent(() => import('./components/EvalView.vue'))
import AppIcon from './components/AppIcon.vue'
const ModelSettings = defineAsyncComponent(() => import('./components/ModelSettings.vue'))
const ProfileSettings = defineAsyncComponent(() => import('./components/ProfileSettings.vue'))
const CapabilitiesView = defineAsyncComponent(() => import('./components/CapabilitiesView.vue'))
import ProjectPicker from './components/ProjectPicker.vue'
const OperationsView = defineAsyncComponent(() => import('./components/OperationsView.vue'))
import NotificationBell from './components/NotificationBell.vue'
import { api, identity, listSessions, saveIdentity, createSession, updateSession, deleteSession, clearSessions, type SessionItem, type RunMode } from './api'

type Tab = 'chat' | 'knowledge' | 'eval' | 'capabilities' | 'operations'

const tab = ref<Tab>('chat')
const operationsSection=ref('mcp')
const navigation:Array<{key:string;tab:Tab;label:string;icon:string;section?:string}>=[
 {key:'chat',tab:'chat',label:'对话',icon:'chat'},
 {key:'knowledge',tab:'knowledge',label:'知识库',icon:'book'},
 {key:'capabilities',tab:'capabilities',label:'工具技能',icon:'settings'},
 {key:'eval',tab:'eval',label:'评测',icon:'check'},
 {key:'mcp',tab:'operations',section:'mcp',label:'MCP',icon:'link'},
 {key:'plans',tab:'operations',section:'plans',label:'任务计划',icon:'check'},
 {key:'schedules',tab:'operations',section:'schedules',label:'定时任务',icon:'clock'},
 {key:'usage',tab:'operations',section:'usage',label:'运行统计',icon:'chart'},
 {key:'memories',tab:'operations',section:'memories',label:'记忆来源',icon:'history'},
]
function navigate(item:typeof navigation[number]){tab.value=item.tab;if(item.section)operationsSection.value=item.section}
const sessions = ref<SessionItem[]>([])
const activeSessionId = ref<string>('')
interface Project {id:string;name:string;root:string;previewOrigin:string}
const projects=ref<Project[]>([])
const draftProjectId=ref('')
const activeSessionProjectId=ref('')
const collapsedProjects=ref<string[]>([])
const projectManager=ref<InstanceType<typeof ProjectPicker>|null>(null)
const activeProjectId=computed(()=>activeSessionId.value?activeSessionProjectId.value:draftProjectId.value)
const activeProjectName=computed(()=>projects.value.find(p=>p.id===activeProjectId.value)?.name||'')
const sessionGroups=computed(()=>[
  ...projects.value.filter(p=>!query.value||p.name.toLowerCase().includes(query.value.toLowerCase())||sessions.value.some(s=>s.projectId===p.id)).map(p=>({id:p.id,name:p.name,root:p.root,items:sessions.value.filter(s=>s.projectId===p.id)})),
  {id:'',name:archived.value?'已归档的普通会话':'普通会话',root:'',items:sessions.value.filter(s=>!s.projectId)},
])
let projectListVersion=0
async function refreshProjects(){const version=++projectListVersion;try{const result=await api.get<{projects:Project[]}>('/api/projects');if(version===projectListVersion)projects.value=result.projects}catch{/* Connection status is reported by the session request. */}}
function pickProject(id:string){draftProjectId.value=id;activeSessionId.value='';tab.value='chat';if(collapsedProjects.value.includes(id))collapsedProjects.value=collapsedProjects.value.filter(p=>p!==id)}
function toggleProject(id:string){collapsedProjects.value=collapsedProjects.value.includes(id)?collapsedProjects.value.filter(p=>p!==id):[...collapsedProjects.value,id]}
const backendUp = ref<boolean | null>(null)
const showSettings = ref(false)
const showModelSettings = ref(false)
function modelsChanged() { window.dispatchEvent(new Event('agent:model-settings-changed')) }
const query = ref('')
const archived = ref(false)
const menuId = ref('')
const sessionError = ref('')
const busy = ref(false)
const chatView = ref<InstanceType<typeof ChatView> | null>(null)
const renameId = ref('')
const renameTitle = ref('')
const confirmAction = ref<{ id?: string; title: string } | null>(null)
let searchTimer: ReturnType<typeof setTimeout> | undefined
let listVersion = 0
const theme = ref<'dark' | 'light'>(
  localStorage.getItem('agentops-theme') === 'dark' ? 'dark' : 'light',
)

function applyTheme(t: 'dark' | 'light') {
  theme.value = t
  document.documentElement.dataset.theme = t
  localStorage.setItem('agentops-theme', t)
  window.desktopEnv?.setWindowTheme?.(t)
}

function toggleTheme() {
  applyTheme(theme.value === 'dark' ? 'light' : 'dark')
}
const profileName=ref(localStorage.getItem('agentops-profile-name')||'个人工作空间')
function openProfile(){menuId.value='';renameId.value='';confirmAction.value=null;showSettings.value=true}

async function refreshSessions() {
  const version = ++listVersion
  try {
    const result = await listSessions(query.value, archived.value)
    if (version !== listVersion) return
    sessions.value = result
    const current=result.find(s=>s.sessionId===activeSessionId.value)
    if(current)activeSessionProjectId.value=current.projectId||''
    backendUp.value = true
  } catch {
    if (version === listVersion) backendUp.value = false
  }
}

async function newSession(projectId:string|null=null) {
  try {
    const id = await createSession('新会话',projectId)
    draftProjectId.value=projectId||''
    collapsedProjects.value=collapsedProjects.value.filter(p=>p!==projectId)
    query.value = ''
    archived.value = false
    void refreshSessions()
    activeSessionId.value = id
    activeSessionProjectId.value=projectId||''
    tab.value = 'chat'
    return id
  } catch (e) {
    alert('新建会话失败：' + (e as Error).message)
    return null
  }
}

function pickSession(id: string) {
  activeSessionProjectId.value=sessions.value.find(s=>s.sessionId===id)?.projectId||''
  activeSessionId.value = id
  tab.value = 'chat'
  menuId.value = ''
}

async function openOperationsSession(id:string){
  await refreshSessions()
  try{const s=await api.get<SessionItem>(`/api/sessions/${id}`);activeSessionProjectId.value=s.projectId||'';activeSessionId.value=id;tab.value='chat'}catch(e){sessionError.value=(e as Error).message}
}
async function startSuggested(text: string, mode: RunMode = 'chat', submit = false) {
  if(!await newSession(activeProjectId.value||null))return
  await nextTick()
  chatView.value?.fillDraft(text, mode, submit)
}

async function organize(id: string, changes: { title?: string; pinned?: boolean; archived?: boolean }) {
  if (busy.value) return
  busy.value = true
  sessionError.value = ''
  try {
    await updateSession(id, changes)
    renameId.value = ''
    menuId.value = ''
    if (changes.archived != null && activeSessionId.value === id) activeSessionId.value = ''
    await refreshSessions()
  } catch (e) { sessionError.value = (e as Error).message }
  finally { busy.value = false }
}

async function confirmDelete() {
  const action = confirmAction.value
  if (!action || busy.value) return
  busy.value = true
  sessionError.value = ''
  try {
    if (action.id) await deleteSession(action.id)
    else await clearSessions()
    if (!action.id || activeSessionId.value === action.id) activeSessionId.value = ''
    confirmAction.value = null
    menuId.value = ''
    await refreshSessions()
  } catch (e) { sessionError.value = (e as Error).message; confirmAction.value = null }
  finally { busy.value = false }
}

function sessionTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : ''
}

function saveProfile(value:{name:string;theme:'light'|'dark';tenantId:string;userId:string}) {
  profileName.value=value.name;localStorage.setItem('agentops-profile-name',value.name);applyTheme(value.theme)
  showSettings.value = false
  if(value.tenantId===identity.tenantId&&value.userId===identity.userId)return
  saveIdentity(value.tenantId,value.userId)
  activeSessionId.value = ''
  activeSessionProjectId.value='';query.value='';archived.value=false
  sessions.value = []
  projects.value=[]
  draftProjectId.value=''
  modelsChanged()
  refreshSessions()
  refreshProjects()
  window.dispatchEvent(new Event('agent:identity-changed'))
}

watch([query, archived], () => {
  listVersion++
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void refreshSessions(), 200)
})
let statusTimer: ReturnType<typeof setInterval> | undefined
let lastSidebarRefresh = 0
function refreshVisibleSessions(force = false) {
  const running = sessions.value.some(s => ['queued','running','retry_wait','cancel_requested'].includes(s.activeRunStatus || ''))
  const interval = running ? 3000 : 12000
  if (!document.hidden && (force || Date.now() - lastSidebarRefresh >= interval)) {
    lastSidebarRefresh = Date.now(); void refreshSessions()
  }
}
function focusSessions() { refreshVisibleSessions(true) }
onBeforeUnmount(() => {
  listVersion++; projectListVersion++; clearTimeout(searchTimer); clearInterval(statusTimer)
  window.removeEventListener('agent:projects-changed',refreshProjects)
  document.removeEventListener('visibilitychange',focusSessions)
  window.removeEventListener('focus',focusSessions)
})
onMounted(()=>{
  refreshSessions();refreshProjects();window.addEventListener('agent:projects-changed',refreshProjects)
  statusTimer=setInterval(() => refreshVisibleSessions(),3000)
  document.addEventListener('visibilitychange',focusSessions)
  window.addEventListener('focus',focusSessions)
})
</script>

<template>
  <div class="shell" :class="{ 'full-page': tab !== 'chat' }">
    <nav class="tabs function-rail" aria-label="功能导航">
      <span class="rail-logo" title="AgentOps"><AppIcon name="spark" :size="24" /></span>
      <button v-for="item in navigation" :key="item.key" class="tab rail-item" :class="{on:tab===item.tab&&(!item.section||operationsSection===item.section)}" :aria-label="item.label" :aria-current="tab===item.tab&&(!item.section||operationsSection===item.section)?'page':undefined" :title="item.label" @click="navigate(item)"><AppIcon :name="item.icon" :size="20" /><span>{{ item.label }}</span></button>
      <div class="rail-bottom"><NotificationBell @open="operationsSection='notifications';tab='operations'" /></div>
    </nav>
    <!-- ============ 顶栏：运控台铭牌 ============ -->
    <header class="topbar">
      <div class="brand">
        <AppIcon name="chat" :size="17" />
        <span class="brand-name">工作空间</span>
        <span class="brand-sub">/</span>
        <span class="brand-sub">{{ tab === 'chat' ? '对话' : tab === 'knowledge' ? '知识与记忆' : tab === 'capabilities' ? '工具与技能' : tab === 'operations' ? '工作台' : '评测' }}</span>
      </div>



      <div class="top-right">

        <span
          class="badge"
          :class="backendUp === true ? 'ok' : backendUp === false ? 'bad' : ''"
          :title="backendUp ? '后端 127.0.0.1:8080 可达' : '后端不可达，请先启动 agent-server'"
        >
          {{ backendUp === true ? '后端在线' : backendUp === false ? '后端离线' : '检测中' }}
        </span>
        <button
          class="btn btn-ghost btn-sm theme-toggle"
          :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
          :title="theme === 'dark' ? '切换到浅色（白天）模式' : '切换到深色（黑夜）模式'"
          @click="toggleTheme"
        >
          <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" :size="17" />
        </button>
        <button class="btn btn-ghost btn-sm settings-toggle" title="个人设置" aria-label="个人设置" @click="openProfile"><AppIcon name="user" :size="17" /></button>
      </div>
    </header>
    <ModelSettings v-if="showModelSettings" @close="showModelSettings = false" @saved="modelsChanged" />

    <ProfileSettings v-if="showSettings" :name="profileName" :theme="theme" @close="showSettings=false" @save="saveProfile" @models="showSettings=false;showModelSettings=true" @clear="showSettings=false;confirmAction={title:'清空当前工作空间的全部会话'}" />

    <!-- ============ 主体三分割 ============ -->
    <div class="body">
      <!-- 侧栏：会话 -->
      <aside v-if="tab === 'chat'" class="sidebar" aria-label="会话列表">
        <div class="sidebar-brand"><span class="logo-tile"><AppIcon name="spark" :size="23" /></span><span>AgentOps<small>你的智能工作伙伴</small></span><span class="workspace-mark">个人</span></div>
        <div class="side-head">
          <button class="btn btn-primary new-chat" @click="newSession()"><AppIcon name="plus" :size="17" />新建普通会话<span class="new-chat-hint">NEW</span></button>
        </div>
        <div class="session-tools">
          <div class="search-wrap"><AppIcon name="search" :size="16" /><input v-model="query" class="field" type="search" placeholder="搜索会话…" aria-label="搜索会话" /></div>
          <div class="session-tabs">
            <button class="btn btn-sm" :class="{ selected: !archived }" @click="archived = false">最近对话 <span v-if="sessions.length && !archived" class="session-count">{{ sessions.length }}</span></button>
            <button class="btn btn-sm" :class="{ selected: archived }" @click="archived = true">已归档</button>
          </div>
        </div>
        <p v-if="sessionError" class="session-error small" role="alert">{{ sessionError }}</p>
        <ul class="session-list">
          <li class="projects-heading"><span>项目</span><button class="btn btn-ghost btn-sm add-project" title="选择项目文件夹" aria-label="选择项目文件夹" @click="projectManager?.show()"><AppIcon name="plus" :size="15" /></button></li>
          <li v-if="!projects.length" class="project-empty">选择一个文件夹，在项目下开始会话。</li>
          <template v-for="group in sessionGroups" :key="group.id">
          <li v-if="group.id" class="project-group" :class="{selected:activeProjectId===group.id}">
            <button class="project-fold" :aria-expanded="!collapsedProjects.includes(group.id)" :aria-label="'展开或折叠 '+group.name" @click="toggleProject(group.id)"><AppIcon name="chevron" :size="11" :class="{expanded:!collapsedProjects.includes(group.id)}" /></button>
            <button class="project-name" :title="group.root" @click="pickProject(group.id)"><AppIcon name="folder" :size="16" /><span>{{ group.name }}</span></button>
            <button class="btn btn-ghost btn-sm project-new" :aria-label="'在 '+group.name+' 下新建会话'" title="在项目下新建会话" @click="newSession(group.id)"><AppIcon name="plus" :size="14" /></button>
          </li>
          <li v-else class="projects-heading recent-heading">{{ group.name }}</li>
          <template v-if="!group.id||!collapsedProjects.includes(group.id)">
          <li v-for="s in group.items" :key="s.sessionId" class="session-row" :class="{'project-session':!!group.id}">
            <button
              class="session-item"
              :data-session-id="s.sessionId"
              :class="{ on: s.sessionId === activeSessionId }"
              @click="pickSession(s.sessionId)"
            >
              <span class="session-label-row"><span class="session-title">{{ s.pinned ? '📌 ' : '' }}{{ s.title || '新会话' }}</span>
                <SessionRunIndicator :status="s.activeRunStatus" /></span>
              <span class="session-time mono dim">{{ sessionTime(s.updatedAt) }}</span>
            </button>
            <button class="session-more btn btn-ghost btn-sm" :aria-label="'管理会话 ' + s.title"
              :aria-expanded="menuId === s.sessionId" @click="menuId = menuId === s.sessionId ? '' : s.sessionId">⋯</button>
            <div v-if="menuId === s.sessionId" class="session-menu">
              <button @click="renameId=s.sessionId; renameTitle=s.title; menuId=''">重命名</button>
              <button :disabled="busy" @click="organize(s.sessionId, { pinned: !s.pinned })">{{ s.pinned ? '取消置顶' : '置顶' }}</button>
              <button :disabled="busy" @click="organize(s.sessionId, { archived: !s.archived })">{{ s.archived ? '恢复会话' : '归档' }}</button>
              <button class="danger-text" @click="confirmAction={id:s.sessionId,title:'删除「'+s.title+'」？'}">删除会话</button>
            </div>
          </li>
          <li v-if="group.id&&!group.items.length" class="project-empty project-session">{{ query ? '无匹配会话' : '点击 ＋ 新建项目会话' }}</li>
          </template>
          </template>
          <li v-if="sessions.length === 0" class="empty small dim">
            <AppIcon :name="query ? 'search' : 'chat'" :size="24" />
            {{ query ? '未找到匹配会话' : archived ? '暂无归档会话' : '暂无会话，点击「＋ 新建」开始' }}
          </li>
        </ul>
        <div class="project-manager"><ProjectPicker ref="projectManager" :disabled="false" /></div>
        <div class="sidebar-footer"><button class="profile-main" aria-label="打开个人设置" @click="openProfile"><span class="profile-avatar">{{ [...profileName][0] }}</span><span class="profile-copy">{{ profileName }}<small>个人设置与工作空间</small></span></button><button class="btn btn-ghost btn-sm clear-history" @click="openProfile" title="个人设置" aria-label="更多个人设置">⋯</button></div>
      </aside>

      <!-- 主工作区 -->
      <main class="work">
        <ChatView
          ref="chatView"
          v-if="tab === 'chat'"
          :session-id="activeSessionId"
          :project-id="activeProjectId"
          :project-name="activeProjectName"
          @sessions-changed="refreshSessions"
          @start-task="startSuggested"
          @configure-model="showModelSettings = true"
        />
        <KnowledgeView v-else-if="tab === 'knowledge'" />
        <EvalView v-else-if="tab === 'eval'" />
        <OperationsView v-else-if="tab === 'operations'" :key="operationsSection" :initial-section="operationsSection" @open-session="openOperationsSession" />
        <CapabilitiesView v-else />
      </main>
    </div>
    <div v-if="renameId || confirmAction" class="modal-backdrop" @click.self="renameId=''; confirmAction=null">
      <form v-if="renameId" class="dialog card" @submit.prevent="organize(renameId,{title:renameTitle})">
        <h3>重命名会话</h3>
        <input v-model="renameTitle" class="field" maxlength="120" aria-label="会话名称" autofocus />
        <p v-if="sessionError" class="session-error small" role="alert">{{ sessionError }}</p>
        <div class="dialog-actions"><button type="button" class="btn" @click="renameId=''">取消</button>
          <button class="btn btn-primary" :disabled="busy || !renameTitle.trim()">保存</button></div>
      </form>
      <div v-else-if="confirmAction" class="dialog card" role="alertdialog" aria-modal="true">
        <h3>{{ confirmAction.title }}</h3>
        <p class="small muted">删除后无法从列表恢复。知识库和长期记忆会保留；未结束的任务需要先停止。</p>
        <div class="dialog-actions"><button class="btn" @click="confirmAction=null">取消</button>
          <button class="btn btn-danger" :disabled="busy" @click="confirmDelete">确认删除</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shell { display:grid; grid-template-columns:68px 248px minmax(0,1fr); grid-template-rows:66px minmax(0,1fr); height:100vh; }
.body { display:contents; }
.topbar { grid-column:3; grid-row:1; display:flex; align-items:center; gap:20px; padding:0 28px; border-bottom:1px solid var(--line); background:var(--ink-0); }
.brand { display:flex; align-items:center; gap:10px; color:var(--txt-3); }
.brand-name { font-size:12px; color:var(--txt-2); font-weight:500; }
.brand-sub { font-size:12px; }
.tabs { display:flex; gap:4px; margin-left:auto; padding:4px; border-radius:10px; background:var(--ink-1); }
.tab { display:flex; align-items:center; gap:7px; border:0; border-radius:7px; background:transparent; padding:6px 12px; color:var(--txt-2); cursor:pointer; font-size:12px; }
.tab:hover { color:var(--txt-1); }
.tab.on { background:var(--ink-2); color:var(--txt-1); box-shadow:0 1px 4px #2030270b; }
.top-right { display:flex; align-items:center; gap:7px; }
.top-right .badge { background:transparent; color:var(--txt-3); padding:4px 2px; margin-right:7px; }
.top-right .badge.ok { color:var(--green); }
.theme-toggle,.settings-toggle { padding:7px; }
.sidebar { grid-column:2; grid-row:1 / 3; display:flex; flex-direction:column; min-height:0; background:var(--ink-1); border-right:1px solid var(--line); padding:0 14px; }
.sidebar-brand { display:flex; align-items:center; gap:10px; height:82px; flex-shrink:0; padding:0 9px; font-size:16px; font-weight:650; letter-spacing:-.4px; }
.logo-tile { display:grid; place-items:center; width:36px; height:36px; background:var(--amber-fill); color:var(--on-amber); border-radius:11px; }
.sidebar-brand small { display:block; font-size:10px; font-weight:400; color:var(--txt-3); letter-spacing:1px; margin-top:1px; }
.workspace-mark { margin-left:auto; font-size:10px; color:var(--txt-3); border:1px solid var(--line-strong); border-radius:5px; padding:1px 5px; font-weight:400; }
.side-head { padding:5px 3px 13px; }
.new-chat { width:100%; justify-content:flex-start; height:40px; padding:9px 13px; box-shadow:0 3px 6px #24433012; font-size:12px; }
.new-chat-hint { margin-left:auto; font-size:9px; opacity:.5; letter-spacing:1px; font-weight:400; }
.session-tools { padding:0 3px; }
.search-wrap { position:relative; display:flex; align-items:center; color:var(--txt-3); }
.search-wrap svg { position:absolute; left:12px; pointer-events:none; }
.search-wrap .field { border-color:var(--line); background:transparent; height:36px; padding-left:36px; font-size:11px; }
.session-tabs { display:flex; align-items:center; justify-content:space-between; margin:22px 0 10px; }
.session-tabs .btn { background:transparent; border:0; color:var(--txt-3); padding:4px 6px; font-size:11px; }
.session-tabs .selected { color:var(--txt-1); font-weight:600; }
.session-count { color:var(--txt-3); font-size:10px; margin-left:2px; }
.session-list { list-style:none; flex:1; min-height:0; padding:0 2px; margin:0; overflow:auto; }
.session-row { position:relative; }
.projects-heading {display:flex;align-items:center;justify-content:space-between;color:var(--txt-3);font-size:11px;padding:10px 7px 7px;min-height:34px}
.recent-heading{margin-top:14px;border-top:1px solid var(--line);padding-top:18px}
.project-group{display:flex;align-items:center;gap:3px;border-radius:8px;margin:3px 0;color:var(--txt-2);min-height:36px}
.project-group.selected{color:var(--txt-1);background:var(--ink-3)}
.project-fold,.project-name{border:0;background:transparent;color:inherit;cursor:pointer;padding:7px 2px;display:flex;align-items:center;gap:8px;min-width:0}
.project-name{flex:1;font-size:12px;text-align:left}.project-name span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-name svg{flex-shrink:0}
.project-fold svg{transition:transform .15s}.project-fold .expanded{transform:rotate(90deg)}
.project-new{opacity:.6;padding:5px}.project-group:hover .project-new{opacity:1}.project-session{margin-left:23px}.project-session .session-item{padding-top:8px;padding-bottom:8px}.project-session .session-time{display:none}
.project-empty{font-size:11px;color:var(--txt-3);line-height:1.7;padding:8px 10px 12px}.project-manager{height:0;overflow:visible}.project-manager :deep(.project-picker>.project-toggle){display:none}
.session-item { display:flex; flex-direction:column; align-items:flex-start; gap:5px; width:100%; border:0; border-radius:9px; background:transparent; color:var(--txt-1); padding:11px 31px 11px 12px; margin-bottom:3px; cursor:pointer; text-align:left; }
.session-item:hover { background:var(--ink-3); }
.session-item.on { background:var(--amber-soft); }
.session-title { font-size:12px; width:100%; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.session-label-row{display:flex;align-items:center;gap:8px;min-width:0;width:100%}.session-label-row .session-title{flex:1;min-width:0;width:auto}
.session-time { color:var(--txt-3); font-size:10px; font-family:var(--font-ui); }
.session-more { position:absolute; top:10px; right:3px; opacity:0; }
.session-row:hover .session-more,.session-row:focus-within .session-more { opacity:1; }
.session-menu { position:relative; z-index:2; display:grid; padding:5px; margin:3px 0 8px; border:1px solid var(--line); border-radius:10px; background:var(--ink-2); box-shadow:var(--shadow-2); }
.session-menu button { padding:8px 10px; border:0; border-radius:6px; background:transparent; color:var(--txt-1); text-align:left; cursor:pointer; font-size:12px; }
.session-menu button:hover { background:var(--ink-1); }
.empty { display:flex; flex-direction:column; align-items:center; gap:12px; padding:43px 14px; font-size:11px; text-align:center; color:var(--txt-3); }
.empty svg { opacity:.5; }
.sidebar-footer { flex-shrink:0; display:flex; align-items:center; gap:10px; padding:18px 5px; border-top:1px solid var(--line); }
.profile-avatar { display:grid; place-items:center; width:30px; height:30px; border-radius:50%; background:var(--line-strong); color:var(--txt-2); font-size:12px; }
.profile-copy { font-size:11px; font-weight:500; }
.profile-copy small { display:block; font-size:10px; color:var(--txt-3); font-weight:400; margin-top:2px; }
.profile-main{display:flex;align-items:center;gap:10px;flex:1;min-width:0;border:0;background:transparent;color:var(--txt-1);padding:6px 0;text-align:left;cursor:pointer;border-radius:8px}.profile-main:hover{background:var(--ink-1)}.profile-copy{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.profile-avatar{flex-shrink:0}
.clear-history { margin-left:auto; font-size:20px; padding:0 5px; }
.work { grid-column:3; grid-row:2; display:flex; flex-direction:column; min-width:0; min-height:0; background:var(--work-bg); }
.modal-backdrop { position:fixed; inset:0; z-index:30; display:grid; place-items:center; background:#17241a50; backdrop-filter:blur(4px); }
.dialog { width:min(440px,90vw); padding:28px; box-shadow:var(--shadow-2); }
.dialog h3 { font-size:18px; font-weight:600; margin:0 0 20px; }
.dialog-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:24px; }
.danger-text,.session-error { color:var(--red)!important; }
.session-error { padding:0 8px; }
.shell.full-page { grid-template-columns:68px minmax(0,1fr); }
.full-page .topbar,.full-page .work { grid-column:2; }
@media(max-width:1150px) { .shell { grid-template-columns:64px 220px minmax(0,1fr); } .shell.full-page{grid-template-columns:64px minmax(0,1fr)} .topbar { padding:0 20px; } }

.top-right{margin-left:auto}.tabs.function-rail{grid-column:1;grid-row:1 / 3;display:flex;flex-direction:column;align-items:center;gap:5px;margin:0;padding:18px 6px 12px;border-radius:0;border-right:1px solid var(--line);background:var(--ink-1);overflow-y:auto;min-height:0;z-index:3}.rail-logo{display:grid;place-items:center;color:var(--green);height:42px;margin-bottom:10px;flex-shrink:0}.function-rail .rail-item{display:flex;flex-direction:column;justify-content:center;gap:6px;width:54px;min-height:54px;padding:8px 2px;border-radius:10px;color:var(--txt-3);font-size:9px;white-space:nowrap;flex-shrink:0}.function-rail .rail-item.on{background:var(--amber-soft);color:var(--green);box-shadow:none}.function-rail .rail-item:hover{background:var(--ink-3);color:var(--txt-1)}.rail-bottom{margin-top:auto;padding-top:16px}.rail-bottom :deep(.notice-bell){flex-direction:column;gap:5px;font-size:10px;padding:7px 4px}
</style>
