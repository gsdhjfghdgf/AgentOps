const {app,BrowserWindow,ipcMain}=require('electron')
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime')
const data=JSON.parse(fs.readFileSync(path.join(runtime,'saved-models-validation.json'),'utf8'))
const label=process.argv.includes('--source')?'source':'packaged',reopen=process.argv.includes('--reopen-only')
const proofFile=path.join(runtime,'model-ui-'+label+'.json')
const headers={'X-Tenant-Id':data.tenant,'X-User-Id':data.user,'Content-Type':'application/json'}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))
app.setPath('userData',path.join(runtime,'model-ui-'+label));app.disableHardwareAcceleration()
ipcMain.on('agentops:notify',()=>{});ipcMain.on('agentops:theme',()=>{})
async function api(p,method='GET',body){
  const r=await fetch(data.base+p,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})})
  const text=await r.text();assert.ok(r.ok,p+':'+text.slice(0,250));return text?JSON.parse(text):null
}
app.whenReady().then(async()=>{
  let win,proxy
  try{
    proxy=http.createServer((req,res)=>{
      const upstream=http.request('http://127.0.0.1:8080'+req.url,{method:req.method,
        headers:{...req.headers,...headers,host:'127.0.0.1:8080'}},response=>{
        res.writeHead(response.statusCode,response.headers);response.pipe(res)
      })
      upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()})
      req.pipe(upstream);res.on('close',()=>upstream.destroy())
    })
    await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve))
    const customBase='http://127.0.0.1:'+proxy.address().port
    const archive=label==='source'?root:path.join(root,'release/win-unpacked/resources/app.asar')
    win=new BrowserWindow({show:false,width:1400,height:1000,webPreferences:{
      preload:path.join(archive,'electron/preload.cjs'),additionalArguments:['--agent-api-base='+customBase],
      offscreen:true,backgroundThrottling:false,
    }})
    const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
    async function wait(code,timeout=30000){
      const end=Date.now()+timeout
      while(Date.now()<end){if(await js('return Boolean('+code+')'))return;await delay(80)}
      throw Error('UI timeout '+code)
    }
    async function click(selector){await wait('document.querySelector('+JSON.stringify(selector)+')');await js('document.querySelector('+JSON.stringify(selector)+').click()')}
    async function choose(id){
      await click('.model-picker-toggle');await click('.model-picker-menu [data-profile-id="'+id+'"]')
      await wait('!document.querySelector(".model-picker-menu")&&!document.querySelector(".model-picker-toggle").disabled')
    }
    async function manage(){await click('.model-picker-toggle');await click('.configure-chat-model');await wait('document.querySelector(".model-dialog .add-model")&&!document.querySelector(".model-dialog .add-model").disabled')}
    await win.loadFile(path.join(archive,'dist/index.html'))
    await wait('document.querySelector(".model-picker-toggle")&&!document.querySelector(".model-picker-toggle").disabled')
    if(reopen){
      const proof=JSON.parse(fs.readFileSync(proofFile,'utf8'))
      assert.equal((await api('/model-settings')).selectedProfileId,proof.createdId)
      assert.ok((await js('return document.querySelector(".model-picker-toggle").textContent')).includes(proof.name))
      await manage();await click('.profile-row[data-profile-id="'+proof.createdId+'"]')
      await wait('document.querySelector("#model-key")?.placeholder.includes("留空")')
      assert.equal(await js('return document.querySelector("#model-key").value'),'')
      assert.equal(await js('return document.querySelectorAll(".model-dialog .profile-row[data-profile-id]").length'),3)
      await click('.delete-profile');await click('.delete-confirm button:last-child')
      await wait('document.querySelector(".model-dialog [role=status]")?.textContent.includes("已从模型列表移除")')
      await click('[aria-label="关闭模型配置"]');await choose(data.b.id)
      assert.equal((await api('/model-settings')).profiles.length,2)
      proof.restartedAppRetainsModels=true;proof.keysNotExposed=true;proof.deleteFromDialog=true
      fs.writeFileSync(proofFile,JSON.stringify(proof,null,2))
      console.log('PASS '+label+': new app process retains saved models/selection; delete only the chosen entry')
    }else{
      await choose(data.a.id);await manage()
      assert.equal(await js('return document.querySelectorAll(".model-dialog .profile-row[data-profile-id]").length'),2)
      assert.equal(await js('return document.querySelector("#model-key").value'),'')
      await click('.add-model')
      const name='fixture-gamma'
      const values={'model-url':data.endpoint+'/v1','model-key':'fixture-key-c','model-name':name}
      await js('for(const [id,value]of Object.entries('+JSON.stringify(values)+')){const node=document.getElementById(id);node.value=value;node.dispatchEvent(new Event("input",{bubbles:true}))}')
      await wait('!document.querySelector(".save-model").disabled')
      await click('.actions button:first-child')
      await wait('document.querySelector(".model-dialog [role=status]")?.textContent.includes("连接正常")')
      await click('.save-model');await wait('document.querySelector(".model-dialog [role=status]")?.textContent.includes("配置已保存")')
      const catalog=await api('/model-settings'),created=catalog.profiles.find(p=>p.name===name)
      assert.ok(created);assert.equal(catalog.profiles.length,3);assert.equal(catalog.selectedProfileId,data.a.id)
      assert.equal(await js('return document.querySelector("#model-key").value'),'')
      fs.writeFileSync(path.join(runtime,'model-catalog-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
      await click('[aria-label="关闭模型配置"]');await choose(created.id)
      await win.webContents.reload()
      await wait('document.querySelector(".model-picker-toggle")?.textContent.includes('+JSON.stringify(name)+')')
      await click('.model-picker-toggle')
      assert.equal(await js('return document.querySelectorAll(".model-options [role=menuitemradio]").length'),4)
      assert.equal(await js('return document.querySelector(".model-options [aria-checked=true]").getAttribute("data-profile-id")'),created.id)
      fs.writeFileSync(proofFile,JSON.stringify({passed:true,name,createdId:created.id,multipleSavedModels:true,
        independentSave:true,composerSwitch:true,reloadRetainsModels:true},null,2))
      console.log('PASS '+label+': multi-model catalog, connection test, save only, composer selection and reload')
    }
    win.destroy();proxy.close();app.exit(0)
  }catch(error){
    console.error(error.stack)
    if(win&&!win.isDestroyed()){fs.writeFileSync(path.join(runtime,'model-ui-'+label+'-failed.png'),(await win.webContents.capturePage()).toPNG());win.destroy()}
    proxy?.close();app.exit(1)
  }
})
