/** Names and categories shared by the catalog and conversation, without copying backend policy. */
export const fileToolLabels: Record<string, string> = {
  artifact_list: '查看会话文件', artifact_import: '导入项目资料', artifact_info: '核验文件信息',
  presentation_templates: '查看 PPT 模板与格式', presentation_create: '生成演示文稿', presentation_inspect: '检查演示文稿',
  presentation_update: '修改演示文稿', presentation_render: '渲染幻灯片',
  presentation_append: '分批追加幻灯片',
  document_read: '读取文档原文', document_search: '搜索文档证据',
  spreadsheet_create: '生成多表 Excel', spreadsheet_read: '读取 Excel 单元格', spreadsheet_analyze: '统计 Excel 数据',
  data_chart: '生成数据图表', pdf_extract: '提取 PDF 页面', pdf_merge: '合并 PDF', pdf_render: '渲染 PDF 页面',
  image_info: '检查图片尺寸', image_resize: '等比缩小图片', archive_create: '打包文件与校验清单', archive_list: '检查 ZIP 目录',
  image_search: '搜索演示配图', image_download: '保存配图与来源',
}

/** A task delivers one PPT. Revisions with different names stay in the file history. */
export function latestPresentations<T extends { id: string; name: string }>(artifacts: readonly T[]): T[] {
  for(let index=artifacts.length-1;index>=0;index--){
    const item=artifacts[index]
    if(item.name.toLowerCase().endsWith('.pptx'))return [item]
  }
  return []
}
export const toolCategories: Record<string, string> = {
  all: '全部工具', presentations: 'PPT 演示文稿', spreadsheets: '表格与图表', documents: '文档与 PDF', media: '图片',
  archives: '压缩与交付', files: '文件对象', project: '项目与终端', web: '网页与浏览器', skills: '技能与环境',
  knowledge: '知识与记忆', mcp: 'MCP 服务', other: '其他工具',
}
export function toolCategory(name: string): string {
  if (name.startsWith('presentation_')) return 'presentations'
  if (name.startsWith('spreadsheet_') || ['csv_analyze','data_chart'].includes(name)) return 'spreadsheets'
  if (name.startsWith('document_') || name.startsWith('pdf_') || name.startsWith('report_')) return 'documents'
  if (name.startsWith('image_')) return 'media'
  if (name.startsWith('archive_')) return 'archives'
  if (name.startsWith('artifact_') || name==='text_file_create') return 'files'
  if (name.startsWith('project_') || name.startsWith('workspace_') || ['command_run','sandbox_run','plan_update','task_notes'].includes(name)) return 'project'
  if (name.startsWith('web_') || name.startsWith('browser_')) return 'web'
  if (name.startsWith('skill_') || name.startsWith('runtime_')) return 'skills'
  if (name.startsWith('knowledge_') || name.startsWith('memory_')) return 'knowledge'
  if (name.startsWith('mcp_')) return 'mcp'
  return 'other'
}
