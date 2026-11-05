# AgentOps 前端源码

这里是完整的 Vue 3 + TypeScript + Electron 工程，release 中的 EXE 是它的构建产物。

## 开发与构建

需要 Node.js 22.13 或更新的受支持版本和 npm。工程已升级为 Electron 44，旧 Node 20 不能用于本轮打包：

    cd agent-web
    npm ci
    npm run dev       # 浏览器开发，http://localhost:5173
    # 或 npm run app:dev，启动 Electron 开发窗口
    npm test
    npm run dist      # Windows 安装包生成到 release

`dist` 会先准备并校验官方 Electron 运行时，再从本地 `node_modules/electron/dist` 打包。若官方二进制下载不可达，可临时设置可信镜像 `ELECTRON_MIRROR`；校验仍使用官方 npm 包附带的 checksums，不关闭校验。

账号界面使用后端正式注册/登录。桌面端自动读取本机注册凭据；浏览器开发时，创建账号需要填写部署管理员提供的注册凭据。不要把注册凭据写入前端源码。详情见 [账号与安全设计](../docs/账号与安全设计.md)。

后端默认 http://127.0.0.1:8080。浏览器开发通过 vite.config.ts 的 /api 代理连接后端。
更换浏览器开发后端时修改代理目标；更换桌面后端时，在启动桌面进程前设置：

    $env:AGENT_API_BASE = 'http://127.0.0.1:8080'
    npm run app

后端地址由主进程通过 preload 在页面模块执行前提供，避免首批请求先连错地址。

## 从哪里修改功能

| 位置 | 作用 |
| --- | --- |
| src/App.vue | 主布局、功能侧栏、项目与会话列表 |
| src/api.ts | 业务接口、请求和响应类型 |
| src/services/httpClient.ts | 统一请求、超时、身份隔离、并发 GET 合并 |
| src/services/runEvents.ts | SSE 订阅、断线状态、事件去重 |
| src/composables/useChatRun.ts | 会话与任务同步、流式文本、输入框内暂停/补充/继续 |
| src/composables/chatTurns.ts | 同一任务的消息、补充和工具结果归组 |
| src/composables/taskTimeline.ts | 过程折叠和最终回答分离 |
| src/components/ChatView.vue | 对话页面和输入框 |
| src/components/ComposerModePicker.vue | 输入框内 Chat / Plan / Build 模式 |
| src/components/ComposerModelPicker.vue | 输入框内切换已保存模型 |
| src/components/ModelSettings.vue | 多个模型的新增、编辑、测试与选择 |
| src/modelProfiles.ts | 模型目录共享类型与展示方法 |
| src/components/RunWorkspacePanel.vue | 计划、修改预览、产物、来源和授权 |
| src/components/OperationsView.vue | MCP、定时任务、统计和来源管理 |
| src/components/KnowledgeView.vue | 文档导入、知识检索与长期记忆 |
| src/style.css | 全局主题与基础样式 |
| electron/main.cjs | 桌面窗口、后端地址和系统交互 |
| electron/preload.cjs | 受限桌面能力与启动配置 |
| electron/context-menu.cjs | 复制、粘贴等原生右键菜单 |
| tests | Node 测试，直接运行实际 TypeScript 模块 |

知识库、评测、工具技能、个人设置等页面按需加载。业务请求不放到 Electron 主进程执行。
增加接口时复用 api；写操作不得加入 GET 合并或自动重试。

运行中使用 SSE 更新公开执行说明和输出，增量接口负责恢复已提交的数据。
正常连接每 10 秒兜底同步，断线时恢复更频繁的轮询；暂停任务会关闭当前 SSE。

模型配置支持多份独立保存。在输入框的模型菜单点击“管理模型”，新增时填写
URL、Key 和模型名即可；名称可选。“保存配置”不切换当前模型，“保存并使用”
同时切换。列表与选择由后端持久化，重启应用后仍保留；切换和编辑用于新任务，
运行中或已暂停的任务保留原配置版本。向量模型仍由系统管理。

保存和修改前会自动检测连接及实际流式工具协议，失败时不落库。Chat 普通问题可以直接
流式输出正文，也可调用查询工具。模型连续请求失败三次会自动暂停并显示原因，
在同一输入框点击继续即可保留现有步骤恢复。
