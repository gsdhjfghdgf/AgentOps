import assert from 'node:assert/strict'
import {readFileSync,writeFileSync} from 'node:fs'
import {randomUUID} from 'node:crypto'
const fixture=JSON.parse(readFileSync(new URL('../agent-server/.runtime/accounts-validation.json',import.meta.url),'utf8'))
assert.ok(fixture.fixture);assert.equal(new URL(fixture.base).port,'18080','Never run attack fixtures against production')
const base=fixture.base,key=readFileSync(fixture.registrationKeyFile,'utf8').trim();let checks=0
async function call(path,{method='GET',body,token,status=200,headers={}}={}){
 const r=await fetch(base+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)})
 assert.equal(r.status,status,method+' '+path+' returned '+r.status);checks++;return r
}
await call('/api/auth/register',{method:'POST',body:{username:'evil_'+randomUUID().slice(0,8),displayName:'恶意接管',password:'attacker-password',inheritLegacy:true,registrationKey:'x'.repeat(43)},status:403,headers:{Origin:'null'}})
for(const path of ['/%61pi/sessions','/api/sessions','/api/runs/'+randomUUID()+'/events?tenantId=11111111-1111-1111-1111-111111111111&userId=22222222-2222-2222-2222-222222222222','/actuator/metrics'])await call(path,{status:401,headers:{'X-Tenant-Id':'11111111-1111-1111-1111-111111111111','X-User-Id':'22222222-2222-2222-2222-222222222222'}})
for(const path of ['/api;ignored/sessions','/api//sessions','/api/%252fsessions','/api/%5csessions'])await call(path,{status:400})
const login=await(await call('/api/auth/login',{method:'POST',body:{username:fixture.username,password:fixture.password}})).json(),token=login.accessToken
const title="x'); DROP TABLE user_accounts; --"
const session=await(await call('/api/sessions',{method:'POST',token,body:{title},status:201})).json()
assert.equal((await(await call('/api/sessions/'+session.sessionId,{token})).json()).title,title)
assert.equal((await(await call('/api/auth/me',{token})).json()).account.username,fixture.username)
await call('/api/auth/me',{method:'PUT',token,body:{displayName:'正常名字',userId:randomUUID(),tenantId:randomUUID(),isAdmin:true}})
assert.equal((await(await call('/api/auth/me',{token})).json()).account.id,login.account.id)
const big=await fetch(base+'/api/auth/me',{method:'PUT',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({displayName:'a'.repeat(270000)})});assert.equal(big.status,413);checks++
const xml=await fetch(base+'/api/auth/me',{method:'PUT',headers:{Authorization:'Bearer '+token,'Content-Type':'application/xml'},body:'<!DOCTYPE x [<!ENTITY e SYSTEM "file:///private-canary">]><x>&e;</x>'});assert.equal(xml.status,415);checks++
const response=await call('/api/auth/me',{token});assert.equal(response.headers.get('x-content-type-options'),'nosniff');assert.equal(response.headers.get('x-frame-options'),'DENY');assert.equal(response.headers.get('cache-control'),'no-store')
const attacker='other_'+randomUUID().slice(0,8),password='Fixture-'+randomUUID()
await call('/api/auth/register',{method:'POST',body:{username:attacker,displayName:'其他测试用户',password,registrationKey:key},status:201})
const other=(await(await call('/api/auth/login',{method:'POST',body:{username:attacker,password}})).json()).accessToken
await call('/api/sessions/'+session.sessionId,{token:other,status:404})
const run=await(await call('/api/sessions/'+session.sessionId+'/runs',{method:'POST',token,body:{message:'你好',mode:'chat'},status:201})).json()
const data=new FormData();data.append('file',new Blob(['PRIVATE_ARTIFACT_CANARY'],{type:'text/plain'}),'安全验证.txt')
const upload=await fetch(base+'/api/file-tools/runs/'+run.runId+'/upload',{method:'POST',headers:{Authorization:'Bearer '+token},body:data});assert.equal(upload.status,200);checks++
const artifact=(await upload.json()).artifact.objectId
await call('/api/artifacts/'+artifact+'/download',{token:other,status:404})
assert.equal(await(await call('/api/artifacts/'+artifact+'/download',{token})).text(),'PRIVATE_ARTIFACT_CANARY')
await call('/api/auth/logout-all',{method:'POST',token:other});await call('/api/auth/me',{token:other,status:401})
const result={passed:true,checks,localRegistrationProtected:true,encodedRouteBypassBlocked:true,sqlPayloadStoredAsData:true,identityFieldsIgnored:true,xmlRejected:true,oversizedBodyRejected:true,crossAccountArtifactBlocked:true,securityHeaders:true}
writeFileSync(new URL('../agent-server/.runtime/security-validation.json',import.meta.url),JSON.stringify(result,null,2));console.log(JSON.stringify(result))
