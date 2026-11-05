const {app,BrowserWindow,ipcMain}=require('electron')
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const delivery=process.argv.includes('--delivery')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime'),data=JSON.parse(fs.readFileSync(path.join(runtime,delivery?'ppt-delivery-validation.json':'ppt-design-sample/validation.json'),'utf8'))
const pageCount=delivery?data.pages:4
const label=process.argv.includes('--source')?'source':'packaged',headers={'X-Tenant-Id':data.tenant,'X-User-Id':data.user}
app.setPath('userData',path.join(runtime,'tech-design-ui-'+label));app.disableHardwareAcceleration();ipcMain.on('agentops:notify',()=>{})
app.whenReady().then(async()=>{let win,proxy
 try{
  proxy=http.createServer((req,res)=>{const up=http.request('http://127.0.0.1:8080'+req.url,{method:req.method,headers:{...req.headers,...headers,host:'127.0.0.1:8080'}},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res)});up.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()});req.pipe(up);res.on('close',()=>up.destroy())})
  await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve))
  const archive=label==='source'?root:path.join(root,'release/win-unpacked/resources/app.asar')
  win=new BrowserWindow({show:false,width:1440,height:1000,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),additionalArguments:['--agent-api-base=http://127.0.0.1:'+proxy.address().port],offscreen:true,backgroundThrottling:false}})
  const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
  async function wait(code){const end=Date.now()+30000;while(Date.now()<end){if(await js('return Boolean('+code+')'))return;await new Promise(resolve=>setTimeout(resolve,80))}throw Error('UI timeout '+code)}
  async function click(selector){await wait('document.querySelector('+JSON.stringify(selector)+')');await js('document.querySelector('+JSON.stringify(selector)+').click()')}
  await win.loadFile(path.join(archive,'dist/index.html'))
  await click('.function-rail [aria-label="工具技能"]');await wait('document.querySelectorAll(".tool-card").length===74')
  await js('const select=document.querySelector(".tool-category select");select.value="media";select.dispatchEvent(new Event("change",{bubbles:true}))')
  await wait('document.querySelectorAll(".tool-card").length===4')
  assert.ok((await js('return document.querySelector(".tool-grid").textContent')).includes('搜索演示配图'))
  await click('.function-rail [aria-label="对话"]');await click('[data-session-id="'+data.sessionId+'"]')
  await wait('document.querySelector(\'.ppt-file[data-artifact-id="'+data.presentationId+'"]\')')
  assert.equal(await js('return document.querySelector(".ppt-preview-files")'),null)
  assert.equal(await js('return document.querySelectorAll(".ppt-file").length'),1)
  if(delivery)assert.ok((await js('return document.querySelector(".ppt-file").textContent')).includes('AI对生活的影响.pptx'))
  await js('document.querySelector(".ppt-file button").click()')
  for(let page=1;page<=pageCount;page++){
   if(page>1)await js('Array.from(document.querySelectorAll(".slide-preview nav button")).find(b=>b.textContent.includes("下一页")).click()')
   await wait('document.querySelector(".slide-preview nav")?.textContent.includes("'+page+' / '+pageCount+'")&&!document.querySelector(".slide-preview [role=status]")&&document.querySelector(".slide-preview img")?.complete&&document.querySelector(".slide-preview img")?.naturalWidth>100')
  }
  if(delivery){
   const result={passed:true,label,actualPagesViewed:pageCount,singlePptCard:true,previewAttachmentsHidden:true,retainedRevisions:data.storedRevisions,theme:data.theme}
   fs.writeFileSync(path.join(runtime,'ppt-delivery-ui-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
   fs.writeFileSync(path.join(runtime,'ppt-delivery-ui-'+label+'.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result))
   win.destroy();proxy.close();app.exit(0);return
  }
  fs.writeFileSync(path.join(runtime,'tech-design-preview-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
  const titles=['封面','重点数字','流程图','图文页']
  const cards=titles.map((title,index)=>'<figure><img src="ppt-design-sample/'+encodeURIComponent('幻灯片-'+(index+1)+'.png')+'"><figcaption>0'+(index+1)+'　'+title+'</figcaption></figure>').join('')
  const html='<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:24px 28px;background:#e9edf3;color:#1d2939;font-family:"Microsoft YaHei",sans-serif}h1{margin:0 0 18px;font-size:24px}main{display:grid;grid-template-columns:1fr 1fr;gap:18px 24px}figure{margin:0}img{width:100%;display:block;aspect-ratio:16/9;box-shadow:0 4px 14px #172b4d20}figcaption{font-size:13px;color:#526078;padding:7px 0 0}</style><h1>科技产品风 · 4 页样张</h1><main>'+cards+'</main>'
  const gallery=path.join(runtime,'tech-design-overview-'+label+'.html');fs.writeFileSync(gallery,html)
  win.setSize(1440,960);await win.loadFile(gallery);await wait('document.images.length===4&&Array.from(document.images).every(i=>i.complete&&i.naturalWidth>100)')
  fs.writeFileSync(path.join(runtime,'tech-design-overview-'+label+'.png'),(await win.webContents.capturePage()).toPNG())
  const result={passed:true,label,actualPagesViewed:4,toolCount:74,localAssets:true,previewImagesDeferred:true}
  fs.writeFileSync(path.join(runtime,'tech-design-ui-'+label+'.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result))
  win.destroy();proxy.close();app.exit(0)
 }catch(error){console.error(error.stack);if(win&&!win.isDestroyed()){fs.writeFileSync(path.join(runtime,'tech-design-failed-'+label+'.png'),(await win.webContents.capturePage()).toPNG());win.destroy()}proxy?.close();app.exit(1)}
})
