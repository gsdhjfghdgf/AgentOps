// Desktop UI contract test with an isolated HTTP fixture. No VM or real user data is touched.
const {app,BrowserWindow,ipcMain}=require('electron')
const {createServer}=require('http'),{randomUUID}=require('crypto')
const fs=require('fs'),path=require('path'),assert=require('assert/strict')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime')
const projects=[{id:randomUUID(),name:'Agent 实战',root:'D:\\Agent实战',previewOrigin:''},{id:randomUUID(),name:'第二个项目',root:'D:\\第二个项目',previewOrigin:''}]
const sessions=[{sessionId:randomUUID(),title:'项目 A 的原有会话',projectId:projects[0].id,updatedAt:new Date().toISOString()},{sessionId:randomUUID(),title:'普通历史会话',projectId:'',updatedAt:new Date().toISOString()}]
const posts=[];let selected=projects[1],picked=0
const delay=ms=>new Promise(r=>setTimeout(r,ms))
app.setPath('userData',path.join(runtime,'project-sessions-ui-profile'));app.disableHardwareAcceleration()
ipcMain.handle('agentops:select-project',()=>{picked++;return 'D:\\NativePickerProject'})
const fixture=createServer(async(req,res)=>{
 try{
 let raw='';for await(const chunk of req)raw+=chunk
 const body=raw?JSON.parse(raw):{},u=new URL(req.url,'http://localhost');let result={}
 res.setHeader('Access-Control-Allow-Origin','*');res.setHeader('Access-Control-Allow-Headers','*');res.setHeader('Access-Control-Allow-Methods','GET,POST,PUT,DELETE,OPTIONS')
 if(req.method==='OPTIONS'){res.end();return}
 if(u.pathname==='/api/projects'&&req.method==='GET')result={projects,selected}
 else if(u.pathname==='/api/projects'&&req.method==='POST'){selected={id:randomUUID(),name:'NativePickerProject',root:body.directory,previewOrigin:''};projects.unshift(selected);result=selected}
 else if(u.pathname==='/api/projects/selection'){selected=projects.find(p=>p.id===body.projectId)||null;result={ok:true}}
 else if(u.pathname.endsWith('/git'))result={repository:true,text:'',warning:''}
 else if(u.pathname.endsWith('/changes'))result={changes:[]}
 else if(u.pathname==='/api/sessions'&&req.method==='POST'){posts.push(body);const s={sessionId:randomUUID(),...body,projectId:body.projectId||'',updatedAt:new Date().toISOString()};sessions.unshift(s);result=s}
 else if(u.pathname==='/api/sessions'){const q=u.searchParams.get('query')||'';result={sessions:sessions.filter(s=>s.title.includes(q))}}
 else if(u.pathname.endsWith('/messages'))result={messages:[],nextBeforeSeq:null,hasMore:false}
 else if(u.pathname.endsWith('/runs'))result={runs:[]}
 else if(u.pathname==='/api/model-settings')result={model:'fixture-model',custom:false}
 res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(result))
 }catch(error){res.writeHead(500);res.end(JSON.stringify({message:error.message}))}
})
app.whenReady().then(async()=>{
 let win
 try{
 await new Promise(r=>fixture.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+fixture.address().port
 const archive=process.argv.includes('--source')?root:path.join(root,'release/win-unpacked/resources/app.asar')
 win=new BrowserWindow({show:false,width:1360,height:900,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),offscreen:true,backgroundThrottling:false}})
 // Redirect only API transport, leaving the app's real fetch/parser and all Vue event handling intact.
 win.webContents.session.webRequest.onBeforeRequest({urls:['http://127.0.0.1:8080/api/*']},(d,cb)=>cb({redirectURL:base+new URL(d.url).pathname+new URL(d.url).search}))
 const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
 async function wait(code){for(let n=0;n<100;n++){if(await js('return '+code))return;await delay(75)}throw Error('UI timeout: '+code)}
 async function click(selector){await js(`document.querySelector(${JSON.stringify(selector)}).click()`)}
 await win.loadFile(path.join(archive,'dist/index.html'))
 await wait("document.querySelectorAll('.project-group').length===2&&document.querySelectorAll('.session-item').length===2")
 await js("document.querySelector('.project-new').click()")
 await wait("document.querySelectorAll('.session-item').length===3")
 assert.equal(posts.at(-1).projectId,projects[0].id)
 await wait("document.querySelector('.conversation-context')?.textContent.includes('Agent 实战')")
 // Changing the default selected project does not alter this session's project information.
 selected=projects[1];await js("window.dispatchEvent(new Event('agent:projects-changed'))")
 await click('.composer-controls .project-toggle');await wait("!!document.querySelector('.project-dialog')")
 assert.equal(await js("return document.querySelector('.project-dialog select').disabled"),true)
 assert.ok((await js("return document.querySelector('.project-root').textContent")).includes('Agent实战'))
 await js("document.querySelector('.project-dialog header button').click()")
 await click('.new-chat');await wait("document.querySelectorAll('.session-item').length===4")
 assert.equal(posts.at(-1).projectId,null)
 await wait("document.querySelector('.conversation-context')?.textContent.trim()==='普通会话'")
 // Existing project chat remains associated when reopened and when filtered out by search.
 await js("Array.from(document.querySelectorAll('.session-item')).find(b=>b.textContent.includes('原有会话')).click()")
 await wait("document.querySelector('.conversation-context')?.textContent.includes('Agent 实战')")
 await js("const e=document.querySelector('input[type=search]');e.value='不存在';e.dispatchEvent(new Event('input',{bubbles:true}))")
 await wait("document.querySelectorAll('.session-item').length===0")
 assert.ok((await js("return document.querySelector('.conversation-context').textContent")).includes('Agent 实战'))
 await js("const e=document.querySelector('input[type=search]');e.value='';e.dispatchEvent(new Event('input',{bubbles:true}))")
 await wait("document.querySelectorAll('.session-item').length===4")
 await click('.project-fold');assert.equal(await js("return document.querySelector('.project-fold').getAttribute('aria-expanded')"),'false')
 await click('.project-fold')
 await click('.add-project');await wait("!!document.querySelector('.attach-project')");await click('.attach-project')
 await wait("document.querySelectorAll('.project-group').length===3")
 assert.equal(picked,1);assert.equal(projects[0].root,'D:\\NativePickerProject')
 await js("document.querySelector('.project-dialog header button').click()")
 // The newly selected folder exposes its own project session creation action.
 await js("document.querySelector('.project-new').click()")
 await wait("document.querySelectorAll('.session-item').length===5")
 assert.equal(posts.at(-1).projectId,projects[0].id)
 await wait("document.querySelector('.conversation-context')?.textContent.includes('NativePickerProject')")
 await delay(200);fs.writeFileSync(path.join(runtime,process.argv.includes('--source')?'project-sessions-source.png':'project-sessions-packaged.png'),(await win.webContents.capturePage()).toPNG())
 console.log('PASS '+(process.argv.includes('--source')?'source':'packaged')+': project groups, native folder selection, nested new chats, standalone chats, pinned project after reopen/default change/search, collapse/expand')
 win.destroy();fixture.close();app.exit(0)
 }catch(error){console.error(error.stack||error);if(win)win.destroy();fixture.close();app.exit(1)}
})
