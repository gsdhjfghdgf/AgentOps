const {app,BrowserWindow}=require('electron')
const {createServer}=require('http')
const {randomUUID}=require('crypto')
const {execFileSync}=require('child_process')
const path=require('path'),fs=require('fs')
const root=path.resolve(__dirname,'..'),runtime=path.resolve(root,'../agent-server/.runtime')
const tenant=randomUUID(),user=randomUUID(),base='http://127.0.0.1:8080/api'
const headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
app.setPath('userData',path.join(runtime,'questions-qa-profile'))
app.disableHardwareAcceleration()
async function api(route,method='GET',body){const r=await fetch(base+route,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})});const text=await r.text();if(!r.ok)throw Error(route+': '+r.status+' '+text);return text?JSON.parse(text):null}
const fixture=createServer(async(req,res)=>{
  let raw='';for await(const c of req)raw+=c
  const body=JSON.parse(raw),users=body.messages.filter(m=>m.role==='user').map(m=>m.content)
  let name='finish_task',args={answer:'全部补充已应用，任务完成。'}
  if(body.tools){
    if(users[0]==='BUDGET_BOUNDARY'){
      const outputs=body.messages.filter(m=>m.role==='tool').length
      name=outputs<15?'current_time':outputs===15?'text_file_create':'finish_task'
      args=outputs<15?{timezone:'UTC'}:outputs===15?{filename:'boundary.txt',content:'APPROVED_AT_MODEL_LIMIT'}:{answer:'原任务已恢复并完成。'}
    } else if(users.length===1){name='ask_user';args={question:'请选择查询时间范围',options:['本月','上月']}}
    else if(users.length===2){name='ask_user';args={question:'请选择输出方式',options:['摘要','详细分析']}}
    else if(users.length===3){await delay(2500);name='current_time';args={timezone:'UTC'}}
  }
  res.writeHead(200,{'Content-Type':'application/json'})
  res.end(JSON.stringify({choices:[{finish_reason:body.tools?'tool_calls':'stop',message:body.tools?{content:null,tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]}:{content:'[]'}}],usage:{prompt_tokens:40,completion_tokens:20}}))
})
app.whenReady().then(async()=>{
  let win
  try{
    await new Promise(r=>fixture.listen(0,'127.0.0.1',r))
    await api('/model-settings','PUT',{url:'http://127.0.0.1:'+fixture.address().port+'/v1',apiKey:'qa-fixture',model:'questions-fixture',clearKey:false})
    await api('/sessions','POST',{title:'[问题卡片验证]'})
    const archive=process.argv.includes('--source')?root:path.join(root,'release/win-unpacked/resources/app.asar')
    win=new BrowserWindow({show:false,width:1320,height:1000,webPreferences:{preload:path.join(archive,'electron/preload.cjs'),offscreen:true,backgroundThrottling:false}})
    win.webContents.session.webRequest.onBeforeSendHeaders({urls:[base+'/*']},(details,callback)=>callback({requestHeaders:{...details.requestHeaders,...headers}}))
    const js=code=>win.webContents.executeJavaScript('(async()=>{'+code+'})()')
    async function wait(code){for(let i=0;i<400;i++){if(await js('return '+code))return;await delay(100)}throw Error('UI timeout: '+code)}
    const fill=(selector,text)=>js(`const el=document.querySelector(${JSON.stringify(selector)});el.value=${JSON.stringify(text)};el.dispatchEvent(new Event('input',{bubbles:true}));`)
    await win.loadFile(path.join(archive,'dist/index.html'))
    await wait("!!document.querySelector('.session-item')")
    await js("document.querySelector('.session-item').click()")
    await wait("!!document.querySelector('.composer-input') && !document.querySelector('.composer-input').disabled")
    await fill('.composer-input','QUESTION_QA');await js("document.querySelector('.send').click()")
    await wait("document.querySelectorAll('.question-option').length===3")
    if(!await js("return document.querySelector('.composer-input').disabled"))throw Error('Composer must be disabled for clarification')
    const sessions=await api('/sessions');const sessionId=sessions.sessions[0].sessionId
    const initial=(await api('/sessions/'+sessionId+'/runs')).runs[0]
    win.reload();await wait("!!document.querySelector('.session-item')")
    await js("document.querySelector('.session-item').click()")
    await wait("!!document.querySelector('.question-card')")
    if(await js("return document.querySelectorAll('.question-option').length")!==3)throw Error('Choices not restored')
    await js("document.querySelector('.question-option input').click()")
    await wait("!document.querySelector('.question-submit').disabled")
    await js("document.querySelector('.question-submit').click()")
    await wait("document.querySelector('.question-card h3')?.textContent.includes('输出方式')")
    await js("document.querySelector('.question-option input[value=__custom__]').click()")
    await wait("!!document.querySelector('.question-card textarea')")
    await fill('.question-card textarea','自定义：按季度生成摘要')
    fs.writeFileSync(path.join(runtime,'desktop-inline-question.png'),(await win.webContents.capturePage()).toPNG())
    await js("document.querySelector('.question-submit').click()")
    await wait("!document.querySelector('.question-card') && !document.querySelector('.composer-input').disabled")
    await fill('.composer-input','额外要求：中文简短回答');await js("document.querySelector('.send').click()")
    await wait("document.querySelector('.run-top')?.textContent.includes('已完成')")
    const runs=(await api('/sessions/'+sessionId+'/runs')).runs
    if(runs.length!==1||runs[0].runId!==initial.runId)throw Error('Supplements created another run')
    if(await js("return document.querySelectorAll('.task-turn').length")!==1)throw Error('Task split into multiple turns')
    if(await js("return document.querySelectorAll('.task-turn .msg-user').length")!==1)throw Error('Supplement rendered as new user bubble')
    if(await js("return document.querySelectorAll('.task-supplement').length")!==3)throw Error('Supplement missing from task block')
    if(await js("return document.querySelectorAll('.task-response>.msg-head').length")!==1)throw Error('Repeated agent headers')
    fs.writeFileSync(path.join(runtime,'desktop-single-task-turn.png'),(await win.webContents.capturePage()).toPNG())
    console.log('PASS persisted choices, in-card custom answer, same-run steering and one task block')
    const budgetSession=await api('/sessions','POST',{title:'[预算边界回归]'})
    const budget=await api('/sessions/'+budgetSession.sessionId+'/runs','POST',{message:'BUDGET_BOUNDARY'})
    const budgetSql=Buffer.from(`SET search_path TO agent_memory,public; UPDATE budget_accounts SET max_model_attempts=16 WHERE tenant_id='${tenant}' AND user_id='${user}' AND period_key='run:${budget.runId}' AND budget_type='run';`).toString('base64')
    execFileSync('ssh',['-p','2222','-i','D:/VM/scripts/vm_key','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','zww@127.0.0.1',`printf %s ${budgetSql} | base64 -d | docker exec -i agent-postgres psql -U agent -d agent_memory -X -v ON_ERROR_STOP=1 -At`],{timeout:15000})
    async function waitRun(status){for(let i=0;i<900;i++){const r=await api('/runs/'+budget.runId);if(r.status===status)return r;if(['failed','timed_out','budget_exceeded'].includes(r.status))throw Error('Unexpected status '+JSON.stringify(r));await delay(100)}throw Error('Run timed out')}
    await waitRun('waiting_approval')
    const approvals=await api('/runs/'+budget.runId+'/approvals')
    await api('/approvals/'+approvals.approvals[0].approvalId+'/decision','POST',{decision:'approved'})
    await waitRun('budget_exceeded')
    const files=await api('/artifacts/by-run/'+budget.runId)
    if(files.artifacts.length!==1)throw Error('Approved last-call tool did not execute exactly once')
    console.log('PASS approved tool executes at model limit, next model stops with budget_exceeded')
    win.reload();await wait("!!document.querySelector('.session-item')")
    await js("document.querySelector('.session-item').click()")
    await wait("!!document.querySelector('.continue-task') && !document.querySelector('.continue-task').disabled")
    await js("document.querySelector('.continue-task').click()")
    await wait("document.querySelector('.run-top')?.textContent.includes('已完成')")
    if((await api('/sessions/'+budgetSession.sessionId+'/runs')).runs.length!==1)throw Error('Continue created a different task')
    if((await api('/artifacts/by-run/'+budget.runId)).artifacts.length!==1)throw Error('Continue repeated file creation')
    if(await js("return document.querySelectorAll('.activity-batch').length")!==1)throw Error('Tool operations were not grouped into a single collapsed batch')
    if(await js("return !!document.querySelector('.monitor')"))throw Error('Execution sidebar should be closed by default')
    fs.writeFileSync(path.join(runtime,'desktop-task-continued.png'),(await win.webContents.capturePage()).toPNG())
    console.log('PASS continue button resumes same task, preserves effects, compact activity batch and optional sidebar')
    await api('/sessions','DELETE');await api('/model-settings','DELETE')
    win.destroy();fixture.close();app.exit(0)
  }catch(e){console.error(e.stack);if(win)win.destroy();fixture.close();app.exit(1)}
})
