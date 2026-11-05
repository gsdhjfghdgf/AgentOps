/** Login tokens are never stored in localStorage or URLs. Desktop persistence uses the OS vault. */
let token='',generation=0,base='',writes=Promise.resolve()
export const accessToken=()=>token
export const authGeneration=()=>generation
export async function restoreToken(apiBase:string){
 base=apiBase
 try{token=sessionStorage.getItem('agentops-session:'+base)||''}catch{}
 if(!token)try{token=await window.desktopEnv?.readLoginToken?.()||''}catch{}
 generation++
}
export async function storeToken(value:string,remember=false){
 token=value;generation++
 try{if(value)sessionStorage.setItem('agentops-session:'+base,value);else sessionStorage.removeItem('agentops-session:'+base)}catch{}
 writes=writes.catch(()=>{}).then(async()=>{if(window.desktopEnv?.saveLoginToken)await window.desktopEnv.saveLoginToken(remember?value:null)})
 try{await writes}catch{/* A successful login remains usable for this window if the OS vault is unavailable. */}
}
export function expireSession(){
 void storeToken('')
 if(typeof window!=='undefined')window.dispatchEvent?.(new Event('agent:auth-expired'))
}
