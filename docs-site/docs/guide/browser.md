# 内置浏览器

App 内置了一个 WebView 浏览器，支持 AI 自动化操作网页。AI 可在后台操作浏览器，截图也能在后台完成（无需先打开面板）。

## 打开浏览器

在侧边栏底部的卡片中点击「内置浏览器」：

- **大屏（平板）**：浏览器在右栏与聊天并排打开，可拖动分割条调整宽度。
- **窄屏（手机）**：浏览器全屏打开，按返回键回到聊天。

## 手动浏览

在地址栏输入网址按回车即可导航；输入的内容不像网址时（如含空格或中文），会自动交给内置搜索引擎 Bing 搜索。地址栏右侧提供刷新按钮，下方底栏包含后退、前进、新建标签页、多标签管理与更多设置菜单（三条杠图标）。页面加载时地址栏下方显示线性进度条。

点击底栏右侧的三条杠图标可打开「浏览器设置与工具」面板，集中管理夜间模式、开发者工具与浏览器标识（User-Agent）。

## 本地文件预览

地址栏支持本地文件地址，可直接打开设备上的 HTML 文件：

- 设备真实路径：`file:///storage/emulated/0/Download/index.html`
- 容器路径（AI 在容器里看到的路径，自动映射为真实文件）：`~/workspace/index.html`、`/etc/xxx.html`

AI 也可通过 `browser` 工具的 `navigate` 打开本地页面并截图分析。

**注意**：远程工作区模式下工作区文件在远端，只能加载页面本身，HTML 引用的相对资源（CSS/JS/图片）会失效；本地工作区不受影响。

## 开发者工具

在底栏三条杠菜单中开启「开发者工具」开关，即可随时开启或收起移动端开发者工具（基于 Eruda）：
- **全功能控制台**：包含 Console（查看日志与执行 JavaScript 代码）、Elements（查看与实时编辑 DOM 树和 CSS 样式）、Network（查看网络请求与响应）、Resources（查看 LocalStorage、Cookie 等数据）、Sources 等。
- **即时可用**：开启后页面右下角显示浮动齿轮图标，点击即可展开完整控制台面板；关闭菜单中的开发者工具开关可彻底关闭并移除悬浮球。
- **电脑端调试联动**：开启时同步启用 Chromium 的 `WebContentsDebugging`，支持通过 USB 连接电脑并在 Chrome 浏览器访问 `chrome://inspect` 进行桌面级远程审查。

## 夜间模式

浏览器内置夜间模式，默认跟随 App 外观：App 为深色时页面自动变暗，App 为浅色时恢复原样。可在底栏三条杠菜单中手动开启或关闭「夜间模式」；手动选择后不再跟随 App 外观，切回与 App 外观一致时会自动恢复跟随。

夜间模式采用双轨渲染架构：
- **原生深色优先**：向页面声明 `prefers-color-scheme: dark` 与 `color-scheme: dark`，现代网站（如 GitHub、Google 等）会自动启用官方精细调优的原生暗色主题，文字清晰且深色区域不会反白。
- **亮底站点压暗**：对无暗色样式的亮底站点，在页面顶层盖一层半透明暗色蒙版整体压暗（保留原色、只降低亮度，不做反色），观感柔和。该蒙版作用于页面渲染，AI 截图同样会呈现压暗后的配色。
- **防闪白**：在页面启动时立即设置暗色视口背景并提前注入深色偏好，避免页面刷新瞬间的白屏闪烁。

## 浏览器标识 (User-Agent)

在底栏三条杠菜单的「浏览器标识 (User-Agent)」区域，可一键切换网页访问的设备环境，切换后自动刷新当前页面生效：

- **系统默认**：使用当前设备的原生 WebView 标识。
- **安卓手机**：模拟 Android Chrome Mobile 环境。
- **安卓平板**：模拟 Android Chrome Tablet 大屏环境。
- **桌面电脑**：模拟 Windows PC 端桌面 Chrome 浏览器，强制请求桌面版网页。
- **苹果 iPhone**：模拟 iOS Mobile Safari 环境。
- **苹果 iPad**：模拟 iPadOS Tablet Safari 环境。
- **自定义**：手动输入任意自定义 User-Agent 字符串。

## AI 自动化操作

AI 可通过 `browser` 工具控制浏览器执行以下操作：

