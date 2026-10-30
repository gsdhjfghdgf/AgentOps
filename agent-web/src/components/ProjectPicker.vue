<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import AppIcon from './AppIcon.vue'
const props=defineProps<{disabled:boolean;projectId?:string}>()
interface Project {id:string;name:string;root:string;previewOrigin:string}
interface Change {id:string;path:string;status:string;createdAt:string}
const canPick=Boolean(window.desktopEnv?.selectDirectory)
const open=ref(false),projects=ref<Project[]>([]),selected=ref<Project|null>(null),busy=ref(false),error=ref(''),manual=ref(''),origin=ref(''),git=ref(''),diff=ref(''),changes=ref<Change[]>([]),confirmUndo=ref('')
async function refresh(){try{const result=await api.get<{projects:Project[];selected:Project|null}>('/api/projects');projects.value=result.projects;selected.value=props.projectId===undefined?result.selected:result.projects.find(p=>p.id===props.projectId)||null;origin.value=selected.value?.previewOrigin||''}catch(e){error.value=(e as Error).message}}
async function details(){if(!selected.value)return;try{const id=selected.value.id;const [status,patch,history]=await Promise.all([api.get<{text:string;repository:boolean}>(`/api/projects/${id}/git`),api.get<{text:string;repository:boolean}>(`/api/projects/${id}/git?diff=true`),api.get<{changes:Change[]}>(`/api/projects/${id}/changes`)]);git.value=status.repository?status.text||'工作区干净':'此目录尚未初始化Git';diff.value=patch.repository?patch.text:'';changes.value=history.changes}catch(e){error.value=(e as Error).message}}
async function show(){open.value=!open.value;if(open.value){await refresh();await details()}}
async function attach(){busy.value=true;error.value='';try{const directory=window.desktopEnv?.selectDirectory?await window.desktopEnv.selectDirectory():manual.value;if(!directory)return;await api.post('/api/projects',{directory});window.dispatchEvent(new Event('agent:projects-changed'));await refresh();await details()}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function select(id:string){busy.value=true;try{await api.put('/api/projects/selection',{projectId:id||null});await refresh();git.value='';diff.value='';changes.value=[];await details()}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function preview(){if(!selected.value)return;busy.value=true;try{await api.put(`/api/projects/${selected.value.id}/preview-origin`,{origin:origin.value});await refresh()}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function undo(id:string){if(!selected.value)return;busy.value=true;try{await api.post(`/api/projects/${selected.value.id}/changes/${id}/undo`);confirmUndo.value='';await details()}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
watch(()=>props.projectId,refresh)
onMounted(()=>{refresh();window.addEventListener('agent:projects-changed',refresh)})
onBeforeUnmount(()=>window.removeEventListener('agent:projects-changed',refresh))
defineExpose({show})
</script>
<template>
 <div class="project-picker"><button class="project-toggle" :disabled="disabled" @click="show"><AppIcon name="folder" :size="14" />{{ selected?.name || '选择项目' }}<AppIcon name="chevron" :size="10" /></button>
 <div v-if="open" class="project-overlay" @click.self="!busy && (open=false)"><section class="project-dialog">
  <header><div><span class="eyebrow">LOCAL PROJECT</span><h2>项目与 Git</h2></div><button class="btn" :disabled="busy" @click="open=false">关闭</button></header>
  <p>项目内的文件读写直接执行，保留修改记录与撤销功能。项目会话始终使用所属目录。</p>
  <p v-if="projectId!==undefined" class="session-project-note">{{ projectId ? '当前会话已固定到此项目。' : '当前是普通会话，使用专属工作区。' }}需要其他项目时，请从侧栏的项目下新建会话。</p>
  <div class="project-actions"><select class="field" :value="selected?.id||''" :disabled="busy||disabled||projectId!==undefined" @change="select(($event.target as HTMLSelectElement).value)"><option value="">仅使用专属工作区</option><option v-for="p in projects" :key="p.id" :value="p.id">{{ p.name }}</option></select><input v-if="!canPick&&projectId===undefined" v-model="manual" class="field" placeholder="后端所在机器的项目绝对目录" /><button v-if="projectId===undefined" class="btn btn-primary attach-project" :disabled="busy||disabled" @click="attach">添加本地项目</button></div>
  <template v-if="selected"><p class="project-root">{{ selected.root }}</p><label>本机预览地址（可选）</label><div class="project-actions"><input v-model="origin" class="field" placeholder="http://127.0.0.1:5173" /><button class="btn" :disabled="busy||disabled" @click="preview">保存预览源</button></div><small>仅独立浏览器可访问这个预览源；修改后在新任务生效。其他内网地址仍不可访问。</small>
   <h3>Git 状态</h3><pre class="project-output">{{ git }}</pre><details v-if="diff"><summary>查看未暂存差异</summary><pre class="project-output">{{ diff }}</pre></details>
   <h3>Agent 文件修改</h3><p v-if="!changes.length">暂无修改记录。</p><div v-for="change in changes" :key="change.id" class="project-change"><span>{{ change.path }} <small>{{ change.status==='reverted'?'已撤销':change.status==='applied'?'已应用':'需要核对' }}</small></span><button v-if="change.status!=='reverted'" class="btn btn-sm" :disabled="busy" @click="confirmUndo=change.id">撤销此修改</button><div v-if="confirmUndo===change.id"><p>恢复修改前的文件内容；有后续变更时将停止，避免覆盖。</p><button class="btn btn-danger btn-sm" :disabled="busy" @click="undo(change.id)">确认撤销</button><button class="btn btn-sm" @click="confirmUndo=''">取消</button></div></div>
  </template><p v-if="error" class="project-error">{{ error }}</p>
 </section></div></div>
</template>
<style scoped>
.project-toggle{display:flex;align-items:center;gap:6px;border:0;background:transparent;color:var(--txt-2);font:11px var(--font-ui);cursor:pointer;padding:4px;max-width:190px;overflow:hidden;white-space:nowrap}.project-toggle:disabled{opacity:.5;cursor:default}.project-overlay{position:fixed;inset:0;z-index:45;background:#18251b55;backdrop-filter:blur(4px);display:grid;place-items:center;padding:24px}.project-dialog{background:var(--ink-2);border:1px solid var(--line);border-radius:16px;width:min(820px,100%);max-height:90vh;overflow:auto;padding:28px;color:var(--txt-1);font-size:12px}.project-dialog header{display:flex;justify-content:space-between;align-items:center}.project-dialog h2{font-size:22px;margin:8px 0}.project-dialog p{line-height:1.8;color:var(--txt-2)}.project-dialog small{color:var(--txt-3);font-size:10px}.project-actions{display:flex;gap:8px;margin:12px 0}.project-actions .field{flex:1;min-width:0}.project-root{overflow-wrap:anywhere;padding:10px;background:var(--ink-1);border-radius:8px}.project-output{max-height:300px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;background:var(--ink-1);padding:14px;border-radius:8px;font:11px/1.8 var(--font-mono)}.project-change{padding:12px 0;border-bottom:1px solid var(--line);display:flex;align-items:center;gap:12px;flex-wrap:wrap}.project-change>span{flex:1}.project-error{color:var(--red)!important}
</style>
