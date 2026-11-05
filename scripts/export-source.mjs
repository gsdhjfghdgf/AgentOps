import { cpSync, existsSync, readFileSync, writeFileSync, readdirSync, statSync, mkdirSync, mkdtempSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const version=JSON.parse(readFileSync(path.join(root,'agent-web/package.json'),'utf8')).version
const output=path.resolve(process.argv[2]||'D:/Codex/Download')
const runtime=path.join(root,'agent-server/.runtime')
mkdirSync(runtime,{recursive:true});mkdirSync(output,{recursive:true})
const stage=mkdtempSync(path.join(runtime,'source-export-'))
const excluded=new Set(['node_modules','target','dist','release','.runtime','.git','.idea'])
const inputs=['README.md','pom.xml','.gitignore','.env.example','.mvn','docs','scripts','agent-server/pom.xml',
  'agent-server/src','agent-web/README.md','agent-web/src','agent-web/electron','agent-web/tests','agent-web/scripts',
  'agent-web/package.json','agent-web/package-lock.json','agent-web/index.html','agent-web/tsconfig.json','agent-web/vite.config.ts']
for(const relative of inputs){
  const source=path.join(root,relative)
  if(!existsSync(source))throw Error('Missing source input: '+relative)
  cpSync(source,path.join(stage,relative),{recursive:true,filter:file=>{
    const parts=path.relative(root,file).split(path.sep)
    return !parts.some(part=>excluded.has(part))&&!/(\.pfx|\.p12|\.pem|\.key)$/i.test(file)
  }})
}
const configPath='agent-server/src/main/resources/application.yml'
const config=readFileSync(path.join(root,configPath),'utf8'),sensitive=[]
let passwordIndex=0
const sanitized=config.split(/\r?\n/).map(line=>{
  const match=line.match(/^(\s*)(password|api-key|embedding-api-key|master-key|encryption-key|client-secret)\s*:\s*(.*)$/)
  if(!match)return line
  const raw=match[3].replace(/\s+#.*$/,'').trim().replace(/^['"]|['"]$/g,'')
  const variable=raw.match(/^\$\{([^:}]+)(?::([^}]*))?\}$/)
  const value=variable?variable[2]||'':raw
  if(value.length>=12)sensitive.push(value)
  let environment=variable?.[1]
  if(!environment){
    environment=match[2]==='password'?(passwordIndex++===0?'SPRING_DATASOURCE_PASSWORD':'SPRING_DATA_REDIS_PASSWORD')
      :match[2]==='api-key'?'MODEL_API_KEY':match[2].replaceAll('-','_').toUpperCase()
  }else if(match[2]==='password')passwordIndex++
  return match[1]+match[2]+': $'+'{'+environment+':}'
}).join('\n')
let clean=sanitized
for(const value of sensitive)clean=clean.replaceAll(value,'REDACTED')
writeFileSync(path.join(stage,configPath),clean)
function files(directory){
  return readdirSync(directory,{withFileTypes:true}).flatMap(entry=>{
    const file=path.join(directory,entry.name)
    return entry.isDirectory()?files(file):[file]
  })
}
const entries=files(stage).map(file=>{
  const data=readFileSync(file)
  for(const value of sensitive)if(data.includes(Buffer.from(value)))throw Error('Sensitive configuration found in '+path.relative(stage,file))
  return {path:path.relative(stage,file).replaceAll('\\','/'),bytes:data.length,sha256:createHash('sha256').update(data).digest('hex')}
})
writeFileSync(path.join(stage,'SOURCE-MANIFEST.json'),JSON.stringify({
  version,createdAt:new Date().toISOString(),configuration:'Passwords and API keys use empty environment-variable defaults',
  files:entries,
},null,2))
const archive=path.join(output,'AgentOps-source-'+version+'.zip')
const psScript=path.join(runtime,'compress-source.ps1')
writeFileSync(psScript,[
  'param([string]$Source,[string]$Destination)',
  '$ErrorActionPreference = "Stop"',
  'Compress-Archive -Path (Join-Path $Source "*") -DestinationPath $Destination -Force',
].join('\r\n'))
const result=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',psScript,stage,archive],{encoding:'utf8'})
if(result.status!==0)throw Error('Source archive failed: '+result.stderr)
const sha256=createHash('sha256').update(readFileSync(archive)).digest('hex')
writeFileSync(archive+'.sha256',sha256+'  '+path.basename(archive)+'\n')
console.log(JSON.stringify({archive,version,files:entries.length+1,bytes:statSync(archive).size,sha256}))
