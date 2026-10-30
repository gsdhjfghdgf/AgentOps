<script setup lang="ts">
import type {WebSourceGroup} from '../composables/taskTimeline'
import AppIcon from './AppIcon.vue'
defineProps<{value:WebSourceGroup}>()
function date(text:string){const parsed=new Date(text);return Number.isNaN(parsed.valueOf())?'':parsed.toLocaleString('zh-CN',{hour12:false})}
</script>
<template>
 <details class="search-sources">
  <summary><AppIcon name="search" :size="14" /><span>{{ value.results.length?`搜索来源 · ${value.results.length} 条候选网页`:'暂未找到相关来源' }}</span><small>{{ value.legacy?'旧版记录':'待核对原文' }}</small><AppIcon name="chevron" :size="12" /></summary>
  <div class="source-list"><p class="source-query">{{ value.query }}<time v-if="value.searchedAt">检索于 {{ date(value.searchedAt) }}</time></p>
   <article v-for="(item,index) in value.results" :key="item.url" class="source-row"><span class="source-number">{{ index+1 }}</span><div><a :href="item.url" target="_blank" rel="noreferrer noopener">{{ item.title }} <span aria-hidden="true">↗</span></a><p class="source-domain">{{ item.domain }}<span v-if="item.dateLabel" title="搜索页提供的日期标签，需在原文核实"> · {{ item.dateLabel }}</span></p><p v-if="item.snippet" class="source-snippet">{{ item.snippet }}</p></div></article>
   <p v-for="warning in value.warnings" :key="warning" class="source-warning">{{ warning }}</p><p class="source-note">候选网页与摘要尚未核验；多个搜索源或同稿转载不代表独立确认。</p>
  </div>
 </details>
</template>
<style scoped>
.search-sources{border:1px solid var(--line);border-radius:12px;font-size:12px;overflow:hidden}.search-sources>summary{display:flex;align-items:center;gap:9px;list-style:none;cursor:pointer;padding:13px 15px;color:var(--txt-2)}summary::-webkit-details-marker{display:none}summary small{margin-left:auto;color:var(--txt-3);font-size:10px}.source-list{border-top:1px solid var(--line);padding:0 16px 12px}.source-query{color:var(--txt-3);font-size:11px;line-height:1.8;margin:12px 0}.source-query time{display:block;font-size:10px}.source-row{display:flex;gap:12px;padding:13px 0;border-top:1px solid var(--line)}.source-number{color:var(--txt-3);font-size:10px;margin-top:2px}.source-row>div{min-width:0;flex:1}.source-row a{color:var(--green);text-decoration:none;font-weight:500;overflow-wrap:anywhere}.source-row a:hover{text-decoration:underline}.source-domain{font-size:10px;color:var(--txt-3);margin:4px 0}.source-snippet{font-size:12px;line-height:1.8;color:var(--txt-2);margin:5px 0;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}.source-note,.source-warning{color:var(--txt-3);font-size:10px;line-height:1.8;margin:9px 0 0}.source-warning{color:var(--amber)}
</style>
