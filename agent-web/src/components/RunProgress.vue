<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {api} from '../api'
const props=defineProps<{runId:string;status:string}>()
interface Progress {reads:number;unique_pages:number;distinct_files:number;notes:string;revision:number;duplicateStreak:number;warning:string;lastNovelAt:string|null;legacy:boolean}
const value=ref<Progress|null>(null)
let timer:ReturnType<typeof setInterval>|undefined,generation=0
const terminal=()=>['succeeded','failed','cancelled','timed_out','budget_exceeded','paused'].includes(props.status)
async function refresh(){const g=generation,id=props.runId;if(!id)return;try{const result=await api.get<Progress>(`/api/operations/runs/${id}/progress`);if(g===generation)value.value=result}catch{/* Keep execution controls usable when the progress endpoint is offline. */}}
watch(()=>props.runId,()=>{generation++;value.value=null;clearInterval(timer);refresh();timer=setInterval(()=>{if(!terminal())refresh()},5000)},{immediate:true})
watch(()=>props.status,()=>refresh())
onBeforeUnmount(()=>{generation++;clearInterval(timer)})
</script>
<template><div v-if="value&&(value.reads||value.notes)" class="read-progress" :class="{warning:!!value.warning}"><div class="read-counts"><span>已读 {{ value.distinct_files }} 个文件</span><span>读取 {{ value.reads }} 次</span><span>{{ value.legacy?'不同读取参数':'新增文件片段' }} {{ value.unique_pages }}</span><span v-if="value.reads>value.unique_pages" class="duplicate">重复读取 {{ value.reads-value.unique_pages }} 次</span></div><p v-if="value.warning" role="status">{{ value.warning }}</p><details v-if="value.notes"><summary>阶段发现 · 版本 {{ value.revision }} <small>Agent 记录，保留原始证据以供核对</small></summary><pre>{{ value.notes }}</pre></details><small v-else-if="!value.legacy">阶段发现尚未记录；已读台账会保留，避免历史裁剪后重新扫描。</small></div></template>
<style scoped>.read-progress{border-top:1px solid var(--line);padding:12px 0 2px;margin-top:12px;color:var(--txt-3);font-size:11px}.read-counts{display:flex;gap:14px;flex-wrap:wrap}.duplicate{color:var(--amber)}p{color:var(--amber);line-height:1.8;margin:10px 0}small{font-size:10px;color:var(--txt-3);display:block;margin-top:7px}summary{cursor:pointer;margin-top:12px;color:var(--txt-2)}summary small{display:inline;margin-left:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:300px;overflow:auto;background:var(--ink-1);padding:12px;border-radius:8px;font:12px/1.9 var(--font-ui)}</style>
