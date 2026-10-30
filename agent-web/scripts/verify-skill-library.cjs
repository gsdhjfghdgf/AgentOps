const {app,BrowserWindow}=require('electron')
const path=require('path'),fs=require('fs')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime')
app.setPath('userData',path.join(runtime,'skill-library-qa-profile'));app.disableHardwareAcceleration()
app.whenReady().then(async()=>{
 const archive=path.join(root,'release/win-unpacked/resources/app.asar')
 const win=new BrowserWindow({show:false,width:1320,height:900,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),offscreen:true,backgroundThrottling:false}})
 const js=code=>win.webContents.executeJavaScript(code)
 async function wait(code){for(let i=0;i<200;i++){if(await js(code))return;await new Promise(r=>setTimeout(r,150))}throw Error('UI timeout '+code)}
 try {
  await win.loadFile(path.join(archive,'dist/index.html'))
  await wait("Array.from(document.querySelectorAll('.tab')).some(b=>b.textContent.includes('工具与技能'))")
  await js("Array.from(document.querySelectorAll('.tab')).find(b=>b.textContent.includes('工具与技能')).click()")
  await wait("document.querySelectorAll('.tool-card').length===48")
  await js("Array.from(document.querySelectorAll('.cap-tabs button')).find(b=>b.textContent.includes('技能库')).click()")
  await wait("Array.from(document.querySelectorAll('.skill-card')).some(c=>c.textContent.includes('design-taste-frontend'))")
  const enabled=await js("Array.from(document.querySelectorAll('.skill-card')).find(c=>c.textContent.includes('design-taste-frontend')).querySelector('input[type=checkbox]').checked")
  if(!enabled)throw Error('Installed skill must be enabled')
  await new Promise(r=>setTimeout(r,300))
  fs.writeFileSync(path.join(runtime,'desktop-installed-taste-skill.png'),(await win.webContents.capturePage()).toPNG())
  console.log('PASS packaged UI: 40 tools and design-taste-frontend registered and enabled in skill library')
  win.destroy();app.exit(0)
 }catch(e){console.error(e.stack);win.destroy();app.exit(1)}
})
