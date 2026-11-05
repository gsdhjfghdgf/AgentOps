# 文件工具与 PPT 工作流（0.1.25）

文件工具共 22 个实际注册、可由 Agent 调用的工具，统一使用原有任务、授权和产物系统。
原来的联网、浏览器、项目、命令、Skills 与 MCP 工具继续保留。

## 工具种类

| 类别 | 工具 | 作用 |
|---|---|---|
| 文件对象 | artifact_list / artifact_import / artifact_info | 查找本会话资料，从授权项目或专属工作区导入，核对实际大小和 SHA256 |
| 演示文稿 | presentation_templates / presentation_create / presentation_append / presentation_inspect / presentation_update / presentation_render | 读取格式示例、生成可编辑 PPTX、分批追加页面、检查结构、修订文本、逐页渲染 PNG |
| 原文读取 | document_read / document_search | 读取 PDF、DOCX、PPTX 和 UTF-8 文本，保留页码、段号和偏移，精确搜索证据 |
| 电子表格 | spreadsheet_create / spreadsheet_read / spreadsheet_analyze | 生成多表 XLSX，读取指定单元格，进行十进制整列统计 |
| 数据图表 | data_chart | 根据明确数值生成柱状图或折线图 PNG |
| PDF | pdf_extract / pdf_merge / pdf_render | 提取连续页、按顺序合并、渲染真实页面 |
| 图片 | image_info / image_resize | 读取真实尺寸和透明通道，等比缩小并另存 PNG |
| 压缩交付 | archive_create / archive_list | 打包文件并附校验清单，检查 ZIP 目录及危险名称 |

前端“工具与技能”增加分类和中文搜索，聊天执行过程使用中文名称。
任务工作台的“文件与成果”可上传资料；PPT 产物可在程序内翻页预览。

## 一个 PPT 任务实际如何执行

示例输入：

> 根据本会话上传的项目说明制作 8 页中文 PPT，用于 10 分钟项目介绍。
> 包含项目目标、架构、核心功能、演示场景和后续计划。
> 不编造测试数据，区分已实现与规划。生成可编辑 PPTX，检查实际页数和文字溢出，
> 渲染每页预览，最后提供 PPT 和预览 ZIP，文件小于 50MB。

1. `artifact_list` 找本会话已经上传或生成的资料。项目中的二进制文件先用
   `artifact_import` 导入，不能拿本机任意绝对路径绕过项目边界。
2. `document_read` 和 `document_search` 核对正文与引用，需要数字运算时调用
   `calculator` 或 `spreadsheet_analyze`。网络事实沿用 `web_search` 定位、`web_read`
   核对原文的流程。
3. `presentation_templates` 返回可用主题、布局和 JSON 示例。模型安排每页目的与内容，
   明确来源和讲稿，不把未知内容写成已确认事实。
4. `presentation_create` 生成真实 PPTX。支持封面、正文、原生表格、原生柱状/折线图、
   已有图片对象及备注；文本、表格和图表可在 Office 中编辑。
   长文稿先创建 2～3 页，再用 `presentation_append` 每批追加 2～3 页（每批最多 5 页、
   文稿总计最多 40 页）。每次使用最新对象 ID，仅传新增页面，避免重复生成整份 JSON。
   每次追加都会输出新的完整 PPTX，保留旧版及其图表、讲稿；最终交付最后的完整版本。
5. `presentation_inspect` 检查全部页面的边界与文字高度，返回实际页数、文本框 ID 和问题。
   文本摘要每次返回 5 页，每页最多 8 个文本框，`nextSlide` 提示续查；长原文用
   `document_read`，检查问题过多时返回明确的 `issuesLimited` 标记。
6. `presentation_render` 为每页生成真实 PNG。前端也支持按页渲染和翻页。
   POI 不能直接绘制所有原生图表，本项目为支持的簇状纵向柱图和简单折线图，
   按图表缓存数据补充预览绘制。其他复杂图表明确提示下载 Office 查看，不输出空图冒充成功。
   对话中的 PPTX 文件卡片提供“查看 PPT”和“下载 .pptx”，逐页预览直接读取该 PPTX 的实际内容。
   同名文件默认显示最新版本，旧版本保留在“文件与成果”；配套 PNG 默认折叠并延迟加载，避免遮挡最终文件。
