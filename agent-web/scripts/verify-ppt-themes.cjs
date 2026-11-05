const {app,BrowserWindow}=require('electron')
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const root=path.resolve(__dirname,'../..'),runtime=path.join(root,'agent-server/.runtime')
app.setPath('userData',path.join(runtime,'theme-gallery-ui'));app.disableHardwareAcceleration()
app.whenReady().then(async()=>{let win
 try{
  const themes=[['tech','科技产品'],['business','商务汇报'],['academic','学术研究'],['editorial','杂志人文'],['warm','温暖生活'],['vivid','活力创意'],['forest','自然生态'],['ocean','海洋清新'],['ink','极简黑白']]
  const cards=themes.map(([id,name])=>'<figure><img src="../target/presentation-themes/'+id+'.png"><figcaption>'+name+'</figcaption></figure>').join('')
  const html='<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:24px;background:#e8ebf0;color:#263243;font-family:"Microsoft YaHei"}h1{font-size:24px;margin:0 0 18px}main{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}figure{margin:0}img{display:block;width:100%;aspect-ratio:16/9}figcaption{font-size:15px;padding:6px 0}</style><h1>AgentOps · 9 套 PPT 风格</h1><main>'+cards+'</main>'
  const file=path.join(runtime,'ppt-theme-gallery.html');fs.writeFileSync(file,html)
  win=new BrowserWindow({show:false,width:1600,height:1110,webPreferences:{offscreen:true,backgroundThrottling:false}})
  await win.loadFile(file)
  for(let i=0;i<100;i++){if(await win.webContents.executeJavaScript('document.images.length===9&&Array.from(document.images).every(i=>i.complete&&i.naturalWidth===1440)'))break;await new Promise(r=>setTimeout(r,100))}
  assert.ok(await win.webContents.executeJavaScript('Array.from(document.images).every(i=>i.complete&&i.naturalWidth===1440)'))
  fs.writeFileSync(path.join(runtime,'ppt-theme-gallery.png'),(await win.webContents.capturePage()).toPNG())
  console.log(JSON.stringify({passed:true,styles:themes.length}));win.destroy();app.exit(0)
 }catch(error){console.error(error.stack);win?.destroy();app.exit(1)}
})
