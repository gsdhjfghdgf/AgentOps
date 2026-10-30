// Isolated PostgreSQL engine; never connects to the user's database or starts the VM.
import { PGlite } from '../agent-server/.runtime/pg-validation/node_modules/@electric-sql/pglite/dist/index.js'
import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const db=new PGlite()
const tenant='00000000-0000-0000-0000-000000000001',user='00000000-0000-0000-0000-000000000002',other='00000000-0000-0000-0000-000000000009'
const a='00000000-0000-0000-0000-000000000010',b='00000000-0000-0000-0000-000000000011'
try{
 await db.exec(`CREATE SCHEMA agent_memory;SET search_path TO agent_memory,public;
 CREATE TABLE sessions(tenant_id uuid,user_id uuid,id uuid PRIMARY KEY,kind text,updated_at timestamptz DEFAULT now(),deleted_at timestamptz);
 CREATE TABLE runs(id uuid PRIMARY KEY,tenant_id uuid,user_id uuid,session_id uuid,config_snapshot jsonb,created_at timestamptz);
 CREATE TABLE agent_projects(id uuid PRIMARY KEY,tenant_id uuid,user_id uuid);
 INSERT INTO agent_projects VALUES('${a}','${tenant}','${user}'),('${b}','${tenant}','${other}');`)
 for(let n=1;n<=4;n++)await db.query('INSERT INTO sessions(tenant_id,user_id,id,kind)VALUES($1,$2,$3,$4)',[tenant,user,`10000000-0000-0000-0000-00000000000${n}`,'chat'])
 const run=async(n,config,time)=>db.query('INSERT INTO runs VALUES(gen_random_uuid(),$1,$2,$3,$4,$5)',[tenant,user,`10000000-0000-0000-0000-00000000000${n}`,JSON.stringify(config),time])
 await run(1,{projectId:a},'2026-01-01');await run(2,{projectId:b},'2026-01-01')
 await run(3,{projectId:a},'2026-01-01');await run(3,{},'2026-01-02')
 await db.exec(await readFile(new URL('../agent-server/src/main/resources/db/migration/V18__project_sessions.sql',import.meta.url),'utf8'))
 const rows=(await db.query('SELECT id,project_id FROM sessions ORDER BY id')).rows
 assert.deepEqual(rows.map(r=>r.project_id),[a,null,null,null])
 console.log('PASS V18 migration: latest task project preserved; foreign project excluded; latest ordinary task and empty chats stay ordinary')
 // Same INSERT SELECT predicate used by SessionRepo: only a project owned by the current user may be bound.
 const inserted=await db.query(`INSERT INTO sessions(tenant_id,user_id,id,kind,project_id) SELECT $1,$2,gen_random_uuid(),'chat',id FROM agent_projects WHERE tenant_id=$1 AND user_id=$2 AND id=$3 RETURNING project_id`,[tenant,user,b])
 assert.equal(inserted.rows.length,0)
 console.log('PASS project ownership predicate rejects another user project')
}finally{await db.close()}