7. 根据检查结果用 `presentation_update` 生成修订版，或重新生成相关布局。
   修改不覆盖原文件。修订后再检查、预览。
8. `artifact_info` 核对大小和 SHA256，`archive_create` 将文件和预览打包，
   ZIP 内的 `manifest.json` 记录每个条目的文件对象和 SHA256。

内置 `presentation-authoring` 技能提供上述工作流，可在技能库启停或按需加载。
技能不会替用户授予工具权限。

## 准确性与边界

- 文件格式、数值计算、来源证据和视觉排版是不同检查。工具成功不能证明资料真实，
  生成预览也不代表模型已看过页面。当前模型无法查看图片时必须明确说明，不能声称人工视觉验收通过。
- 创建工具对超长标题、过密正文、表格和图表超限返回错误，要求拆页，不静默截断内容。
  `presentation_update` 只修改已检查文本框，以新文件保存；复杂重排需要重新生成。
- 支持的生成范围是固定布局，不等于任意设计模板、动画、视频和完整 Office 排版引擎。
  渲染受本机字体和 POI 支持范围影响，与 Office 显示可能略有差异。
- 统计使用 `BigDecimal`，缺失值、非法数字和公式分别计数。平均数最多 34 位有效数字。
  不执行公式，不把过期缓存结果当成现算值；创建 Excel 时公式式字符串按文字存储，长编号用字符串。
- PDF 页面合并按调用顺序执行，PDFBox 3 的延迟读取要求输入在保存完成前保持打开。
  PDF 提取和合并只保留静态页面，移除交互批注和表单动作，不覆盖原文件。
- 文档提取不包含 OCR，扫描件提取不到文字不能推断“没有内容”。
- 文件对象按当前租户和用户核验，列表限定当前会话；目录导入复用既有路径与外部链接检查。
  Office 拒绝活动内容和外部资源，保留 POI 的 ZIP 防护；ZIP 目录检查不解压、不执行。
- 输入文件最多 20MB，输出最多 50MB，像素、页数、表格行数和归档总量都有上限。
  不支持的内容必须给出真实限制，不通过命令工具暗中绕过用户禁用的能力。
- Chat / Plan 可读取和分析文件，但不会生成正式演示文稿或修改文件；执行交付任务使用 Build。

## 测试与维护

- `FileToolsTest` 用真实 POI / PDFBox / ImageIO 文件测试编辑性、内容读回、图表预览像素、
  PDF 顺序和字体、精确统计、越界、取消、原文件保留及归档 SHA256。
- `ModelToolCatalogTest` 验证 PPT 任务按相关类别加载工具，不把全部工具附在每轮请求里。
- 前端测试覆盖翻页、旧请求迟到、身份变化、预览失败保留上一页和 Blob URL 释放。
- `node scripts/verify-file-tools.mjs` 在隔离账号、实际后端和数据库上走完整引擎链。
  模型端是可控的流式协议测试服务，因此此脚本验证确定的执行链，不冒充真实模型自主选择工具的质量评测。

## 参考的成熟方案

优先复用项目现有 JVM 依赖，避免新增一个必须单独部署的 Office 服务。

- [Apache POI XSLF Cookbook](https://poi.apache.org/components/slideshow/xslf-cookbook.html)：Office 幻灯片、文本、图片和原生图表。
- [Apache POI 渲染说明](https://poi.apache.org/components/slideshow/ppt-wmf-emf-renderer.html)：基于 Java2D 的页面渲染及范围。
- [Apache POI 电子表格指南](https://poi.apache.org/components/spreadsheet/quick-guide.html)：XLSX 类型、范围和工作表处理。
- [Apache PDFBox 工具](https://pdfbox.apache.org/3.0/commandline.html) 与 [3.0 迁移说明](https://pdfbox.apache.org/3.0/migration.html)：PDF 合并、提取、渲染和延迟读取。
- [PptxGenJS 表格 API](https://gitbrent.github.io/PptxGenJS/docs/api-tables.html)：参考其结构化输入和可编辑内容的接口组织方式，本轮没有引入 Node 生成依赖。
- [LibreOffice 命令行参数](https://help.libreoffice.org/latest/en-GB/text/shared/guide/start_parameters.html)：可作为后续 Office 转 PDF 的独立适配器参考，当前未宣称已集成。
