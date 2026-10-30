// Test-only PostgreSQL endpoint. Never points at the user's VM or production data.
import {PGlite} from '../agent-server/.runtime/pg-validation/node_modules/@electric-sql/pglite/dist/index.js'
import {PGLiteSocketServer} from '../agent-server/.runtime/pg-validation/node_modules/@electric-sql/pglite-socket/dist/index.js'
import {vector} from '../agent-server/.runtime/pg-validation/node_modules/@electric-sql/pglite-pgvector/dist/index.js'
import {readFile,readdir} from 'node:fs/promises'
const db=await PGlite.create({extensions:{vector}})
const migration=new URL('../agent-server/src/main/resources/db/migration/',import.meta.url)
const files=(await readdir(migration)).filter(n=>/^V\d+__.*\.sql$/.test(n)).sort((a,b)=>Number(a.match(/^V(\d+)/)[1])-Number(b.match(/^V(\d+)/)[1]))
for(const file of files){await db.exec(await readFile(new URL(file,migration),'utf8'));console.log('Applied '+file)}
const server=new PGLiteSocketServer({db,port:55432,host:'127.0.0.1',maxConnections:8})
await server.start();console.log('Isolated validation PostgreSQL ready on 55432; all migrations applied.')
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{await server.stop();await db.close();process.exit(0)})
