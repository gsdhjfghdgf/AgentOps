const fs=require('node:fs'),path=require('node:path')
/** The OS encrypts remembered tokens. Never fall back to a plaintext token file. */
function installLoginVault({app,ipcMain,safeStorage,BrowserWindow,apiBase,devUrl,registrationKeyFile}){
 const file=()=>path.join(app.getPath('userData'),'account-session.bin')
 function trusted(event){
  const win=BrowserWindow.fromWebContents(event.sender)
  const url=event.senderFrame?.url||''
  return !!win&&event.senderFrame===event.sender.mainFrame&&(url.startsWith('file://')||!!devUrl&&new URL(url).origin===new URL(devUrl).origin)
 }
 ipcMain.handle('agentops:read-login-token',(event)=>{
  if(!trusted(event)||!safeStorage.isEncryptionAvailable())return null
  try{const saved=JSON.parse(safeStorage.decryptString(fs.readFileSync(file())));return saved.apiBase===apiBase&&typeof saved.token==='string'&&/^[A-Za-z0-9_-]{43}$/.test(saved.token)?saved.token:null}catch{return null}
 })
 ipcMain.handle('agentops:read-registration-key',(event)=>{
  if(!trusted(event))return null
  try{const location=registrationKeyFile||process.env.AGENT_REGISTRATION_KEY_FILE||path.join(require('node:os').homedir(),'.agentops','registration.key');const stat=fs.lstatSync(location);if(stat.isSymbolicLink()||!stat.isFile()||stat.size>128)return null;const key=fs.readFileSync(location,'utf8').trim();return /^[A-Za-z0-9_-]{43}$/.test(key)?key:null}catch{return null}
 })
 ipcMain.handle('agentops:save-login-token',(event,token)=>{
  if(!trusted(event))throw Error('不允许访问登录凭据')
  if(token===null||token===''){try{fs.unlinkSync(file())}catch(error){if(error.code!=='ENOENT')throw Error('清理登录凭据失败')}return}
  if(typeof token!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(token))throw Error('登录凭据格式不正确')
  if(!safeStorage.isEncryptionAvailable())throw Error('系统加密不可用，本次不记住登录')
  fs.writeFileSync(file(),safeStorage.encryptString(JSON.stringify({apiBase,token})),{mode:0o600})
 })
}
module.exports={installLoginVault}
