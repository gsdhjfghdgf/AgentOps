import{createServer}from'node:http'
import{randomUUID}from'node:crypto'
import{writeFileSync}from'node:fs'
import assert from'node:assert/strict'
const base='http://127.0.0.1:8080/api',tenant=randomUUID(),user=randomUUID(),headers={'X-Tenant-Id':tenant,'X-User-Id':user,'Content-Type':'application/json'},tasks=[]
const isSpring=url=>{const host=new URL(url).hostname;return host==='spring.io'||host.endsWith('.spring.io')}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
const server=createServer(async(req,res)=>{try{let raw='';for await(const chunk of req)raw+=chunk;const b=JSON.parse(raw);let msg={content:'[]'},finish='stop';if(b.tools){
 const goal=JSON.parse(b.messages.find(m=>m.role==='user').content),out=b.messages.filter(m=>m.role==='tool');let name='finish_task',args={answer:'已检索候选来源。请展开搜索来源列表核对标题、摘要与原文；检索结果本身不代表事实已确认。'}
 if(!out.length){name='web_search';args={query:goal.query}}
 else if(goal.read){
  const parsed=out.map(m=>{try{return JSON.parse(m.content)}catch{return{status:'tool_error'}}}),sources=parsed.filter(p=>Array.isArray(p.results)).flatMap(p=>p.results).filter(r=>isSpring(r.url))
  const tried=b.messages.flatMap(m=>m.tool_calls||[]).filter(c=>c.function.name==='web_read').map(c=>JSON.parse(c.function.arguments).url)
  const success=parsed.some(p=>p.status==='ok'&&typeof p.content==='string'&&/CORS|cross-origin|Cross Origin/.test(p.content))
  const remaining=sources.filter(r=>!tried.includes(r.url)).sort((a,b)=>rank(b.url)-rank(a.url)),refined=parsed.some(p=>p.query==='CORS site:spring.io')
  if(!success&&!remaining.length&&!refined){name='web_search';args={query:'CORS site:spring.io'}}
  else if(!success&&tried.length<3&&remaining.length){name='web_read';args={url:remaining[0].url}}
 }
 msg={content:'按完整主体检索并保留可核验的原文来源。',tool_calls:[{id:randomUUID(),type:'function',function:{name,arguments:JSON.stringify(args)}}]};finish='tool_calls'
 }res.setHeader('Content-Type','application/json');res.end(JSON.stringify({choices:[{finish_reason:finish,message:msg}],usage:{prompt_tokens:70,completion_tokens:30}}))}catch(e){console.error(e.stack);res.statusCode=500;res.end()}})
await new Promise(r=>server.listen(0,'127.0.0.1',r))
async function api(p,m='GET',v){const r=await fetch(base+p,{method:m,headers,...(v===undefined?{}:{body:JSON.stringify(v)})});const text=await r.text();assert.ok(r.ok,p+':'+text.slice(0,300));return text?JSON.parse(text):null}
async function run(query,read=false){const session=await api('/sessions','POST',{title:'网页检索验证：'+query}),task=await api('/sessions/'+session.sessionId+'/runs','POST',{message:JSON.stringify({query,read})});tasks.push(task.runId);const approvals=new Set();let state
 for(let i=0;i<600;i++){state=await api('/runs/'+task.runId);if(state.status==='succeeded')break;assert.ok(!['failed','timed_out','budget_exceeded','waiting_reconcile','waiting_user'].includes(state.status),JSON.stringify(state));if(state.status==='waiting_approval')for(const a of(await api('/runs/'+task.runId+'/approvals')).approvals){if(approvals.has(a.approvalId))continue;approvals.add(a.approvalId);assert.equal(approvals.size,1,'network permission is shared across search and page reads');await api('/approvals/'+a.approvalId+'/decision','POST',{decision:'approved'})}await delay(100)}
 assert.equal(state.status,'succeeded');const messages=(await api('/sessions/'+session.sessionId+'/messages?limit=100')).messages,tools=messages.filter(m=>m.role==='tool');return{...task,sessionId:session.sessionId,tools,approvalCount:approvals.size}
}
try{
 writeFileSync('agent-server/.runtime/web-search-inflight.json',JSON.stringify({tenant,user,base}));await api('/memory-settings','PUT',{enabled:false});await api('/model-settings','PUT',{url:'http://127.0.0.1:'+server.address().port+'/v1',model:'web-search-fixture',apiKey:'',clearKey:true})
 const cn=await run('张雪峰 去世 传闻'),chinese=JSON.parse(cn.tools[0].content);assert.equal(chinese.status,'ok',JSON.stringify(chinese));assert.ok(chinese.results.length>0);assert.equal(chinese.providers.length,3);assert.ok(chinese.results.every(r=>(r.title+' '+r.snippet).includes('张雪峰')));assert.ok(chinese.results.every(r=>!/^张[（(]|^张姓/.test(r.title)));assert.equal(new Set(chinese.results.map(r=>r.url)).size,chinese.results.length);assert.ok(chinese.searchedAt);assert.ok(chinese.evidenceNotice.includes('未核验'))
 console.log('PASS live Chinese search: exact full subject, multiple providers, original links, deduplication, metadata and explicit evidence limits')
 const en=await run('Spring Boot CORS official documentation',true),english=JSON.parse(en.tools[0].content),all=en.tools.map(t=>{try{return JSON.parse(t.content)}catch{return{status:'tool_error',message:t.content}}}),page=all.find(t=>t.status==='ok'&&typeof t.content==='string'&&/CORS|cross-origin|Cross Origin/.test(t.content)),official=all.filter(t=>Array.isArray(t.results)).flatMap(t=>t.results);assert.equal(english.status,'ok',JSON.stringify(english));assert.ok(official.some(r=>isSpring(r.url)),JSON.stringify(all));assert.ok(page,JSON.stringify(all));assert.equal(page.status,'ok',JSON.stringify(page));assert.ok(page.content.length>100);assert.match(page.content,/CORS|cross-origin|Cross Origin/);assert.equal(en.approvalCount,1)
 console.log('PASS live technical search and article extraction: original Spring documentation, meaningful body and one shared network approval')
 writeFileSync('agent-server/.runtime/web-search-validation.json',JSON.stringify({tenant,user,base,cnSessionId:cn.sessionId,cnRunId:cn.runId,enRunId:en.runId,cnResults:chinese.results.length,cnProviders:chinese.providers,englishResults:english.results.length,articleUrl:page.url,articleCharacters:page.content.length,passed:true},null,2))
}catch(e){console.error(e.stack);for(const id of tasks)try{const state=await api('/runs/'+id);if(!['succeeded','failed','cancelled'].includes(state.status))await api('/runs/'+id+'/cancel','POST')}catch{}process.exitCode=1}finally{server.close()}
function rank(url){return url.includes('/guides/')?10:url.includes('/reference/')?8:url.includes('/current/')?6:url.includes('cors')?4:0}
