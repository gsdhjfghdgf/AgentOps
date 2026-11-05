import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {writeFileSync,readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'

// Never run first-account tests against production. A fresh isolated DB/backend must be started first.
const base=process.env.ACCOUNTS_TEST_BASE||'http://127.0.0.1:18080'
assert.equal(new URL(base).port,'18080','Refusing to run registration tests against a production backend')
const password='Fixture-'+randomUUID(),nextPassword='Changed-'+randomUUID(),suffix=randomUUID().slice(0,8)
const alice='alice_'+suffix,bob='用户_'+suffix;let checks=0
const registrationKeyFile=fileURLToPath(new URL('../agent-server/.runtime/accounts-test-registration.key',import.meta.url))
const registrationKey=readFileSync(registrationKeyFile,'utf8').trim()
async function request(path,{method='GET',body,token,expected=200,extra={}}={}){
 if(path==='/auth/register'&&body)body={...body,registrationKey}
 const r=await fetch(base+'/api'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...extra},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)})
 const text=await r.text();assert.equal(r.status,expected,method+' '+path+' unexpected status '+r.status+' '+text.slice(0,180));checks++
 try{return JSON.parse(text)}catch{return text}
}
assert.equal((await request('/auth/bootstrap')).setupRequired,true,'Use a new isolated database for this verification')
await request('/auth/register',{method:'POST',body:{username:alice,displayName:'测试',password:'short'},expected:400})
await request('/auth/register',{method:'POST',body:{username:alice,displayName:'历史工作空间',password,inheritLegacy:true},expected:201})
await request('/auth/register',{method:'POST',body:{username:alice,displayName:'重复',password},expected:409})
await request('/auth/register',{method:'POST',body:{username:'later_'+suffix,displayName:'非法接管',password,inheritLegacy:true},expected:409})
await request('/auth/login',{method:'POST',body:{username:alice,password:'wrong-password'},expected:401})
const first=await request('/auth/login',{method:'POST',body:{username:alice,password,remember:false}}),token=first.accessToken
assert.equal(first.account.tenantId,'11111111-1111-1111-1111-111111111111');assert.equal(first.account.userId,'22222222-2222-2222-2222-222222222222')
await request('/auth/register',{method:'POST',body:{username:bob,displayName:'独立用户',password},expected:201})
const second=await request('/auth/login',{method:'POST',body:{username:bob,password}}),other=second.accessToken
assert.notEqual(second.account.userId,first.account.userId);assert.notEqual(second.account.tenantId,first.account.tenantId)
await request('/sessions',{extra:{'X-Tenant-Id':first.account.tenantId,'X-User-Id':first.account.userId},expected:401})
await request('/auth/me',{token:'bad-token',expected:401})
assert.equal((await request('/auth/me',{token,extra:{'X-Tenant-Id':second.account.tenantId,'X-User-Id':second.account.userId}})).account.id,first.account.id)
const session=await request('/sessions',{method:'POST',token,body:{title:'认证隔离测试'},expected:201})
await request('/sessions/'+session.sessionId,{token:other,expected:404})
await request('/sessions/'+session.sessionId+'/export',{token:other,expected:404})
await request('/sessions',{method:'POST',token,body:{title:'a'.repeat(121)},expected:400})
await request('/sessions/'+session.sessionId+'/runs',{method:'POST',token,body:{message:'a'.repeat(32001)},expected:400})
await request('/operations/mcp',{method:'POST',token,body:{name:'无协议端点',url:'relative-path'},expected:400})
await request('/projects/'+randomUUID()+'/preview-origin',{method:'PUT',token,body:{origin:'http:opaque-address'},expected:400})
await request('/memories',{method:'POST',token,body:{memoryKey:'test',kind:'fact',content:'测试内容',validForSeconds:9223372036854775000},expected:400})
await request('/auth/me',{method:'PUT',token,body:{displayName:'新的称呼'}})
assert.equal((await request('/auth/me',{token})).account.displayName,'新的称呼')
const extra=await request('/auth/login',{method:'POST',body:{username:alice,password}})
const devices=(await request('/auth/sessions',{token})).sessions;assert.equal(devices.length,2)
const extraSession=devices.find(s=>!s.current).id
await request('/auth/sessions/'+extraSession,{method:'DELETE',token:other})
await request('/auth/me',{token:extra.accessToken})
await request('/auth/sessions/'+extraSession,{method:'DELETE',token})
await request('/auth/me',{token:extra.accessToken,expected:401})
await request('/auth/password',{method:'PUT',token,body:{currentPassword:'incorrect-password',newPassword:nextPassword},expected:400})
await request('/auth/me',{token})
await request('/auth/password',{method:'PUT',token,body:{currentPassword:password,newPassword:nextPassword}})
await request('/auth/me',{token,expected:401})
await request('/auth/login',{method:'POST',body:{username:alice,password},expected:401})
const relogin=await request('/auth/login',{method:'POST',body:{username:alice,password:nextPassword}})
await request('/auth/logout',{method:'POST',token:relogin.accessToken})
await request('/auth/me',{token:relogin.accessToken,expected:401})
for(let i=0;i<12;i++)await request('/auth/login',{method:'POST',body:{username:'missing_'+suffix,password},expected:401})
await request('/auth/login',{method:'POST',body:{username:'missing_'+suffix,password},expected:429})
const malicious=await fetch(base+'/api/auth/bootstrap',{headers:{Origin:'https://untrusted.example'}});assert.equal(malicious.status,403);checks++
// Leave a test-only second account for desktop UI verification; no user's credentials are involved.
writeFileSync(new URL('../agent-server/.runtime/accounts-validation.json',import.meta.url),JSON.stringify({passed:true,checks,base,username:bob,password,fixture:true,registrationKeyFile},null,2))
console.log(JSON.stringify({passed:true,checks,legacyOwnerPreserved:true,spoofedIdentityRejected:true,crossAccountIsolated:true,passwordRevocation:true,durableRateLimit:true}))
