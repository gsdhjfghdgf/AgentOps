import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import * as Vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'

const clone=value=>structuredClone(value)
const a={id:'a',versionId:'v-a',name:'日常聊天',url:'https://same.test/v1/chat/completions',model:'model-a',keyConfigured:true}
const b={...a,id:'b',versionId:'v-b',name:'代码助手',model:'model-b'}
const initial=()=>({selectedProfileId:'a',profileId:'v-a',profiles:[clone(a),clone(b)],
  defaultProfile:{id:'default',name:'系统默认',url:'https://default.test/v1',model:'system',keyConfigured:true}})
let moduleNo=0
test('model editor opens the selected saved entry and tests another entry with its own version',async t=>{
  const calls=[],f=await mount('ModelSettings',{post:async(path,body)=>{calls.push({path,body});return{ok:true,message:'OK'}}})
  t.after(f.close)
  assert.equal(f.state.editing,'a');assert.equal(f.state.key,'')
  f.state.choose(b);await f.flush();await f.state.test()
  assert.equal(calls[0].path,'/api/model-settings/profiles/b/test')
  assert.equal(calls[0].body.versionId,'v-b');assert.equal(calls[0].body.apiKey,'')
  assert.equal(f.state.catalog.selectedProfileId,'a')
})
test('saving an added model preserves other models and does not select it implicitly',async t=>{
  let sent
  const f=await mount('ModelSettings',{post:async(path,body)=>{
    sent={path,body};const result=initial();result.profiles.push({...a,id:'c',versionId:'v-c',name:body.name})
    return{...result,savedProfileId:'c'}
  }})
  t.after(f.close)
  f.state.choose();f.state.name='本地助手';f.state.url='http://localhost:11434/v1';f.state.model='local'
  assert.equal(!!f.state.ready,false)
  f.state.clearKey=true;assert.equal(!!f.state.ready,true)
  await f.state.save(false)
  assert.equal(sent.path,'/api/model-settings/profiles');assert.equal(sent.body.activate,false)
  assert.equal(f.state.catalog.profiles.length,3);assert.equal(f.state.catalog.selectedProfileId,'a')
  assert.equal(f.state.editing,'c');assert.deepEqual(f.events,['saved'])
})
test('save and use explicitly selects the saved revision and clears the entered key',async t=>{
  let sent
  const f=await mount('ModelSettings',{put:async(path,body)=>{
    sent={path,body};return{...initial(),selectedProfileId:'b',profileId:'v-b',savedProfileId:'b'}
  }})
  t.after(f.close)
  f.state.choose(b);f.state.key='replacement';await f.flush()
  await f.state.save(true)
  assert.equal(sent.path,'/api/model-settings/profiles/b');assert.equal(sent.body.activate,true)
  assert.equal(sent.body.versionId,'v-b');assert.equal(f.state.key,'')
  assert.equal(f.state.catalog.selectedProfileId,'b')
})
test('only URL, key and model are required; an omitted display name uses the model identifier',async t=>{
  const f=await mount('ModelSettings');t.after(f.close)
  f.state.choose();f.state.url='https://provider.test/v1';f.state.model='only-three-fields';f.state.key='fixture'
  assert.equal(!!f.state.ready,true)
  assert.equal(f.state.payload().name,'only-three-fields')
})
test('switching in the composer persists selection and all entries survive remount and default selection',async t=>{
  let stored=initial();const calls=[]
  const methods={get:async()=>clone(stored),put:async(path,body)=>{
    calls.push({path,body});stored={...stored,selectedProfileId:body.profileId};return clone(stored)
  }}
  const first=await mount('ComposerModelPicker',methods);t.after(first.close)
  await first.state.select('b');await first.flush()
  assert.equal(first.state.model.name,'代码助手')
  assert.deepEqual(calls[0],{path:'/api/model-settings/selection',body:{profileId:'b'}})
  first.close()
  const reopened=await mount('ComposerModelPicker',methods);t.after(reopened.close)
  assert.equal(reopened.state.model.id,'b');assert.equal(reopened.state.catalog.profiles.length,2)
  await reopened.state.select('default');await reopened.flush()
  assert.equal(reopened.state.model.id,'default');assert.equal(reopened.state.catalog.profiles.length,2)
})
test('failed selection keeps the old model and displays the failure',async t=>{
  const f=await mount('ComposerModelPicker',{put:async()=>{throw Error('服务不可用')}});t.after(f.close)
  await f.state.select('b')
  assert.equal(f.state.model.id,'a');assert.equal(f.state.error,'服务不可用');assert.equal(f.state.switching,false)
})
test('identity changes discard a model draft and closing clears the typed key',async t=>{
  const f=await mount('ModelSettings');t.after(f.close)
  f.state.key='private';window.dispatchEvent(new Event('agent:identity-changed'))
  assert.equal(f.state.key,'');assert.deepEqual(f.events,['close'])
  f.state.key='another';f.close();assert.equal(f.state.key,'')
})
test('failed pre-save connection check preserves saved models, selection and the editable draft',async t=>{
  const f=await mount('ModelSettings',{post:async()=>{throw Error('配置未保存：Key 无效（HTTP 401）')}});t.after(f.close)
  f.state.choose();f.state.url='https://fixture.test/v1';f.state.model='fixture';f.state.key='wrong-key'
  await f.state.save(true)
  assert.equal(f.state.catalog.profiles.length,2);assert.equal(f.state.catalog.selectedProfileId,'a')
  assert.equal(f.state.key,'wrong-key');assert.equal(f.state.editing,'new');assert.equal(f.state.failed,true)
  assert.match(f.state.message,/配置未保存/);assert.deepEqual(f.events,[])
})