| 操作 | 说明 |
| --- | --- |
| `navigate` | 导航到指定 URL（支持 `http(s)`，本地文件支持 `file://` 或容器路径；不像网址的输入会按关键词用内置搜索引擎 Bing 搜索），等待页面加载完成 |
| `evaluate` | 执行任意 JavaScript（支持 Promise/async，返回原生 JSON） |
| `click` | 点击元素（完整事件链，兼容 React/Vue） |
| `fill` | 填充表单字段（native setter + React valueTracker hack） |
| `select` | 选择原生下拉框 `<select>`（按 value 或可见文本） |
| `hover` | 悬停元素（派发 mouseenter/over/move，可展开下拉菜单） |
| `press` | 按键（Enter/Escape/Tab/方向键/Backspace/Delete/空格/普通字符） |
| `getText` | 提取页面文本（可指定选择器），已过滤 script/style |
| `getHtml` | 提取页面 HTML（可指定选择器） |
| `getBackbone` | 提取无障碍树（role/name/ref，可指定 `maxDepth`），ref 可直接用于后续操作 |
| `screenshot` | 截取当前页面，返回图片供视觉模型分析（支持后台离屏截图） |
| `console` | 取页面控制台日志（可过滤级别、可清空） |
| `wait` | 等待条件满足（`text=` / `text*=` / `selector=` / `domStable`，可设 `timeout`） |
| `scroll` | 滚动页面（滚动到指定元素或滚到底部） |
| `dialog` | 处理挂起的 `confirm`/`prompt` 对话框（接受或取消）；浏览器面板可见时由用户在界面上处理 |
| `back` / `forward` / `reload` | 浏览器导航控制，back/forward 会等待导航完成 |

页面弹出 `alert`/`confirm`/`prompt` 时的处理方式取决于浏览器面板是否打开：

- **面板已打开**：在面板内弹出「网页提示 / 网页确认 / 网页输入」对话框，由你直接选择确定或取消（标题已标明来自网页）。此时 AI 不会代答，需要你处理。
- **面板未打开（AI 后台操作）**：`confirm`/`prompt` 会被挂起，工具响应里出现 `pendingDialog` 字段，由 AI 用 `dialog` action 接受或取消（30 秒未处理会自动取消）；`alert` 直接确认，不阻塞。

**注意**：浏览器面板未打开时，网页无法弹出任何原生界面（下拉列表、对话框等）——这是有意为之，避免后台页面把弹窗伪装成 App 自己的界面。

## 选择器格式

`click`、`fill`、`hover`、`getText`、`getHtml`、`scroll`、`wait` 的选择器支持：

- `ref=e22`：`getBackbone` 返回的元素引用，直接定位快照里的元素
- CSS：`#id`、`.class`、`a[href=...]`
- `text=登录`：精确匹配元素文本
- `text*=登录`：包含匹配
- `role=button[name="登录"]`：按角色与名称匹配
- `xpath=//a[@href]`：XPath 表达式

`getBackbone` 返回无障碍树：每个节点形如 `{role,name,ref,url,value,children}`，`role` 是按标签/`role` 属性推导的可访问角色（`link`/`button`/`textbox`/`heading`/`navigation`…），`name` 是可访问名称（`aria-label`/`aria-labelledby`/`alt`/`placeholder`/关联 `label`/内容文本），`ref` 只分配给可交互元素。已过滤 `script`/`style` 与不可见元素，超出 `maxDepth` 的节点以 `truncated:true` 标记。

## 操作超时怎么办

AI 打开网页会等待页面加载完成，超过 30 秒仍未完成时会提示操作超时。这不表示浏览器未初始化：先打开浏览器面板检查页面是否已经显示，再检查网络、代理或登录状态，确认后再让 AI 继续操作。若只需要网页正文或静态 HTML，可以让 AI 使用网页抓取，不必等待浏览器渲染。

你主动停止 AI 时，进行中的浏览器工具调用也会取消，不会把停止操作误报成浏览器未就绪。

## 与 websearch / webfetch 的区别

- **websearch**：搜索引擎查询，获取搜索结果摘要。
- **webfetch**：抓取网页 HTTP 内容（纯文本或 HTML），不支持 JS 渲染。
- **browser**：完整 WebView 渲染，支持 JS 动态页面、交互操作、截图分析。

当网页内容依赖 JavaScript 渲染、需要登录后才能访问、或需要点击/填表等交互操作时，使用 `browser` 工具。
