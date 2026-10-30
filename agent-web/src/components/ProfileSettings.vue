<script setup lang="ts">
import {nextTick,onBeforeUnmount,onMounted,ref} from 'vue'
import AppIcon from './AppIcon.vue'
import {identity} from '../api'
const props=defineProps<{name:string;theme:'light'|'dark'}>()
const emit=defineEmits<{close:[];save:[value:{name:string;theme:'light'|'dark';tenantId:string;userId:string}];models:[];clear:[]}>()
const name=ref(props.name),theme=ref(props.theme),tenant=ref(identity.tenantId),user=ref(identity.userId),error=ref('')
const panel=ref<HTMLElement|null>(null),nameInput=ref<HTMLInputElement|null>(null)
const previous=document.activeElement as HTMLElement|null
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function save(){
 if(!name.value.trim()){error.value='请填写显示名称。';nameInput.value?.focus();return}
 if(!uuid.test(tenant.value.trim())||!uuid.test(user.value.trim())){error.value='工作空间标识必须是完整的 UUID，请检查开发者设置。';return}
 emit('save',{name:name.value.trim().slice(0,24),theme:theme.value,tenantId:tenant.value.trim().toLowerCase(),userId:user.value.trim().toLowerCase()})
}
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
   <header><span class="profile-symbol"><AppIcon name="user" :size="24" /></span><div><h2 id="profile-title">个人设置</h2><p>管理本机资料与工作空间</p></div><button class="btn btn-ghost" aria-label="关闭个人设置" @click="emit('close')"><AppIcon name="close" /></button></header>
   <form @submit.prevent="save">
    <label for="profile-name">显示名称</label><input id="profile-name" ref="nameInput" v-model="name" class="field" maxlength="24" autocomplete="nickname" />
    <p class="help">名称仅显示在本机，不会创建或切换登录账号。</p>
    <label for="profile-theme">外观</label><select id="profile-theme" v-model="theme" class="field"><option value="light">浅色</option><option value="dark">深色</option></select>
    <button type="button" class="profile-link" @click="emit('models')"><span><strong>聊天模型</strong><small>管理服务地址、Key 和模型名称</small></span><AppIcon name="chevron" :size="16" /></button>
    <details class="developer-settings"><summary>开发者设置 · 工作空间标识</summary><p class="help">仅用于本地联调。修改后切换会话和项目数据视图，已有数据保留，正在运行的任务不会因此停止。</p>
     <label for="profile-tenant">Tenant ID</label><input id="profile-tenant" v-model="tenant" class="field mono" spellcheck="false" />
     <label for="profile-user">User ID</label><input id="profile-user" v-model="user" class="field mono" spellcheck="false" />
    </details>
    <p v-if="error" class="profile-error" role="alert">{{ error }}</p>
    <footer><button class="btn btn-ghost history-manage" type="button" @click="emit('clear')">清空会话…</button><button type="button" class="btn" @click="emit('close')">取消</button><button class="btn btn-primary" type="submit">保存设置</button></footer>
   </form>
  </section>
 </div></Teleport>
</template>
<style scoped>
.profile-backdrop{position:fixed;inset:0;z-index:1100;background:#17251d50;backdrop-filter:blur(5px);display:flex;align-items:center;justify-content:center;padding:24px}
.profile-dialog{width:min(520px,100%);max-height:calc(100dvh - 48px);overflow:auto;background:var(--ink-0,#fff);color:var(--txt-1);border:1px solid var(--line);border-radius:22px;box-shadow:0 24px 90px #13261b26;padding:30px}
header{display:flex;align-items:center;gap:14px;margin-bottom:28px}header>.btn{margin-left:auto}.profile-symbol{display:grid;place-items:center;width:46px;height:46px;border-radius:14px;background:var(--green-soft);color:var(--green)}h2{margin:0;font-size:21px}header p{margin:6px 0 0;font-size:12px;color:var(--txt-3)}label{display:block;font-size:12px;margin:20px 0 9px}.field{width:100%}.help{font-size:11px;line-height:1.8;color:var(--txt-3);margin:9px 0}.profile-link{display:flex;align-items:center;justify-content:space-between;width:100%;background:transparent;color:inherit;border:1px solid var(--line);border-radius:12px;padding:16px;margin:24px 0;cursor:pointer;text-align:left}.profile-link strong{display:block;font-size:13px;font-weight:500}.profile-link small{display:block;font-size:11px;color:var(--txt-3);margin-top:6px}.profile-link:hover{background:var(--ink-1)}.developer-settings{border-top:1px solid var(--line);padding-top:18px;font-size:11px;color:var(--txt-3)}summary{cursor:pointer}.profile-error{background:#fceeee;color:#b53333;padding:12px;border-radius:8px;font-size:12px}footer{display:flex;gap:9px;border-top:1px solid var(--line);margin-top:25px;padding-top:20px;align-items:center}.history-manage{margin-right:auto;font-size:11px;color:var(--txt-3)}
</style>
