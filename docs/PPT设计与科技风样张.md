# PPT 设计与多风格交付（0.1.27）

## 0.1.27：单一成品与自动选风格

- 每个任务默认只显示一个最终完整 PPT 卡片，不按文件名将“6页版”“10页版”分别列为成品。对话不再列出页面 PNG，用户在同一 PPT 预览中翻页；旧版本和素材仍保留在“文件与成果”，不删除既有数据。
- `presentation_append` 与 `presentation_update` 沿用输入文件名。`title` 参数为兼容旧调用保留，不再用于新起阶段名称；每次继续使用最新对象 ID。底层保留不可变版本供恢复，用户只交付最后完整对象。
- `presentation_templates.themeCatalog` 提供 9 套风格及适用场景：tech 科技产品、business 商务汇报、academic 学术研究、editorial 杂志人文、warm 温暖生活、vivid 活力创意、forest 自然生态、ocean 海洋清新、ink 极简黑白。
- 风格包含配色、封面明暗、标题排布与正文层级的差异。AI 根据主题、用途和受众选择并明确传入 `theme`；用户指定时优先遵循。可提供 `purpose`（200字内）、`audience`（100字内）。
- `theme` 省略或 `auto` 时，后端按标题、用途、受众与封面标题关键词作确定性回退，无匹配采用 ink。这是遗漏参数的回退，不冒充一次额外 AI 审美判断。append 的 auto/省略继承原主题，显式传入不同主题会拒绝，避免整份混搭；换风格需重建完整文稿。
- 最终回答只引用最后 PPT 对象，不默认附加 PNG、PDF 或 ZIP；只有用户明确要求时才额外导出素材。

验证：256 项后端、56 项前端测试通过。9 套风格均生成可编辑文稿并实际渲染封面，另检查图表结构与文本边界；外部验收查看九宫格。隔离 fixture 经过真实任务引擎生成草稿、重建、追加及预览，自动回退为 warm，保留 3 个版本；源码和打包界面均只展示一个 PPT 并实际翻阅全部 3 页。fixture 不花费提供商 Token，也不代表任意真实模型的风格选择质量保证。

复现：`node scripts/verify-presentation-delivery.mjs`；桌面验证 `agent-web/scripts/verify-ppt-design.cjs --delivery`（源码加 `--source`）；后端测试渲染九套封面后可运行 `agent-web/scripts/verify-ppt-themes.cjs` 查看对照。

## 已实现

- 增加 tech 主题，统一深色封面、蓝色强调、浅色正文与字体层级；保留 forest、ocean、ink。
- 原生可编辑布局扩展到 12 类：cover、section、statement、metric、comparison、process、timeline、image_split、chart、table、image、bullets。
- 生成流程先组织逐页观点、证据、版式与素材，再分批生成。正文建议不超过三条，长文稿避免连续列表页。
- 主题保存在 PPTX 元数据中，追加页面省略 theme 时沿用原主题。
- 图文默认完整保留画面；装饰照片可选择 cover 裁剪，图表和 Logo 应使用 contain。
- 图表的可编辑对象和预览使用相同系列配色；多行文本逐段设置字体、字号与颜色，并显式设置中文字体。
- `presentation_inspect` 增加 designReview：文字密度、重复布局、图片尺寸。该结果明确为规则检查，不等于视觉模型审稿。

## 配图实现及本次限制

已增加 image_search、image_download，通过 Openverse 获取公开图片索引及作者/许可信息。下载重新读取索引元数据，只接受 JPEG/PNG，按真实尺寸限制解码，大图等比缩小以控制体积。两个工具共用任务联网授权，保留项目与系统文件写入边界。

公网客户端支持 PUBLIC_WEB_PROXY_URL，未指定时使用进程 HTTPS_PROXY / HTTP_PROXY。代理不会放开对内网、回环和元数据地址的目标访问。

当前环境的 Openverse 查询仍会超时，本次没有把互联网配图计为验收通过。四页样张使用 AgentOps 自身界面截图；来源不依赖外部摄影素材。

## 四页效果样张

按用户缩小后的范围，仅输出四页：项目封面、重点数字、三步流程、图文展示。固定分镜通过真实任务引擎调用生成器并渲染，不花费真实模型 Token；它证明排版和文件链路，不代表任意聊天模型都能自主得到同等设计质量。

重现：先准备 `.runtime/ppt-design-sample/` 下的两张项目界面截图，再执行 `node scripts/verify-presentation-sample.mjs`。桌面验收使用 `agent-web/scripts/verify-ppt-design.cjs`。

## 参考

- [Apache POI XSLF：原生文字、图片、布局与渲染](https://poi.apache.org/components/slideshow/xslf-cookbook.html)
- [PptxGenJS：母版与占位符](https://gitbrent.github.io/PptxGenJS/docs/masters/)
- [Slidev：PPTX 及可编辑 PPTX 导出与局限](https://sli.dev/guide/exporting.html)
- [Openverse：开放许可素材及索引许可信息的限制](https://docs.openverse.org/)

下一步若要自动判断留白、视觉重心、图文相关性及审美，需要接入图片输入与视觉模型审阅链路；目前没有声称该能力已完成。
