import { API_BASE, identity,headers,parse } from './httpClient'
import {authGeneration} from './authSession'
import type { RunEvent } from '../api'

export function openRunEvents(
  runId: string,
  lastEventSeq: number,
  onEvent: (e: RunEvent) => void,
  onDone: (reason: string) => void,
  onConnection?: (connected: boolean) => void,
): () => void {
  let closed = false
  let cursor = lastEventSeq
  let retry=0,timer:ReturnType<typeof setTimeout>|undefined,wake:(()=>void)|undefined
  let activeReader:ReadableStreamDefaultReader<Uint8Array>|undefined,activeIdle:ReturnType<typeof setTimeout>|undefined
  let controller=new AbortController()
  const generation=authGeneration(),owner=identity.tenantId+':'+identity.userId
  const current=()=>!closed&&generation===authGeneration()&&owner===identity.tenantId+':'+identity.userId
  function close(){closed=true;controller.abort();clearTimeout(timer);clearTimeout(activeIdle);void activeReader?.cancel().catch(()=>{});wake?.()}

  const handle = (e: MessageEvent, fallbackType: string) => {
    if (!current()) return
    let data: RunEvent
    try {
      data = JSON.parse(e.data)
    } catch {
      return
    }
    if(!data||typeof data!=='object')return
    const type = data.type || (e as unknown as { type?: string }).type || fallbackType
    data.type = type
    if (type === 'heartbeat') return
    if (type === 'run.snapshot') {
      if (data.reset === true&&Number.isSafeInteger(data.seq)&&data.seq>=0) cursor = data.seq
    } else {
      if (!Number.isSafeInteger(data.seq) || data.seq <= cursor) return
      cursor = data.seq
    }
    onEvent(data)
    if (
      ['run.succeeded', 'run.failed', 'run.cancelled', 'run.timed_out', 'run.budget_exceeded'].includes(type) ||
      (type === 'run.snapshot' && ['succeeded', 'failed', 'cancelled', 'timed_out', 'budget_exceeded'].includes(String(data.status)))
    ) {
      close()
      onDone(type)
    }
  }

  async function connect(){
    while(current()){
      let reader:ReadableStreamDefaultReader<Uint8Array>|undefined
      let idle:ReturnType<typeof setTimeout>|undefined
      try{
        controller=new AbortController()
        const active=controller
        const refreshIdle=()=>{clearTimeout(idle);idle=setTimeout(()=>active.abort(),45000);activeIdle=idle}
        refreshIdle()
        const response=await fetch(`${API_BASE}/api/runs/${encodeURIComponent(runId)}/events?lastEventSeq=${cursor}`,{headers:headers({Accept:'text/event-stream'}),signal:controller.signal,cache:'no-store'})
        if(!response.ok){await parse(response);throw Error('实时连接失败')}
        if(!response.headers.get('Content-Type')?.includes('text/event-stream')||!response.body)throw Error('实时输出格式不正确')
        if(!current())break
        onConnection?.(true)
        reader=response.body.getReader();activeReader=reader;const text=new TextDecoder(),events=new SseDecoder((raw,type)=>handle({data:raw} as MessageEvent,type))
        while(current()){const chunk=await reader.read();if(chunk.done)break;retry=0;refreshIdle();events.push(text.decode(chunk.value,{stream:true}))}
      }catch(error){
        const status=(error as {status?:number}).status
        if([401,403,404].includes(status||0)){close();onDone(status===401?'connection.unauthorized':'connection.unavailable')}
      }finally{clearTimeout(idle);await reader?.cancel().catch(()=>{});if(activeReader===reader)activeReader=undefined;if(current())onConnection?.(false)}
      if(current())await new Promise<void>(resolve=>{wake=resolve;timer=setTimeout(resolve,Math.min(1000*2**retry++,10000))})
    }
  }
  void connect()
  return close
}

/** Framing is independent of UTF-8 decoding; accepts CRLF and arbitrary chunk boundaries. */
export class SseDecoder {
 private buffer=''
 private data:string[]=[]
 private name='message'
 private size=0
 constructor(private receive:(data:string,event:string)=>void){}
 push(chunk:string){
  this.buffer+=chunk
  if(this.buffer.length>1_000_000)throw Error('实时事件过大')
  let end:number
  while((end=this.buffer.indexOf('\n'))>=0){
   const line=this.buffer.slice(0,end).replace(/\r$/,'');this.buffer=this.buffer.slice(end+1)
   if(!line){if(this.data.length)this.receive(this.data.join('\n'),this.name);this.data=[];this.name='message';this.size=0;continue}
   if(line.startsWith(':'))continue
   const colon=line.indexOf(':'),key=colon<0?line:line.slice(0,colon),value=colon<0?'':line.slice(colon+1).replace(/^ /,'')
   if(key==='data'){this.data.push(value);this.size+=value.length}
   else if(key==='event')this.name=value
   if(this.size>1_000_000)throw Error('实时事件过大')
  }
 }
}

// ==================== 业务操作 ====================
