import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {mkdtempSync,readFileSync,existsSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import path from 'node:path'
const require=createRequire(import.meta.url),{installLoginVault}=require('../electron/login-vault.cjs')
test('remembered tokens are OS-encrypted, backend-bound and inaccessible to subframes',t=>{
 const directory=mkdtempSync(path.join(tmpdir(),'agentops-vault-'));t.after(()=>rmSync(directory,{recursive:true,force:true}))
 const handlers=new Map(),frame={url:'file:///application/index.html'},sender={mainFrame:frame},event={sender,senderFrame:frame}
 const safeStorage={isEncryptionAvailable:()=>true,encryptString:value=>Buffer.from([...value].reverse().join('')),decryptString:value=>[...value.toString()].reverse().join('')}
 const options={app:{getPath:()=>directory},ipcMain:{handle:(name,fn)=>handlers.set(name,fn)},safeStorage,BrowserWindow:{fromWebContents:()=>({})},apiBase:'http://backend-a'}
 installLoginVault(options)
 const token='a'.repeat(43)
 handlers.get('agentops:save-login-token')(event,token)
 assert.doesNotMatch(readFileSync(path.join(directory,'account-session.bin'),'utf8'),/accessToken|http:\/\/backend-a/)
 assert.equal(handlers.get('agentops:read-login-token')(event),token)
 assert.equal(handlers.get('agentops:read-login-token')({sender,senderFrame:{url:'https://other.test'}}),null)
 assert.throws(()=>handlers.get('agentops:save-login-token')({sender,senderFrame:{url:'https://other.test'}},token),/不允许/)
 installLoginVault({...options,apiBase:'http://backend-b'})
 assert.equal(handlers.get('agentops:read-login-token')(event),null)
 handlers.get('agentops:save-login-token')(event,null)
 assert.equal(existsSync(path.join(directory,'account-session.bin')),false)
 safeStorage.isEncryptionAvailable=()=>false
 assert.throws(()=>handlers.get('agentops:save-login-token')(event,token),/加密不可用/)
 assert.equal(existsSync(path.join(directory,'account-session.bin')),false)
})
