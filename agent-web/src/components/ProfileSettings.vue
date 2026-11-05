<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import AppIcon from './AppIcon.vue'
import {api} from '../api'
import {useAccount,type LoginDevice} from '../composables/useAccount'
const props=defineProps<{name:string;theme:'light'|'dark'}>()
const emit=defineEmits<{close:[];save:[value:{name:string;theme:'light'|'dark'}];models:[];clear:[]}>()
const {account,updateName,changePassword,logout}=useAccount()
const name=ref(props.name),theme=ref(props.theme),error=ref(''),notice=ref(''),busy=ref(false),section=ref('profile')
const currentPassword=ref(''),newPassword=ref(''),confirmation=ref(''),devices=ref<LoginDevice[]>([]),loadingDevices=ref(false)
const tabs=[{id:'profile',name:'个人资料',icon:'user'},{id:'appearance',name:'偏好设置',icon:'settings'},{id:'security',name:'账号安全',icon:'check'},{id:'data',name:'数据管理',icon:'folder'}]
const initial=computed(()=>[...(account.value?.displayName||'A')][0])
const panel=ref<HTMLElement|null>(null),nameInput=ref<HTMLInputElement|null>(null)
const previous=document.activeElement as HTMLElement|null
async function action(task:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await task()}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function save(){
 if(!name.value.trim()){error.value='请填写显示名称。';nameInput.value?.focus();return}
 await action(async()=>{await updateName(name.value.trim());emit('save',{name:name.value.trim(),theme:theme.value});notice.value='个人资料已保存。'})
}
async function loadDevices(){loadingDevices.value=true;try{devices.value=(await api.get<{sessions:LoginDevice[]}>('/api/auth/sessions')).sessions}catch(e){error.value=(e as Error).message}finally{loadingDevices.value=false}}
function password(){if(newPassword.value!==confirmation.value){error.value='两次输入的新密码不一致。';return}void action(async()=>{await changePassword(currentPassword.value,newPassword.value);currentPassword.value='';newPassword.value='';confirmation.value=''})}
async function revoke(device:LoginDevice){await action(async()=>{if(device.current){await logout();return}await api.del('/api/auth/sessions/'+device.id);await loadDevices();notice.value='该设备已退出登录。'})}
function date(value?:string){return value?new Date(value).toLocaleString('zh-CN',{dateStyle:'medium',timeStyle:'short'}):'—'}
watch(section,async value=>{error.value='';notice.value='';if(value==='security')await loadDevices()})
function keydown(e:KeyboardEvent){if(e.key==='Escape'){e.preventDefault();emit('close')}
 if(e.key!=='Tab'||!panel.value)return
 const controls=[...panel.value.querySelectorAll<HTMLElement>('button,input,select,summary,[tabindex="0"]')].filter(el=>!el.hasAttribute('disabled')&&el.getClientRects().length&&(!el.closest('details:not([open])')||el.matches('summary')))
 const first=controls[0],last=controls.at(-1)
 if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus()}
 else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus()}
}
onMounted(async()=>{document.addEventListener('keydown',keydown);await nextTick();nameInput.value?.focus()})
onBeforeUnmount(()=>{document.removeEventListener('keydown',keydown);if(previous?.isConnected)previous.focus()})
</script>
<template>
 <Teleport to="body"><div class="profile-backdrop" @click.self="emit('close')">
  <section ref="panel" class="profile-dialog" role="dialog" aria-modal="true" aria-labelledby="profile-title">
   <header><span class="profile-symbol">{{ initial }}</span><div><h2 id="profile-title">个人中心</h2><p>{{ account?.displayName }} · @{{ account?.username }}</p></div><button class="btn btn-ghost" aria-label="关闭个人设置" @click="emit('close')"><AppIcon name="close" /></button></header>
   <div class="profile-layout"><nav aria-label="个人中心栏目"><button v-for="item in tabs" :key="item.id" :class="{selected:section===item.id}" :aria-current="section===item.id?'page':undefined" :disabled="busy" @click="section=item.id"><AppIcon :name="item.icon" :size="16" />{{ item.name }}</button><button class="logout" :disabled="busy" @click="action(()=>logout())">退出登录</button></nav>
   <div class="profile-content">
    <form v-if="section==='profile'" @submit.prevent="save"><h3>个人资料</h3><p class="help">你的称呼会在工作空间中显示。</p><label for="profile-name">显示名称</label><input id="profile-name" ref="nameInput" v-model="name" class="field" maxlength="24" autocomplete="nickname" required :disabled="busy" /><label>用户名</label><p class="readonly">{{ account?.username }}</p><label>注册时间</label><p class="readonly">{{ date(account?.createdAt) }}</p><footer><button class="btn btn-primary" type="submit" :disabled="busy">{{ busy?'正在保存…':'保存资料' }}</button></footer></form>
    <section v-else-if="section==='appearance'"><h3>偏好设置</h3><p class="help">外观偏好保存在当前设备。</p><label for="profile-theme">外观</label><select id="profile-theme" v-model="theme" class="field"><option value="light">浅色</option><option value="dark">深色</option></select><button class="btn btn-primary preference-save" @click="emit('save',{name:account?.displayName||name,theme});notice='偏好设置已保存。'">保存偏好</button><button type="button" class="profile-link" @click="emit('models')"><span><strong>聊天模型</strong><small>选择或管理多个模型服务</small></span><AppIcon name="chevron" :size="16" /></button><p class="help">快捷键：Ctrl / ⌘ + K 搜索会话，Ctrl / ⌘ + Shift + O 开始新对话。</p></section>
    <section v-else-if="section==='security'"><h3>账号安全</h3><form @submit.prevent="password"><p class="help">修改密码后，所有设备都会退出登录。后台任务会保留。</p><label for="current-password">当前密码</label><input id="current-password" v-model="currentPassword" class="field" type="password" autocomplete="current-password" required maxlength="128" :disabled="busy" /><label for="new-password">新密码</label><input id="new-password" v-model="newPassword" class="field" type="password" autocomplete="new-password" required minlength="10" maxlength="128" :disabled="busy" /><label for="confirm-new-password">确认新密码</label><input id="confirm-new-password" v-model="confirmation" class="field" type="password" autocomplete="new-password" required minlength="10" maxlength="128" :disabled="busy" /><button class="btn preference-save" type="submit" :disabled="busy">修改密码</button></form><div class="devices-title"><h3>已登录设备</h3><button class="btn btn-ghost btn-sm" :disabled="loadingDevices||busy" @click="loadDevices">刷新</button></div><p v-if="loadingDevices" class="help" role="status">正在读取…</p><article v-for="device in devices" :key="device.id" class="device"><div><strong>{{ device.current?'当前设备':'其他设备' }}</strong><span :title="device.device">{{ device.device }}</span><small>最近活动 {{ date(device.lastUsedAt) }}</small></div><button class="btn btn-sm" :disabled="busy" @click="revoke(device)">退出</button></article><button v-if="devices.length>1" class="btn btn-sm preference-save" :disabled="busy" @click="action(()=>logout(true))">退出所有设备</button></section>
    <section v-else><h3>数据管理</h3><p class="help">会话、项目与文件归属于当前账号，切换账号不会删除资料。</p><div class="data-row"><strong>导出会话</strong><p>打开会话，使用顶部“导出对话”，保存 Markdown 文件。</p></div><div class="data-row danger-zone"><strong>清空会话记录</strong><p>删除当前账号的会话记录。执行中的任务需要先停止，操作前会再次确认。</p><button class="btn btn-danger" :disabled="busy" @click="emit('clear')">清空会话…</button></div></section>
    <p v-if="error" class="profile-error" role="alert">{{ error }}</p><p v-if="notice" class="profile-success" role="status">{{ notice }}</p>
   </div></div>
  </section>
 </div></Teleport>
