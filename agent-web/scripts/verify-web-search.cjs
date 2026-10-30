const{app,BrowserWindow}=require('electron'),fs=require('fs'),path=require('path'),assert=require('assert/strict')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime'),data=JSON.parse(fs.readFileSync(path.join(runtime,'web-search-validation.json'),'utf8')),label=process.argv.includes('--source')?'source':'packaged'
app.setPath('userData',path.join(runtime,'web-search-ui-'+label));app.disableHardwareAcceleration();const delay=ms=>new Promise(r=>setTimeout(r,ms))
app.whenReady().then(async()=>{let win;try{
 const archive=label==='source'?root:path.join(root,'release/win-unpacked/resources/app.asar')
 win=new BrowserWindow({show:false,width:1400,height:1050,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),offscreen:true,backgroundThrottling:false}})
 win.webContents.session.webRequest.onBeforeSendHeaders({urls:['http://127.0.0.1:8080/api/*']},(r,cb)=>cb({requestHeaders:{...r.requestHeaders,'X-Tenant-Id':data.tenant,'X-User-Id':data.user}}))
 const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
 async function wait(code){for(let i=0;i<200;i++){if(await js('return '+code))return;await delay(100)}throw Error('UI timeout '+code)}
 await win.loadFile(path.join(archive,'dist/index.html'));await wait('document.querySelector(\'[data-session-id="'+data.cnSessionId+'"]\')');await js('document.querySelector(\'[data-session-id="'+data.cnSessionId+'"]\').click()');await wait('document.querySelector(".search-sources")')
 await js('document.querySelector(".task-process").open=true;document.querySelector(".search-sources").open=true;document.querySelector(".search-sources").scrollIntoView({block:"start"})');await wait('document.querySelectorAll(".source-row").length>0')
 assert.equal(await js('return document.querySelectorAll(".source-row").length'),data.cnResults);assert.ok(await js('return document.querySelector(".source-query").textContent.includes("张雪峰 去世 传闻")'));assert.ok(await js('return document.querySelector(".source-note").textContent.includes("尚未核验")'));assert.ok(await js('return Array.from(document.querySelectorAll(".source-row a")).every(a=>/^https?:/.test(a.href)&&a.target==="_blank")'));assert.ok(!await js('return document.querySelector(".source-list").textContent.includes("[object Object]")'))
 await delay(150);fs.writeFileSync(path.join(runtime,'web-search-'+label+'.png'),(await win.webContents.capturePage()).toPNG());console.log('PASS '+label+' search sources: actual results, full query, dates, original links, evidence notice and readable layout');win.destroy();app.exit(0)
 }catch(e){console.error(e.stack);if(win)win.destroy();app.exit(1)}})
