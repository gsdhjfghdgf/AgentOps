import {ref} from 'vue'
import {api,API_BASE,saveIdentity} from '../services/httpClient'
import {restoreToken,storeToken,accessToken,expireSession} from '../services/authSession'
export interface Account {id:string;tenantId:string;userId:string;username:string;displayName:string;createdAt:string}
export interface LoginDevice {id:string;device:string;createdAt:string;lastUsedAt:string;expiresAt:string;current:boolean}
const account=ref<Account|null>(null),authReady=ref(false),setupRequired=ref(false),authError=ref('')
let initializing:Promise<void>|undefined,subscribed=false
function apply(value:Account){account.value=value;saveIdentity(value.tenantId,value.userId)}
export function useAccount(){
 if(!subscribed&&typeof window!=='undefined'){subscribed=true;window.addEventListener?.('agent:auth-expired',()=>{account.value=null;authError.value='登录已失效，请重新登录。'})}
 async function initialize(){
  if(initializing)return initializing
  initializing=(async()=>{
   authReady.value=false;authError.value=''
   try{
    await restoreToken(API_BASE)
    if(accessToken())try{const result=await api.get<{account:Account}>('/api/auth/me');apply(result.account);return}catch(error){
     if((error as {status?:number}).status!==401)throw error
     await storeToken('')
    }
    setupRequired.value=(await api.get<{setupRequired:boolean}>('/api/auth/bootstrap')).setupRequired
   }catch(error){authError.value=(error as Error).message}finally{authReady.value=true}
  })().finally(()=>{initializing=undefined})
  return initializing
 }
 async function login(username:string,password:string,remember:boolean){
  const result=await api.post<{account:Account;accessToken:string}>('/api/auth/login',{username,password,remember})
  await storeToken(result.accessToken,remember);apply(result.account);authError.value=''
 }
 async function register(username:string,displayName:string,password:string,inheritLegacy:boolean,registrationKey?:string){
  const proof=registrationKey||await window.desktopEnv?.readRegistrationKey?.()
  if(!proof)throw new Error('未找到本机注册凭据，请确认后端已启动；其他部署方式请填写管理员提供的注册凭据。')
  await api.post('/api/auth/register',{username,displayName,password,inheritLegacy,registrationKey:proof})
  setupRequired.value=false;await login(username,password,false)
 }
 async function logout(all=false){await api.post(all?'/api/auth/logout-all':'/api/auth/logout');await storeToken('');account.value=null;authError.value=''}
 async function updateName(displayName:string){const result=await api.put<{account:Account}>('/api/auth/me',{displayName});apply(result.account)}
 async function changePassword(currentPassword:string,newPassword:string){await api.put('/api/auth/password',{currentPassword,newPassword});expireSession();authError.value='密码已修改，所有设备已退出。请使用新密码登录。'}
 return {account,authReady,setupRequired,authError,initialize,login,register,logout,updateName,changePassword}
}
