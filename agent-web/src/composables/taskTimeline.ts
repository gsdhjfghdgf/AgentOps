import type { MessageItem } from '../api'

export type Activity = { id: string; name: string; arguments: unknown; result?: string }
export interface WebSource {title:string;url:string;domain:string;snippet:string;dateLabel:string}
export interface WebSourceGroup {query:string;searchedAt:string;results:WebSource[];warnings:string[];legacy:boolean}
export type TimelineEntry = { key: string; type: 'text' | 'supplement' | 'commentary' | 'milestone'; content: string;kind?:string } |
  { key: string; type: 'activities'; activities: Activity[] } | {key:string;type:'sources';sources:WebSourceGroup}

/** Only the last assistant answer belongs outside the execution history. Earlier drafts remain inspectable. */
export function splitTaskTimeline(entries:TimelineEntry[]){
 const last=entries.at(-1)
 return last?.type==='text'?{process:entries.slice(0,-1),answer:last}:{process:entries,answer:null}
}

export function extractWebSources(content:string,query:string):WebSourceGroup|null{
 try{
  const parsed=JSON.parse(content),legacy=Array.isArray(parsed),rows=legacy?parsed:parsed?.results
  if(!Array.isArray(rows))return null
  const results:WebSource[]=[]
  for(const row of rows.slice(0,8))try{const url=new URL(row.url);if(!['https:','http:'].includes(url.protocol)||url.username||url.password||typeof row.title!=='string')continue
   results.push({title:row.title,url:url.href,domain:url.hostname,snippet:typeof row.snippet==='string'?row.snippet:'',dateLabel:typeof row.dateLabel==='string'?row.dateLabel:''})
  }catch{/* Invalid result links never become clickable controls. */}
  return{query:legacy?query:typeof parsed.query==='string'?parsed.query:query,searchedAt:typeof parsed.searchedAt==='string'?parsed.searchedAt:'',results,warnings:legacy?['旧版来源，未执行新版相关性筛选。']:(Array.isArray(parsed.warnings)?parsed.warnings.filter((w:unknown)=>typeof w==='string'):[]),legacy}
 }catch{return null}
}

export function taskTimeline(messages: MessageItem[]): TimelineEntry[] {
  const entries: TimelineEntry[]=[]
  const calls=new Map<string,Activity>()
  const milestones=new Set<string>()
  const sourcesShown=new Set<string>()
  function activity(item: Activity) {
    const last=entries.at(-1)
    if(last?.type==='activities')last.activities.push(item)
    else entries.push({key:`activity-${item.id}`,type:'activities',activities:[item]})
  }
  for(const m of messages) {
    if(m.role==='tool') {
      const call=m.toolCallId?calls.get(m.toolCallId):undefined
      if(call){call.result=m.content
        if(call.name==='web_search'&&!sourcesShown.has(call.id)){
          const group=extractWebSources(m.content,String((call.arguments as {query?:string}|null)?.query||''))
          if(group){entries.push({key:`sources-${call.id}`,type:'sources',sources:group});sourcesShown.add(call.id)}
        }
        if(call.name==='task_notes'&&!milestones.has(call.id))try{
          const result=JSON.parse(m.content),args=call.arguments as {content?:unknown}|null
          if(result.saved===true&&typeof args?.content==='string'&&args.content.trim()){
            entries.push({key:`finding-${call.id}`,type:'milestone',content:args.content});milestones.add(call.id)
          }
        }catch{/* A failed or malformed tool response is never presented as saved findings. */}
      }
      else activity({id:`result-${m.seq}`,name:'工具返回',arguments:null,result:m.content})
      continue
    }
    if(m.content)entries.push({key:`message-${m.seq}`,type:m.role==='user'?'supplement':m.kind==='commentary'||m.toolCalls?.length?'commentary':'text',content:m.content,kind:m.kind})
    for(const t of m.toolCalls || []) {
      const item:Activity={id:t.id,name:t.name,arguments:t.arguments}
      calls.set(t.id,item);activity(item)
    }
  }
  return entries
}
