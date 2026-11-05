import test from 'node:test'
import assert from 'node:assert/strict'
import {loadTypeScript} from './helpers/load-typescript.mjs'
const turn=()=>new Promise(resolve=>setImmediate(resolve))

test('SSE uses incremental framing and preserves multibyte characters across network chunks',async()=>{
 globalThis.localStorage={getItem:()=>null,setItem(){}};globalThis.window={}
 const {openRunEvents}=await loadTypeScript(new URL('../src/services/runEvents.ts',import.meta.url))
 let controller
 globalThis.fetch=async()=>new Response(new ReadableStream({start(c){controller=c}}),{headers:{'Content-Type':'text/event-stream'}})
 const received=[];const close=openRunEvents('run',0,event=>received.push(event),()=>{})
 await turn()
 const bytes=new TextEncoder().encode('event: message.delta\r\ndata: {"seq":1,"delta":"中文😀"}\r\n\r\n')
 for(const byte of bytes)controller.enqueue(new Uint8Array([byte]))
 await turn();close()
 assert.equal(received.length,1);assert.equal(received[0].delta,'中文😀')
})
test('expired event authentication stops reconnect attempts',async()=>{
 globalThis.localStorage={getItem:()=>null,setItem(){}};globalThis.window={}
 const {openRunEvents}=await loadTypeScript(new URL('../src/services/runEvents.ts',import.meta.url))
 let calls=0;globalThis.fetch=async()=>{calls++;return new Response('{"message":"登录已失效"}',{status:401})}
 const done=[];const close=openRunEvents('run',0,()=>{},reason=>done.push(reason))
 await turn();close();assert.equal(calls,1);assert.deepEqual(done,['connection.unauthorized'])
})
test('login tokens use session storage and an OS vault rather than localStorage',async()=>{
 const values=new Map(),saved=[]
 globalThis.localStorage={setItem(){throw Error('must not persist bearer in localStorage')}}
 globalThis.sessionStorage={getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)}
 globalThis.window={desktopEnv:{readLoginToken:async()=>null,saveLoginToken:async value=>saved.push(value)},dispatchEvent(){}}
 const auth=await loadTypeScript(new URL('../src/services/authSession.ts',import.meta.url))
 await auth.restoreToken('http://backend');await auth.storeToken('a'.repeat(43),true)
 assert.equal(auth.accessToken(),'a'.repeat(43));assert.deepEqual(saved,['a'.repeat(43)])
 auth.expireSession();await turn();assert.equal(auth.accessToken(),'');assert.equal(saved.at(-1),null);assert.equal(values.size,0)
 delete globalThis.sessionStorage
})
test('the SSE decoder rejects oversized events',async()=>{
 globalThis.localStorage={getItem:()=>null,setItem(){}};globalThis.window={}
 const {SseDecoder}=await loadTypeScript(new URL('../src/services/runEvents.ts',import.meta.url))
 const decoder=new SseDecoder(()=>{})
 assert.throws(()=>decoder.push('data: '+'a'.repeat(1_000_001)),/过大/)
})
