<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import AppIcon from './AppIcon.vue'
import {useAccount} from '../composables/useAccount'
const {authReady,setupRequired,authError,initialize,login,register}=useAccount()
const registering=ref(false),username=ref(''),name=ref(''),password=ref(''),confirmation=ref(''),visible=ref(false),remember=ref(true),inherit=ref(true),busy=ref(false),error=ref('')
const desktop=!!window.desktopEnv?.saveLoginToken
const registrationKey=ref(''),manualRegistration=ref(!window.desktopEnv?.readRegistrationKey)
const usernameInvalid=ref(false)
const usernameHelp='3～32位中文、字母、数字或 . _ -，以中文或字母开头。'
function invalidUsername(event:Event){event.preventDefault();usernameInvalid.value=true;(event.target as HTMLInputElement).focus()}
watch(setupRequired,value=>{if(value)registering.value=true},{immediate:true})
watch(registering,()=>{error.value='';password.value='';confirmation.value=''})
const heading=computed(()=>setupRequired.value?'创建你的 AgentOps 账号':registering.value?'创建账号':'欢迎回来')
async function submit(){
 if(busy.value)return
 error.value=''
 if(registering.value&&password.value!==confirmation.value){error.value='两次输入的密码不一致。';return}
 busy.value=true
 try{if(registering.value)await register(username.value.trim(),name.value.trim(),password.value,setupRequired.value&&inherit.value,registrationKey.value);else await login(username.value.trim(),password.value,desktop&&remember.value);password.value='';confirmation.value='';registrationKey.value=''}
 catch(e){error.value=(e as Error).message}finally{busy.value=false}
}
</script>
<template>
 <main class="account-screen">
  <section class="account-intro"><div class="account-brand"><AppIcon name="spark" :size="30" /><strong>AgentOps</strong></div><div><p class="eyebrow">YOUR PERSONAL WORKSPACE</p><h1>从一个想法，<br />到一份成果。</h1><p>在自己的项目里思考、执行、交付。<br />资料与任务，都在你的工作空间。</p></div><small>可控执行 · 持续协作 · 成果可追溯</small></section>
  <section class="account-form-area"><div class="account-form">
   <template v-if="!authReady"><AppIcon name="activity" :size="28" /><h2>正在连接工作空间…</h2><p class="muted">请稍候</p></template>
   <template v-else>
    <span class="account-kicker">{{ registering?'开始使用':'登录工作空间' }}</span><h2>{{ heading }}</h2><p class="account-subtitle">{{ registering?'创建账号后即可开始对话和管理项目。':'继续你的任务，所有进度都已保存。' }}</p>
    <p v-if="authError" class="account-notice" role="status">{{ authError }} <button type="button" @click="initialize">重新连接</button></p>
    <form @submit.prevent="submit">
     <label for="account-username">用户名</label><input id="account-username" v-model="username" class="field" autocomplete="username" minlength="3" maxlength="32" pattern="[\p{Script=Han}a-zA-Z][\p{Script=Han}a-zA-Z0-9_.\-]{2,31}" :title="usernameHelp" aria-describedby="account-username-help" :aria-invalid="usernameInvalid||undefined" placeholder="例如：小林同学 或 agent_user" required :disabled="busy" @invalid="invalidUsername" @input="usernameInvalid=false" />
     <p id="account-username-help" class="account-help" :class="{'username-invalid':usernameInvalid}" :role="usernameInvalid?'alert':undefined">{{ usernameInvalid?'请检查用户名：':'' }}{{ usernameHelp }}</p>
     <template v-if="registering"><label for="account-name">显示名称</label><input id="account-name" v-model="name" class="field" autocomplete="nickname" maxlength="24" placeholder="希望我们如何称呼你" required :disabled="busy" /></template>
     <label for="account-password">密码</label><div class="password-field"><input id="account-password" v-model="password" class="field" :type="visible?'text':'password'" :autocomplete="registering?'new-password':'current-password'" :minlength="registering?10:1" maxlength="128" placeholder="输入密码" required :disabled="busy" /><button type="button" :aria-label="visible?'隐藏密码':'显示密码'" :aria-pressed="visible" @click="visible=!visible">{{ visible?'隐藏':'显示' }}</button></div>
     <template v-if="registering"><p class="account-help">至少10位，建议使用不易猜测的长密码。</p><label for="account-confirm">确认密码</label><input id="account-confirm" v-model="confirmation" class="field" type="password" autocomplete="new-password" minlength="10" maxlength="128" placeholder="再次输入密码" required :disabled="busy" /></template>
     <label v-if="!registering&&desktop" class="account-check"><input v-model="remember" type="checkbox" :disabled="busy" />记住本机登录<span>使用系统加密保存</span></label>
     <label v-if="registering&&setupRequired" class="account-check legacy-claim"><input v-model="inherit" type="checkbox" :disabled="busy" /><span>接管本机旧工作空间<small>保留原有会话、项目、知识和模型配置，仅第一个本机账号可选。</small></span></label>
     <template v-if="registering"><button v-if="!manualRegistration" type="button" class="registration-method" @click="manualRegistration=true">其他部署方式</button><template v-else><label for="account-registration-key">注册凭据</label><input id="account-registration-key" v-model="registrationKey" class="field" type="password" autocomplete="off" maxlength="128" placeholder="由部署管理员提供" /><p class="account-help">桌面端会自动读取本机凭据，浏览器不能自行接管本机数据。</p></template></template>
     <p v-if="error" class="account-error" role="alert">{{ error }}</p>
     <button class="btn btn-primary account-submit" type="submit" :disabled="busy">{{ busy?'正在处理…':registering?'创建账号并进入':'登录' }}<AppIcon v-if="!busy" name="chevron" :size="16" /></button>
    </form>
    <p v-if="!setupRequired" class="account-switch">{{ registering?'已有账号？':'第一次使用？' }}<button :disabled="busy" @click="registering=!registering">{{ registering?'返回登录':'创建账号' }}</button></p>
   </template>
  </div></section>
 </main>
