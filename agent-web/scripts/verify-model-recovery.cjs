const {app,BrowserWindow,ipcMain}=require('electron')
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime')
const data=JSON.parse(fs.readFileSync(path.join(runtime,'model-recovery-validation.json'),'utf8'))
const label=process.argv.includes('--source')?'source':'packaged',taskLabel='PAUSE_UI_'+label+'_'+Date.now()
const headers={'X-Tenant-Id':data.tenant,'X-User-Id':data.user,'Content-Type':'application/json'}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
app.setPath('userData',path.join(runtime,'recovery-ui-'+label));app.disableHardwareAcceleration()
ipcMain.on('agentops:notify',()=>{});ipcMain.on('agentops:theme',()=>{})
async function api(p,method='GET',body){
 const r=await fetch(data.base+p,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})})
 const text=await r.text();assert.ok(r.ok,p+':'+text.slice(0,200));return text?JSON.parse(text):null
}
app.whenReady().then(async()=>{
 let win,proxy
 try{
  proxy=http.createServer((req,res)=>{
   const upstream=http.request('http://127.0.0.1:8080'+req.url,{method:req.method,headers:{...req.headers,...headers,host:'127.0.0.1:8080'}},
    response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)})
   upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()});req.pipe(upstream);res.on('close',()=>upstream.destroy())
  })
  await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve))
  const session=await api('/sessions','POST',{title:'[恢复界面] '+label})
  const task=await api('/sessions/'+session.sessionId+'/runs','POST',{message:taskLabel,mode:'chat'})
  const archive=label==='source'?root:path.join(root,'release/win-unpacked/resources/app.asar')
  win=new BrowserWindow({show:false,width:1400,height:1000,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),
   additionalArguments:['--agent-api-base=http://127.0.0.1:'+proxy.address().port],offscreen:true,backgroundThrottling:false}})
  const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
  async function wait(code,timeout=35000){const end=Date.now()+timeout;while(Date.now()<end){if(await js('return Boolean('+code+')'))return;await delay(70)}throw Error('UI timeout '+code)}
  async function click(selector){await wait('document.querySelector('+JSON.stringify(selector)+')');await js('document.querySelector('+JSON.stringify(selector)+').click()')}
  await win.loadFile(path.join(archive,'dist/index.html'))
  await click('[data-session-id="'+session.sessionId+'"]')
  await wait('document.querySelector(".stream-notice")?.textContent.includes("自动暂停")')
  assert.ok((await js('return document.querySelector(".stream-notice").textContent')).includes('HTTP 503'))
  assert.equal((await api('/runs/'+task.runId)).status,'paused')
  await win.webContents.reload();await click('[data-session-id="'+session.sessionId+'"]')
  await wait('document.querySelector(".stream-notice")?.textContent.includes("自动暂停")')
  await fetch(data.endpoint+'/allow?case='+encodeURIComponent(taskLabel))
  await click('.composer .send')
  await wait('document.querySelector(".run-bar .badge")?.textContent.includes("已完成")')
  assert.ok((await js('return document.body.textContent')).includes('已恢复，这是完整回答。'))
  const runs=await api('/sessions/'+session.sessionId+'/runs');assert.equal(runs.runs.length,1)
  await click('.model-picker-toggle');await click('.configure-chat-model');await click('.add-model')
  const values={'model-label':'界面验证 '+label,'model-url':data.endpoint+'/new/v1','model-key':'wrong-key','model-name':'fixture'}
  await js('for(const [id,value]of Object.entries('+JSON.stringify(values)+')){const node=document.getElementById(id);node.value=value;node.dispatchEvent(new Event("input",{bubbles:true}))}')
  await wait('!document.querySelector(".save-use-model").disabled');await click('.save-use-model')
  await wait('document.querySelector(".model-dialog .result.failed")?.textContent.includes("配置未保存")')
  assert.equal((await api('/model-settings')).profiles.length,1)
  fs.writeFileSync(path.join(runtime,'connection-gate-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
  await js('const node=document.getElementById("model-key");node.value="fixture-key";node.dispatchEvent(new Event("input",{bubbles:true}))')
  await click('.save-model');await wait('document.querySelector(".model-dialog .result")?.textContent.includes("配置已保存")')
  const catalog=await api('/model-settings');assert.equal(catalog.profiles.length,2)
  const created=catalog.profiles.find(p=>p.name===values['model-label'])
  await api('/model-settings/profiles/'+created.id,'DELETE')
  fs.writeFileSync(path.join(runtime,'recovery-ui-'+label+'.json'),JSON.stringify({passed:true,automaticPause:true,
   reasonVisibleAfterReload:true,sameTaskResume:true,failedSaveDoesNotPersist:true,validSaveTestsAutomatically:true},null,2))
  console.log('PASS '+label+': retry reason, reload, same-task resume, mandatory connection check and save rejection')
  win.destroy();proxy.close();app.exit(0)
 }catch(error){console.error(error.stack);if(win&&!win.isDestroyed()){fs.writeFileSync(path.join(runtime,'recovery-ui-'+label+'-failed.png'),(await win.webContents.capturePage()).toPNG());win.destroy()}proxy?.close();app.exit(1)}
})
