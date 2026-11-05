import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import * as Vue from 'vue'
import ts from 'typescript'

const url=source=>'data:text/javascript;base64,'+Buffer.from(source).toString('base64')
const transpile=source=>ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
let sequence=0
async function mount(fetcher){
 const name='__previewFixture'+sequence++,transport={API_BASE:'http://backend.test',identity:{tenantId:'t',userId:'u'},headers:()=>({'X-Tenant-Id':'t','X-User-Id':'u'})}
 globalThis[name]=transport
 const source=await readFile(new URL('../src/composables/usePresentationPreview.ts',import.meta.url),'utf8')
 const dependency=url('export const {API_BASE,identity,headers}=globalThis.'+name)
 const vue=new URL('../node_modules/vue/index.mjs',import.meta.url).href
 const code=transpile(source).replaceAll("'vue'",JSON.stringify(vue)).replace("'../services/httpClient'",JSON.stringify(dependency))
 const module=await import(url(code+'\n// '+name))
 const originalFetch=globalThis.fetch,oldCreate=URL.createObjectURL,oldRevoke=URL.revokeObjectURL
 globalThis.fetch=fetcher
 let count=0;const revoked=[]
 URL.createObjectURL=()=>`blob:preview-${++count}`;URL.revokeObjectURL=value=>revoked.push(value)
 let state
 const renderer=Vue.createRenderer({createElement:()=>({children:[]}),insert:(c,p)=>p.children.push(c),remove(){},createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null,patchProp(){}})
 const app=renderer.createApp({setup(){state=module.usePresentationPreview(()=> 'deck-id');return()=>null}})
 app.mount({children:[]});let closed=false
 return{state,transport,revoked,close(){if(closed)return;closed=true;app.unmount();globalThis.fetch=originalFetch;URL.createObjectURL=oldCreate;URL.revokeObjectURL=oldRevoke;delete globalThis[name]}}
}
const png=()=>new Response(new Blob(['image'],{type:'image/png'}),{status:200})

test('PPT delivery keeps only the latest revision even when the model changes filenames',async()=>{
 const source=await readFile(new URL('../src/fileTools.ts',import.meta.url),'utf8')
 const {latestPresentations}=await import(url(transpile(source)))
 const artifacts=[{id:'draft',name:'报告.pptx'},{id:'image',name:'第一页.png'},{id:'other',name:'附录.pptx'},{id:'final',name:'报告.PPTX'}]
 assert.deepEqual(latestPresentations(artifacts).map(a=>a.id),['final'])
 assert.deepEqual(latestPresentations([{id:'six',name:'报告-6页.pptx'},{id:'ten',name:'报告-10页.pptx'},{id:'preview',name:'幻灯片-1.png'}]).map(a=>a.id),['ten'])
 assert.deepEqual(latestPresentations([{id:'preview',name:'幻灯片-1.png'}]),[])
 assert.equal(artifacts.length,4)
 assert.deepEqual(artifacts.map(a=>a.id),['draft','image','other','final'])
})

test('all new file tools have readable names and distinct categories',async()=>{
 const source=await readFile(new URL('../src/fileTools.ts',import.meta.url),'utf8')
 const {fileToolLabels,toolCategory}=await import(url(transpile(source)))
 assert.equal(Object.keys(fileToolLabels).length,24)
 assert.equal(toolCategory('presentation_render'),'presentations')
 assert.equal(toolCategory('pdf_extract'),'documents')
 assert.equal(toolCategory('data_chart'),'spreadsheets')
 assert.equal(toolCategory('artifact_list'),'files')
 assert.equal(toolCategory('archive_create'),'archives')
})
test('PPT previews carry owner headers and revoke replaced image URLs',async t=>{
 const calls=[];const f=await mount(async(...args)=>{calls.push(args);return png()});t.after(f.close)
 await f.state.load(1);assert.equal(f.state.page.value,1);assert.equal(f.state.url.value,'blob:preview-1')
 await f.state.load(2);assert.equal(f.state.page.value,2);assert.equal(f.state.url.value,'blob:preview-2')
 assert.equal(calls[0][0],'http://backend.test/api/file-tools/artifacts/deck-id/slides/1')
 assert.equal(calls[0][1].headers['X-User-Id'],'u');assert.ok(calls[0][1].signal)
 assert.deepEqual(f.revoked,['blob:preview-1'])
 f.close();assert.deepEqual(f.revoked,['blob:preview-1','blob:preview-2'])
})
test('a late previous page cannot overwrite the current preview',async t=>{
 let first
 const f=await mount(async path=>path.endsWith('/1')?new Promise(resolve=>{first=resolve}):png());t.after(f.close)
 const old=f.state.load(1);await f.state.load(2);first(png());await old
 assert.equal(f.state.page.value,2);assert.equal(f.state.url.value,'blob:preview-1');assert.equal(f.state.loading.value,false)
})
test('failed page keeps the last valid image and page number with a retryable error',async t=>{
 const f=await mount(async path=>path.endsWith('/1')?png():new Response('failure',{status:500}));t.after(f.close)
 await f.state.load(1);await f.state.load(2)
 assert.equal(f.state.page.value,1);assert.equal(f.state.url.value,'blob:preview-1');assert.match(f.state.error.value,/500/)
})
test('responses for a previous identity are discarded',async t=>{
 let finish
 const f=await mount(()=>new Promise(resolve=>{finish=resolve}));t.after(f.close)
 const pending=f.state.load(1);f.transport.identity.userId='another';finish(png());await pending
 assert.equal(f.state.url.value,'');assert.equal(f.state.loading.value,false)
})
test('page label changes only after its new image has decoded',async t=>{
 const originalImage=globalThis.Image,decoded=[]
 globalThis.Image=class{decode(){return new Promise(resolve=>decoded.push(resolve))}}
 t.after(()=>{globalThis.Image=originalImage})
 const f=await mount(async()=>png());t.after(f.close)
 const pending=f.state.load(2)
 for(let i=0;i<30&&!decoded.length;i++)await new Promise(resolve=>setImmediate(resolve))
 assert.equal(decoded.length,1);assert.equal(f.state.page.value,1);assert.equal(f.state.url.value,'')
 decoded[0]();await pending;assert.equal(f.state.page.value,2);assert.equal(f.state.url.value,'blob:preview-1')
})
