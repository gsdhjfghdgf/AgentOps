import{createServer}from'node:http'
import{randomUUID}from'node:crypto'
import{mkdtempSync,writeFileSync,readFileSync}from'node:fs'
import path from'node:path'
import assert from'node:assert/strict'
const runtime=path.resolve('agent-server/.runtime'),base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID(),headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'}
const gates=new Map(),counts=new Map(),closedTags=new Set(),requests=[],activeTasks=[]
const delay=ms=>new Promise(r=>setTimeout(r,ms)),answer='## 检查结果\n\napp.js 第 2 行把请求内容传给 eval，可执行未经验证的代码。\n\n已完成读取和阶段记录，建议移除 eval，改用明确允许的操作列表。\n\n### 验证范围\n\n只检查当前文件，没有修改项目，没有联网，也没有执行终端命令。中文与表情输出正常😀。'
function data(res,payload){if(!res.destroyed)res.write('data: '+(typeof payload==='string'?payload:JSON.stringify(payload))+'\n\n')}
function fragment(id,name,args){return{choices:[{index:0,delta:{tool_calls:[{index:0,...(id?{id}:{}),function:{...(name?{name}:{}),arguments:args}}]}}]}}
function done(res){data(res,{choices:[{index:0,finish_reason:'tool_calls',delta:{}}]});data(res,{choices:[],usage:{prompt_tokens:85,completion_tokens:65}});data(res,'[DONE]');res.end()}
async function gate(tag,res){await new Promise(resolve=>{gates.set(tag,resolve);res.once('close',resolve)});gates.delete(tag)}
const server=createServer(async(req,res)=>{try{
 const url=new URL(req.url,'http://local');if(url.pathname==='/release'){gates.get(url.searchParams.get('tag'))?.();res.end('ok');return}
 if(url.pathname==='/state'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({gates:[...gates.keys()],closedTags:[...closedTags],requests}));return}
 if(url.pathname==='/shutdown'){res.end('ok');server.close();setTimeout(()=>process.exit(0),200);return}
 let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw),goal=body.messages.find(m=>m.role==='user')?.content||'',tag=goal.match(/STREAM_[A-Z]+/)?.[0]||'OTHER'
 requests.push({tag,stream:body.stream===true,includeUsage:body.stream_options?.include_usage===true});const count=(counts.get(tag)||0)+1;counts.set(tag,count)
 if(!body.tools){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({choices:[{finish_reason:'stop',message:{content:'[]'}}],usage:{prompt_tokens:1,completion_tokens:1}}));return}
 res.writeHead(200,{'Content-Type':'text/event-stream; charset=utf-8','Cache-Control':'no-cache'});res.on('close',()=>{if(!res.writableFinished)closedTags.add(tag)})
 const toolResults=body.messages.filter(m=>m.role==='tool'),hasRead=body.messages.some(m=>m.tool_calls?.some(c=>c.function.name==='project_read')),hasNotes=body.messages.some(m=>m.tool_calls?.some(c=>c.function.name==='task_notes'))
 if((tag==='STREAM_PHASES'||tag==='STREAM_UI'||tag==='STREAM_FOLD')&&!hasRead){
  data(res,{choices:[{index:0,delta:{content:'我会先读取 app.js 的关键代码并核对行号，再把可确认的问题记录为阶段发现。只检查这一个文件，避免扩大范围。',reasoning_content:'PRIVATE_STREAM_CANARY'}}]});await delay(90)
  data(res,fragment(randomUUID(),'project_read',JSON.stringify({path:'app.js'})));done(res);return
 }
  if((tag==='STREAM_PHASES'||tag==='STREAM_UI'||tag==='STREAM_FOLD')&&!hasNotes){assert.ok(toolResults.some(m=>m.content.includes('eval')));data(res,{choices:[{delta:{content:'文件已经读完，接下来保存发现和原文位置，方便你核对证据。'}}]});if(tag==='STREAM_FOLD')await gate('STREAM_FOLD_NOTES',res);data(res,fragment(randomUUID(),'task_notes',JSON.stringify({revision:'0',content:'**阶段发现：** app.js:2 将请求中的 code 传给 eval，存在执行任意代码的风险。已核对原文，仅检查这一份文件；尚未修改代码。'})));done(res);return}
 if(tag==='STREAM_CANCEL'||tag==='STREAM_AFTERFAIL'||(tag==='STREAM_ERROR'&&count===1)){
  data(res,fragment(randomUUID(),'finish_task','{"answer":"未'));await gate(tag,res);if(!res.destroyed)res.destroy();return
 }
 if(tag==='STREAM_ERROR'){data(res,fragment(randomUUID(),'finish_task',JSON.stringify({answer:'恢复后的完整回答。'})));done(res);return}
 const encoded=JSON.stringify(answer).slice(1,-1),cut=Math.floor(encoded.length/2)
 if(tag==='STREAM_FOLD')await gate('STREAM_FOLD_BEFORE',res)
 data(res,fragment(randomUUID(),'finish_task','{"answer":"'+encoded.slice(0,cut)));await gate(tag,res)
 if(res.destroyed)return;await delay(120);data(res,fragment(null,null,encoded.slice(cut)+'"}'));done(res)
 }catch(e){console.error(e.stack);if(!res.headersSent)res.writeHead(500);res.end()}})