const dataUrl=code=>'data:text/javascript;base64,'+Buffer.from(code).toString('base64')
async function mount(component,overrides={}) {
  globalThis.window=new EventTarget();globalThis.document=new EventTarget()
  const registry='__modelTest'+moduleNo++,events=[]
  const api={get:async()=>initial(),...overrides};globalThis[registry]=api
  const vueUrl=new URL('../node_modules/vue/index.mjs',import.meta.url).href
  const dependency=dataUrl('export const api=globalThis.'+registry)
  const pure=await readFile(new URL('../src/modelProfiles.ts',import.meta.url),'utf8')
  const models=dataUrl(ts.transpileModule(pure,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText)
  const source=await readFile(new URL('../src/components/'+component+'.vue',import.meta.url),'utf8')
  const descriptor=parse(source).descriptor
  const script=compileScript(descriptor,{id:registry}).content
  const compiled=ts.transpileModule(script,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
    .replaceAll("'vue'",JSON.stringify(vueUrl)).replaceAll('"vue"',JSON.stringify(vueUrl))
    .replace("'../api'",JSON.stringify(dependency)).replace("'../modelProfiles'",JSON.stringify(models))
    .replace("'./AppIcon.vue'",JSON.stringify(dataUrl('export default {}')))
  const module=await import(dataUrl(compiled+'\n// '+registry))
  const renderer=Vue.createRenderer({createElement:()=>({children:[]}),insert:(c,p)=>p.children.push(c),remove(){},
    createText:()=>({}),createComment:()=>({}),setText(){},setElementText(){},parentNode:()=>null,nextSibling:()=>null,patchProp(){}})
  const app=renderer.createApp({...module.default,render:()=>null},{onSaved:()=>events.push('saved'),onClose:()=>events.push('close')})
  app.mount({children:[]})
  const state=app._instance.setupState
  async function flush(){for(let i=0;i<20;i++)await Promise.resolve();await Vue.nextTick()}
  await flush();let closed=false
  return{state,events,flush,close(){if(closed)return;closed=true;app.unmount();delete globalThis[registry]}}
}