</template>
<style scoped>
.account-help.username-invalid{color:#b43b3b}
.registration-method{border:0;background:none;color:var(--txt-3);font-size:10px;cursor:pointer;padding:6px 0}
.account-screen{min-height:100dvh;display:grid;grid-template-columns:minmax(340px,.9fr) minmax(430px,1.1fr);background:var(--ink-0);color:var(--txt-1)}.account-intro{background:#1b352c;color:#f0f4ee;padding:54px 60px;display:flex;flex-direction:column;justify-content:space-between;min-height:640px}.account-brand{display:flex;align-items:center;gap:14px;font-size:23px}.eyebrow{font-size:10px;letter-spacing:.22em;color:#a9c8b4}.account-intro h1{font-size:clamp(38px,4vw,62px);line-height:1.4;letter-spacing:-.03em;margin:22px 0}.account-intro p:not(.eyebrow){font-size:14px;line-height:2.1;color:#bbcdc0}.account-intro>small{font-size:11px;color:#99b09f}.account-form-area{display:grid;place-items:center;padding:40px}.account-form{width:min(380px,100%)}.account-kicker{font-size:11px;letter-spacing:.12em;color:var(--green)}h2{font-size:28px;letter-spacing:-.03em;margin:14px 0}.account-subtitle{font-size:12px;color:var(--txt-3);margin:0 0 28px;line-height:1.8}form>label:not(.account-check){display:block;font-size:12px;margin:18px 0 8px}.field{width:100%;height:44px}.password-field{position:relative}.password-field input{padding-right:60px}.password-field>button{position:absolute;right:12px;top:14px;border:0;background:none;color:var(--txt-3);font-size:11px;cursor:pointer}.account-check{display:flex;gap:9px;align-items:center;font-size:12px;margin:18px 0;color:var(--txt-2)}.account-check>span:not(:first-child){font-size:11px}.account-check>span:last-child{margin-left:auto}.legacy-claim{align-items:flex-start;padding:13px;background:var(--green-soft);border-radius:10px;line-height:1.6}.legacy-claim>span:last-child{margin:0}.legacy-claim small{display:block;color:var(--txt-3);font-size:10px;margin-top:4px}.account-help{font-size:10px;color:var(--txt-3);margin:7px 0}.account-submit{width:100%;height:44px;margin-top:20px;display:flex;align-items:center;justify-content:center;gap:12px}.account-switch{text-align:center;font-size:12px;color:var(--txt-3);margin-top:24px}.account-switch button,.account-notice button{border:0;background:none;color:var(--green);cursor:pointer;padding:0 5px}.account-error{font-size:12px;padding:12px;border-radius:8px;background:#a5303010;color:#b43b3b;line-height:1.7}.account-notice{font-size:11px;line-height:1.8;padding:12px;border:1px solid var(--line);border-radius:8px;color:var(--txt-2)}@media(max-width:900px){.account-screen{grid-template-columns:1fr}.account-intro{display:none}.account-form-area{min-height:100dvh;padding:28px}}
</style>