await new Promise(r=>server.listen(0,'127.0.0.1',r));const fixture='http://127.0.0.1:'+server.address().port
async function api(p,m='GET',value,status=200){const r=await fetch(base+p,{method:m,headers,...(value===undefined?{}:{body:JSON.stringify(value)})});const text=await r.text();assert.equal(r.status,status,p+': '+text.slice(0,400));return text?JSON.parse(text):null}
async function until(check,description,timeout=30000){const end=Date.now()+timeout;while(Date.now()<end){const result=await check();if(result)return result;await delay(80)}throw Error('timeout: '+description)}
async function task(tag,projectId){const session=await api('/sessions','POST',{title:tag,projectId},201),run=await api('/sessions/'+session.sessionId+'/runs','POST',{message:tag},201);activeTasks.push(run.runId);return{...run,sessionId:session.sessionId}}
async function listen(id){const controller=new AbortController(),events=[];const response=await fetch(`${base}/runs/${id}/events?lastEventSeq=0&tenantId=${tenant}&userId=${user}`,{signal:controller.signal});assert.ok(response.ok);const promise=(async()=>{let text='',decoder=new TextDecoder();try{for await(const chunk of response.body){text+=decoder.decode(chunk,{stream:true}).replaceAll('\r\n','\n');let end;while((end=text.indexOf('\n\n'))>=0){const block=text.slice(0,end);text=text.slice(end+2);const payload=block.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5)).join('\n');if(payload)events.push(JSON.parse(payload));}}}catch(e){if(!controller.signal.aborted)throw e}})();return{events,close:async()=>{controller.abort();await promise}}}
try{
 await api('/memory-settings','PUT',{enabled:false});await api('/model-settings','PUT',{url:fixture+'/v1',model:'stream-fixture',apiKey:'',clearKey:true})
 const directory=mkdtempSync(path.join(runtime,'stream-project-')),source='// STREAM_SOURCE_CANARY\nexport const run = req => eval(req.body.code);\n';writeFileSync(path.join(directory,'app.js'),source)
 const project=await api('/projects','POST',{directory}),phase=await task('STREAM_PHASES',project.id),live=await listen(phase.runId)
 await until(()=>live.events.some(e=>e.type==='message.delta'&&e.channel==='answer'),'first answer before finish')
 assert.equal((await api('/runs/'+phase.runId)).status,'running');assert.ok(gates.has('STREAM_PHASES'))
 const before=await api('/sessions/'+phase.sessionId+'/messages?limit=100');assert.ok(before.messages.some(m=>m.kind==='commentary'&&m.content.includes('先读取')));assert.ok(before.messages.some(m=>m.role==='tool'&&m.content.includes('"saved":true')),JSON.stringify(before.messages.filter(m=>m.role==='tool')))
 const partial=live.events.filter(e=>e.type==='message.delta'&&e.channel==='answer').map(e=>e.delta).join('');assert.ok(partial.length>30&&partial.length<answer.length)
 gates.get('STREAM_PHASES')();await until(async()=>{const r=await api('/runs/'+phase.runId);assert.ok(!['failed','waiting_user','waiting_approval'].includes(r.status),JSON.stringify(r));return r.status==='succeeded'},'validated final answer')
 await until(()=>live.events.some(e=>e.type==='run.succeeded'),'terminal SSE');assert.equal(live.events.filter(e=>e.type==='message.delta'&&e.channel==='answer').map(e=>e.delta).join(''),answer)
 assert.ok(!JSON.stringify(live.events).includes('PRIVATE_STREAM_CANARY'));const history=await api('/sessions/'+phase.sessionId+'/messages?limit=100');assert.equal(history.messages.filter(m=>m.kind==='candidate').length,1);assert.equal(history.messages.find(m=>m.kind==='candidate').content,answer);assert.ok(history.messages.filter(m=>m.kind==='commentary').every(m=>m.attemptId))
 assert.equal(readFileSync(path.join(directory,'app.js'),'utf8'),source);await live.close();console.log('PASS live PostgreSQL/SSE: partial answer arrives before provider completion, public explanations persist, findings precede final answer, Unicode and usage assemble without duplicated text or private reasoning')
 const stop=await task('STREAM_CANCEL'),stopping=await listen(stop.runId);await until(()=>stopping.events.some(e=>e.type==='message.delta'),'cancellable partial');const started=Date.now();await api('/runs/'+stop.runId+'/cancel','POST')
 await until(async()=>{const r=await api('/runs/'+stop.runId);assert.notEqual(r.status,'waiting_reconcile');return r.status==='cancelled'},'cancelled stream',5000);await until(()=>closedTags.has('STREAM_CANCEL'),'provider socket closes',5000);assert.ok(Date.now()-started<5000);assert.equal((await api('/sessions/'+stop.sessionId+'/messages')).messages.filter(m=>m.kind==='candidate').length,0);await stopping.close();console.log('PASS stopping a stalled stream closes upstream promptly, retains unknown billing and saves no unfinished final answer')
 const retry=await task('STREAM_ERROR'),retrying=await listen(retry.runId);await until(()=>retrying.events.some(e=>e.type==='message.delta'),'first failed draft');gates.get('STREAM_ERROR')();await until(async()=>{const r=await api('/runs/'+retry.runId);return r.status==='succeeded'},'retry after interrupted provider',15000)
 await until(()=>retrying.events.some(e=>e.type==='run.succeeded'),'retry completion SSE');assert.ok(retrying.events.some(e=>e.type==='message.interrupted'));assert.equal(new Set(retrying.events.filter(e=>e.type==='message.start').map(e=>e.attemptId)).size,2);const retried=(await api('/sessions/'+retry.sessionId+'/messages')).messages.filter(m=>m.kind==='candidate');assert.deepEqual(retried.map(m=>m.content),['恢复后的完整回答。']);await retrying.close();console.log('PASS broken stream retries with a new attempt id and commits only the recovered answer')
 const after=await task('STREAM_AFTERFAIL'),afterEvents=await listen(after.runId);await until(()=>afterEvents.events.some(e=>e.type==='message.delta'),'first failed attempt');gates.get('STREAM_AFTERFAIL')();await until(()=>new Set(afterEvents.events.filter(e=>e.type==='message.start').map(e=>e.attemptId)).size===2&&gates.has('STREAM_AFTERFAIL'),'second attempt after unknown billing');await api('/runs/'+after.runId+'/cancel','POST');await until(async()=>{const r=await api('/runs/'+after.runId);assert.notEqual(r.status,'waiting_reconcile');return r.status==='cancelled'},'cancel after an earlier model network failure',5000);await afterEvents.close();console.log('PASS an earlier unknown model bill does not block cancellation behind an effect-reconciliation screen')
 const ui=await api('/sessions','POST',{title:'STREAM_UI_SESSION',projectId:project.id},201)
 writeFileSync(path.join(runtime,'streaming-validation.json'),JSON.stringify({tenant,user,base,fixture,projectId:project.id,phaseSessionId:phase.sessionId,uiSessionId:ui.sessionId,phaseRunId:phase.runId,stopRunId:stop.runId,retryRunId:retry.runId,expectedAnswer:answer,backendPassed:true},null,2))
 console.log('READY desktop streaming fixture '+fixture)
 setTimeout(()=>{console.error('fixture expired');process.exit(2)},30*60*1000).unref()
}catch(e){console.error(e.stack);for(const id of activeTasks)try{await api('/runs/'+id+'/cancel','POST')}catch{}for(const release of gates.values())release();server.close();process.exit(1)}