</template>
<style scoped>
.profile-backdrop{position:fixed;inset:0;z-index:1100;background:#17251d50;backdrop-filter:blur(5px);display:flex;align-items:center;justify-content:center;padding:24px}
.profile-dialog{width:min(820px,100%);max-height:calc(100dvh - 48px);overflow:auto;background:var(--ink-0,#fff);color:var(--txt-1);border:1px solid var(--line);border-radius:22px;box-shadow:0 24px 90px #13261b26;padding:30px}
header{display:flex;align-items:center;gap:14px;margin-bottom:28px}header>.btn{margin-left:auto}.profile-symbol{display:grid;place-items:center;width:46px;height:46px;border-radius:14px;background:var(--green-soft);color:var(--green)}h2{margin:0;font-size:21px}header p{margin:6px 0 0;font-size:12px;color:var(--txt-3)}label{display:block;font-size:12px;margin:20px 0 9px}.field{width:100%}.help{font-size:11px;line-height:1.8;color:var(--txt-3);margin:9px 0}.profile-link{display:flex;align-items:center;justify-content:space-between;width:100%;background:transparent;color:inherit;border:1px solid var(--line);border-radius:12px;padding:16px;margin:24px 0;cursor:pointer;text-align:left}.profile-link strong{display:block;font-size:13px;font-weight:500}.profile-link small{display:block;font-size:11px;color:var(--txt-3);margin-top:6px}.profile-link:hover{background:var(--ink-1)}.profile-error{background:#fceeee;color:#b53333;padding:12px;border-radius:8px;font-size:12px}footer{display:flex;gap:9px;border-top:1px solid var(--line);margin-top:25px;padding-top:20px;align-items:center}
.profile-layout{display:grid;grid-template-columns:150px minmax(0,1fr);gap:30px;min-height:400px}.profile-layout nav{border-right:1px solid var(--line);padding-right:16px;display:flex;flex-direction:column;gap:7px}.profile-layout nav button{display:flex;gap:9px;align-items:center;padding:12px;border:0;border-radius:9px;background:transparent;color:var(--txt-2);font-size:12px;text-align:left;cursor:pointer}.profile-layout nav button.selected{background:var(--green-soft);color:var(--green);font-weight:600}.profile-layout nav .logout{margin-top:auto;color:#a13f3f}.profile-content{min-width:0}.profile-content h3{font-size:15px;margin:3px 0 12px}.readonly{font-size:12px;color:var(--txt-2);margin:0}.profile-symbol{font-size:23px;font-weight:600}.preference-save{margin-top:16px}.profile-success{font-size:12px;color:var(--green);padding:12px;background:var(--green-soft);border-radius:9px}.devices-title{display:flex;align-items:center;justify-content:space-between;margin-top:32px}.devices-title h3{margin:0}.device{display:flex;gap:14px;align-items:center;border-bottom:1px solid var(--line);padding:14px 0}.device>div{min-width:0;flex:1}.device strong{font-size:12px;font-weight:500}.device span,.device small{display:block;font-size:10px;color:var(--txt-3);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:370px;margin-top:5px}.data-row{border-top:1px solid var(--line);margin-top:22px;padding-top:20px}.data-row strong{font-size:13px}.data-row p{font-size:12px;line-height:1.8;color:var(--txt-3)}.danger-zone{margin-top:32px}@media(max-width:650px){.profile-layout{grid-template-columns:1fr;gap:20px}.profile-layout nav{flex-direction:row;flex-wrap:wrap;border-right:0;border-bottom:1px solid var(--line);padding:0 0 12px}.profile-layout nav .logout{margin-top:0}.profile-dialog{padding:20px}}
</style>
