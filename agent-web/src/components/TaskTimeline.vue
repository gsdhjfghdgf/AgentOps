<script setup lang="ts">
import { computed,ref,useSlots,watch } from 'vue'
import type { MessageItem } from '../api'
import { taskTimeline,splitTaskTimeline } from '../composables/taskTimeline'
import AppIcon from './AppIcon.vue'
import SearchSources from './SearchSources.vue'
const props=defineProps<{messages: MessageItem[]; active: boolean;status?:string;liveCommentary?:string;answerStreaming?:boolean;duration?:number; render: (text:string)=>string; toolLabel:(name:string)=>string}>()
const emit=defineEmits<{answerStarted:[]}>()
const slots=useSlots()
const entries=computed(()=>taskTimeline(props.messages))
const parts=computed(()=>splitTaskTimeline(entries.value))
const hasAnswer=computed(()=>!!props.answerStreaming||!!parts.value.answer)
const expanded=ref(false)
const operations=computed(()=>parts.value.process.reduce((sum,e)=>sum+(e.type==='activities'?e.activities.length:0),0))
const elapsed=computed(()=>{const seconds=Math.max(0,Math.floor(props.duration||0));return seconds>=60?`${Math.floor(seconds/60)}分${seconds%60?seconds%60+'秒':''}`:`${seconds}秒`})
watch(()=>[props.active,hasAnswer.value] as const,([active,answer],previous)=>{
 if(answer&&!previous?.[1]){expanded.value=false;emit('answerStarted')}
 else if(active&&!answer&&(!previous?.[0]||previous[1]))expanded.value=true
 else if(!active&&!answer)expanded.value=false
},{immediate:true})
function toggle(event:Event){expanded.value=(event.currentTarget as HTMLDetailsElement).open}
function activityStatus(result: string|undefined){return result!==undefined?'已返回':props.active?'执行中':'结果未确认'}
</script>
<template>
  <div class="task-timeline">
   <details v-if="parts.process.length||liveCommentary||slots.details" class="task-process" :open="expanded" @toggle="toggle">
    <summary><AppIcon name="activity" :size="14" /><span>{{ active&&!hasAnswer?'正在执行':'执行过程' }}</span><span v-if="duration!=null" class="process-elapsed">· 用时 {{ elapsed }}</span><span v-if="operations" class="process-count">· {{ operations }} 项操作</span><AppIcon name="chevron" :size="12" class="process-chevron" /></summary>
    <div class="process-content">
    <div v-for="entry in parts.process" :key="entry.key" class="timeline-entry" :class="entry.type">
      <template v-if="entry.type==='text'"><div class="progress-caption">中间生成内容</div><div class="msg-body" v-html="render(entry.content)"></div></template>
      <div v-else-if="entry.type==='commentary'" class="public-commentary"><AppIcon name="activity" :size="14" /><div class="msg-body" v-html="render(entry.content)"></div></div>
      <section v-else-if="entry.type==='milestone'" class="stage-finding"><header><AppIcon name="check" :size="15" /><strong>阶段发现</strong><span>Agent 记录 · 可展开执行过程核对证据</span></header><div class="msg-body" v-html="render(entry.content)"></div></section>
      <div v-else-if="entry.type==='supplement'" class="task-supplement"><AppIcon name="plus" :size="13" /><span>补充要求</span><div v-html="render(entry.content)"></div></div>
      <SearchSources v-else-if="entry.type==='sources'" :value="entry.sources" />
      <details v-else-if="entry.type==='activities'" class="activity-batch">
        <summary><AppIcon name="activity" :size="15" /><span>{{ entry.activities.some(a=>a.result===undefined) && active ? '正在执行' : '执行过程' }} · {{ entry.activities.length }} 项操作</span><span class="activity-preview">{{ [...new Set(entry.activities.map(a=>toolLabel(a.name)))].join('、') }}</span><AppIcon name="chevron" :size="12" /></summary>
        <div class="activity-list"><details v-for="a in entry.activities" :key="a.id" class="activity-item">
          <summary><span class="activity-dot"></span><span>{{ toolLabel(a.name) }}</span><span class="activity-status">{{ activityStatus(a.result) }}</span></summary>
          <div class="activity-detail"><template v-if="a.arguments"><span>调用参数</span><pre>{{ JSON.stringify(a.arguments,null,2) }}</pre></template><template v-if="a.result!==undefined"><span>工具结果</span><pre>{{ a.result }}</pre></template></div>
        </details></div>
      </details>
    </div>
    <div v-if="liveCommentary" class="public-commentary live-commentary" aria-label="当前行动说明"><AppIcon name="activity" :size="14" /><div class="msg-body" v-html="render(liveCommentary)"></div></div>
    <slot name="details" />
    </div>
   </details>
   <section v-if="parts.answer" class="task-final-answer" aria-label="最终回答"><div v-if="parts.answer.kind==='candidate'&&status&&status!=='succeeded'" class="progress-caption">{{ active?'回答已生成，等待校验':'任务未完成 · 以下为待确认内容' }}</div><div class="msg-body" v-html="render(parts.answer.content)"></div></section>
  </div>
