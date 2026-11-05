import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'

// Real 65s HTTP fixtures exercise production wiring, including the underlying
// transport, without spending provider tokens or changing any saved profile.
const seconds=65, timers=new Set()
const server=createServer(async(request,response)=>{
 let raw='';for await(const chunk of request)raw+=chunk
 const body=JSON.parse(raw),later=(fn,ms)=>{const timer=setTimeout(()=>{timers.delete(timer);fn()},ms);timers.add(timer)}
 const write=event=>response.write('data: '+JSON.stringify(event)+'\n\n')
 function finish(){
  response.writeHead(200,{'Content-Type':'text/event-stream'})
  write({choices:[{delta:{tool_calls:[{index:0,id:'check',function:{name:'finish_task',arguments:'{"answer":"OK"}'}}]}}]})
  write({choices:[{delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:10,completion_tokens:10}})
  response.end('data: [DONE]\n\n')
 }
 if(body.model==='delayed-first-byte'){later(finish,seconds*1000);return}
 response.writeHead(200,{'Content-Type':'text/event-stream'})
 write({choices:[{delta:{tool_calls:[{index:0,id:'check',function:{name:'finish_task',arguments:'{"answer":"'}}]}}]})
 const tick=setInterval(()=>{if(!response.destroyed)write({choices:[{delta:{tool_calls:[{index:0,function:{arguments:'x'}}]}}]})},1000)
 response.on('close',()=>clearInterval(tick))
 later(()=>{clearInterval(tick);write({choices:[{delta:{tool_calls:[{index:0,function:{arguments:'"}'}}]}}]});write({choices:[{delta:{},finish_reason:'tool_calls'}],usage:{prompt_tokens:10,completion_tokens:80}});response.end('data: [DONE]\n\n')},seconds*1000)
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
try {
 const endpoint='http://127.0.0.1:'+server.address().port+'/v1/chat/completions'
 const cases=await Promise.all(['delayed-first-byte','active-stream'].map(async model=>{
  const started=Date.now()
  const response=await fetch('http://127.0.0.1:8080/api/model-settings/test',{
   method:'POST',headers:{'Content-Type':'application/json','X-Tenant-Id':randomUUID(),'X-User-Id':randomUUID()},
   body:JSON.stringify({url:endpoint,apiKey:'',model,clearKey:true}),signal:AbortSignal.timeout(85000)})
  const result=await response.json();assert.equal(response.status,200);assert.equal(result.ok,true,JSON.stringify(result))
  return {case:model,milliseconds:Date.now()-started,passed:true}
 }))
 const result={passed:true,seconds,cases,realBackend:true,providerTokensSpent:0,savedProfilesChanged:false}
 writeFileSync(new URL('../agent-server/.runtime/stream-timeout-live.json',import.meta.url),JSON.stringify(result,null,2))
 console.log(JSON.stringify(result))
}finally{for(const timer of timers)clearTimeout(timer);server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
