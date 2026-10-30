const {app,BrowserWindow,ipcMain}=require('electron')
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const {randomUUID}=require('node:crypto')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime')
const data=JSON.parse(fs.readFileSync(path.join(runtime,'pause-validation.json'),'utf8'))
const label=process.argv.includes('--source')?'source':'packaged'
const headers={'X-Tenant-Id':data.tenant,'X-User-Id':data.user,'Content-Type':'application/json'}
const delay=ms=>new Promise(r=>setTimeout(r,ms)),requests=[]
let proxy
app.setPath('userData',path.join(runtime,'pause-ui-'+label));app.disableHardwareAcceleration()
ipcMain.on('agentops:notify',()=>{})
async function api(p,method='GET',body){
  const r=await fetch(data.base+p,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})})
  const text=await r.text();assert.ok(r.ok,p+':'+text.slice(0,250));return text?JSON.parse(text):null
}
app.whenReady().then(async()=>{
  let win,task
  try{
    proxy=http.createServer((req,res)=>{
      requests.push(req.url)
      const upstream=http.request('http://127.0.0.1:8080'+req.url,{method:req.method,
        headers:{...req.headers,...headers,host:'127.0.0.1:8080'}},response=>{
        res.writeHead(response.statusCode,response.headers);response.pipe(res)
      })
      upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()})
      req.pipe(upstream);res.on('close',()=>upstream.destroy())
    })
    await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve))
    const customBase='http://127.0.0.1:'+proxy.address().port
    const session=await api('/sessions','POST',{title:'桌面暂停验证 '+label})
    task=await api('/sessions/'+session.sessionId+'/runs','POST',{message:'PAUSE_MODEL：桌面暂停验证 '+label,mode:'build'})
    const archive=label==='source'?root:path.join(root,'release/win-unpacked/resources/app.asar')
    win=new BrowserWindow({show:false,width:1400,height:1000,webPreferences:{
      preload:path.join(archive,'electron/preload.cjs'),additionalArguments:['--agent-api-base='+customBase],
      offscreen:true,backgroundThrottling:false,
    }})
    let wrongBaseRequests=0
    win.webContents.session.webRequest.onBeforeRequest({urls:['http://127.0.0.1:8080/api/*']},(request,done)=>{
      wrongBaseRequests++;done({})
    })
    const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
    async function wait(code,timeout=30000){
      const end=Date.now()+timeout
      while(Date.now()<end){if(await js('return Boolean('+code+')'))return;await delay(80)}
      throw Error('UI timeout '+code)
    }
    const select='[data-session-id="'+session.sessionId+'"]'
    await win.loadFile(path.join(archive,'dist/index.html'))
    await wait('document.querySelector('+JSON.stringify(select)+')')
    assert.equal(await js('return window.__AGENT_DESKTOP__.apiBase'),customBase)
    await js('localStorage.setItem("agent.tenantId",'+JSON.stringify(data.tenant)+');localStorage.setItem("agent.userId",'+JSON.stringify(data.user)+')')
    await win.webContents.reload()
    await wait('document.querySelector('+JSON.stringify(select)+')')
    await js('document.querySelector('+JSON.stringify(select)+').click()')
    await wait('document.querySelector(".composer .send")?.getAttribute("aria-label")==="暂停任务"&&!document.querySelector(".composer .send").disabled')
    await js('const input=document.querySelector(".composer-input");input.value="保留底部草稿";input.dispatchEvent(new Event("input",{bubbles:true}))')
    await js('document.querySelector(".composer .send").click()')
    await wait('document.querySelector(".run-bar .badge")?.textContent.includes("已暂停")&&document.querySelector(".composer-input")&&!document.querySelector(".composer-input").disabled')
    assert.equal(await js('return document.querySelector(".composer-input").value'),'保留底部草稿')
    assert.equal((await api('/runs/'+task.runId)).status,'paused')
    await win.webContents.reload()
    await wait('document.querySelector('+JSON.stringify(select)+')')
    await js('document.querySelector('+JSON.stringify(select)+').click()')
    await wait('document.querySelector(".run-bar .badge")?.textContent.includes("已暂停")&&!document.querySelector(".composer-input").disabled')
    const text='RESUME_NEW_REQUIREMENT：保留已有结果，只总结，不修改文件 '+label
    await js('const input=document.querySelector(".composer-input");input.value='+JSON.stringify(text)+';input.dispatchEvent(new Event("input",{bubbles:true}))')
    assert.equal(await js('return document.querySelectorAll(".composer-input").length'),1)
    assert.ok(await js('return !document.querySelector(".pause-input")'))
    fs.writeFileSync(path.join(runtime,'pause-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
    await js('document.querySelector(".composer .send").click()')
    await wait('document.querySelector(".run-bar .badge")?.textContent.includes("已完成")&&!document.querySelector(".pause-card")')
    const runs=(await api('/sessions/'+session.sessionId+'/runs')).runs
    assert.equal(runs.length,1);assert.equal(runs[0].runId,task.runId)
    const messages=(await api('/sessions/'+session.sessionId+'/messages?limit=100')).messages
    assert.equal(messages.filter(m=>m.role==='user'&&m.content===text).length,1)
    assert.equal(wrongBaseRequests,0)
    assert.ok(requests.some(p=>p.includes('/sync?afterSeq=')))
    assert.ok(!requests.some(p=>p.startsWith('/api/artifacts/by-run/')),'routine sync must not refetch artifacts separately')
    fs.writeFileSync(path.join(runtime,'pause-ui-'+label+'.json'),JSON.stringify({
      passed:true,sameTask:true,composerPauseAndResume:true,pausedAfterReload:true,customBackendReadyBeforeFirstModule:true,
      wrongBaseRequests,incrementalSync:true,runId:task.runId,
    },null,2))
    console.log('PASS '+label+': pause, reload, composer supplement, same-task resume, early custom backend URL and combined sync')
    win.destroy();proxy.close();app.exit(0)
  }catch(error){
    console.error(error.stack)
    if(task)try{await api('/runs/'+task.runId+'/cancel','POST')}catch{}
    if(win&&!win.isDestroyed()){
      fs.writeFileSync(path.join(runtime,'pause-'+label+'-failed.png'),(await win.webContents.capturePage()).toPNG())
      win.destroy()
    }
    proxy?.close();app.exit(1)
  }
})