</template>
<style scoped>
.task-timeline{position:relative}.task-process>summary{display:flex;align-items:center;flex-wrap:wrap;gap:7px;cursor:pointer;list-style:none;color:var(--txt-3);font-size:12px;line-height:1.8;padding:5px 0 10px;border-bottom:1px solid var(--line)}.task-process>summary::-webkit-details-marker{display:none}.process-elapsed,.process-count{font-size:11px}.process-chevron{transition:transform .15s}.task-process[open] .process-chevron{transform:rotate(90deg)}.process-content{padding:18px 0 6px 20px;border-left:1px solid var(--line);margin-top:16px}.task-process+.task-final-answer{margin-top:24px}.live-commentary{margin-top:20px}.process-content>.live-commentary:first-child{margin-top:0}.task-final-answer>.msg-body :deep(:first-child){margin-top:0}@media(prefers-reduced-motion:reduce){.process-chevron{transition:none}}
.public-commentary{display:flex;align-items:baseline;gap:10px;color:var(--txt-3)}.public-commentary>svg{flex-shrink:0}.public-commentary .msg-body{font-size:13px;color:var(--txt-2)}.stage-finding{background:var(--ink-1);border:1px solid var(--line);border-radius:12px;padding:16px 18px}.stage-finding header{display:flex;align-items:center;gap:8px;color:var(--green);margin-bottom:12px;font-size:12px}.stage-finding header span{margin-left:auto;color:var(--txt-3);font-size:10px}.stage-finding .msg-body{font-size:13px}.progress-caption{font-size:11px;color:var(--txt-3);margin-bottom:10px}
.timeline-entry + .timeline-entry { margin-top:20px; }
.activity-batch { color:var(--txt-3); font-size:12px; }
.activity-batch>summary,.activity-item>summary { display:flex; gap:9px; align-items:center; cursor:pointer; list-style:none; line-height:1.8; }
.activity-batch>summary::-webkit-details-marker,.activity-item>summary::-webkit-details-marker { display:none; }
.activity-batch>summary { flex-wrap:wrap; padding:3px 0; }
.activity-preview { color:var(--txt-3); opacity:.8; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; max-width:300px; font-size:11px; }
.activity-list { padding:12px 0 2px 8px; }.activity-item { margin:9px 0; }.activity-dot { width:4px; height:4px; background:var(--green); border-radius:50%; }.activity-status { color:var(--txt-3); font-size:10px; margin-left:auto; }
.activity-detail { margin:10px 0 14px 12px; font-size:10px; }.activity-detail pre { background:var(--ink-1); border-radius:8px; padding:12px; font:11px/1.8 var(--font-mono); max-height:260px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; }
.task-supplement { display:flex; gap:8px; align-items:baseline; font-size:12px; color:var(--txt-2); padding:8px 0; }.task-supplement>span { color:var(--txt-3); flex-shrink:0; font-size:10px; }.task-supplement :deep(p) { margin:0; }
.msg-body { color:var(--txt-1); font-size:14px; line-height:1.9; overflow-wrap:anywhere; }
.msg-body :deep(p) { margin:0 0 1em; }.msg-body :deep(p:last-child){margin-bottom:0;}
.msg-body :deep(pre) { overflow:auto; border:1px solid var(--line); border-radius:10px; padding:16px; font:12px/1.8 var(--font-mono); background:var(--ink-1); }.msg-body :deep(code) { font-family:var(--font-mono); font-size:.9em; }
.msg-body :deep(h1),.msg-body :deep(h2),.msg-body :deep(h3) { font-weight:600; line-height:1.5; margin:1.4em 0 .6em; }.msg-body :deep(h1){font-size:23px;}.msg-body :deep(h2){font-size:20px;}.msg-body :deep(h3){font-size:16px;}
.msg-body :deep(ul),.msg-body :deep(ol){padding-left:22px;}.msg-body :deep(table){border-collapse:collapse;display:block;overflow:auto;font-size:12px;}.msg-body :deep(th),.msg-body :deep(td){padding:8px 12px;border-bottom:1px solid var(--line);text-align:left;}.msg-body :deep(blockquote){padding-left:14px;margin:16px 0;border-left:2px solid var(--line-strong);color:var(--txt-2);}
</style>
