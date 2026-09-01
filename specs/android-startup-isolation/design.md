# Android 启动白屏根治设计

## 已确认根因边界

同一 AppID、Taro 4.2.1、微信基础库 3.15.1 和 Android 真机环境下，只有一个首页且不装载业务图的最小探针可以正常显示；正式包仍白屏。正式包的所有页面同步导入单体 `compositionRoot`，而 Taro 默认 Babel 转换又会在 Webpack 建图前把 `import()` 降级为 `Promise + require`，因此完整业务图仍进入主包公共启动链。`retryableLazy` 只延迟对象构造，不能延迟模块求值，所以首帧仍会被完整业务图中的任一求值异常阻断。

## 结构

采用六边形架构中的 Composition Root + Facade，并用 Abstract Factory 管理共享适配器：

- `startupCompositionRoot`：只创建会话恢复、登录、首页导航和活动训练探测。
- `planningCompositionRoot`：只创建建档、计划候选、计划编辑和偏好能力。
- `workoutCompositionRoot`：只创建训练准备、执行、总结和训练导航能力。
- `progressCompositionRoot`：只创建历史、趋势、同步冲突和进阶建议能力。
- `accountCompositionRoot`：只创建账户生命周期、隐私和账户导航能力。
- `exerciseGuideCompositionRoot`：只创建动作详情与动作趋势能力。

各根使用独立 `createRetryableLazyValue`，向页面暴露聚焦 Facade。轻量 `SharedPlatformKernel` 是唯一的共享基础设施内核，保存用户 generation lease、本地数据生命周期、会话刷新协调器和已加载功能的内存清理 Observer；它不导入 API 客户端、AI、计划、训练或隐私模块。需要跨页面保持内存候选状态的计划页面共用同一个 planning 根。

## 首帧加载

主包只包含 `app` 与可见 Bootstrap 首页。Bootstrap 首轮渲染不读取业务组合根，在 Taro `useReady` 后再经 `nextTick` 等待 Android 原生视图首帧完成，然后通过微信官方 `loadSubpackage` 加载 startup 分包并跳转；失败时留在 Bootstrap 显示可重试错误态。业务首页同样先完成自身首帧，再加载 `startupCompositionRoot` 并运行会话恢复。这样即使 startup 页面模块自身装载失败，重新打开仍会先回到可见的安全主包。

React/Taro 错误边界只能在框架完成第一次提交后生效，不能覆盖 `app.js` 已执行但页面根节点尚未建立的窗口。构建输出因此采用 Output Decorator：从最终 `app.json` 枚举所有主包与分包页面，在每个 Taro WXML 根模板之前注入不依赖 JavaScript 的原生首屏，并在全局 WXSS 注入配套样式。Taro 一旦写入非空 `root.cn` 就自动隐藏原生层；若框架在首次提交前中断，则保留版本化 `WB-P00` 诊断码，从而把“无信息白屏”降级为可观察故障。功能薄壳加载和渲染错误分别显示版本化的 `WL-E01/E02/E03/E04/E99/R01`，不得把原始异常、路径或敏感信息回显给用户。

Android 真机回读 `WL-R01 · R4` 后，失败边界已从异步块加载进一步收敛到业务组件渲染。正式产物仍直接调用 `padStart`、`flatMap`、`Object.fromEntries` 与 `.at()`，而原 Babel 配置仅声明 Android 5/iOS 9 target、未安装标准库能力。兼容设计采用 Pure Runtime Adapter：项目级 Babel Compatibility Transformer 将已审计的 12 类调用按使用点改写为 `@babel/runtime-corejs3`/`core-js-pure` helper，既不导入 core-js 根或 stable 全集，也不写全局 prototype。不直接依赖 Taro 4.2.1 的 `useBuiltIns: 'usage'`，因为回归探针已证明它在当前工具链未改写 `padStart/flatMap/fromEntries`。Webpack 的微信 target 使用 `wx` 作为 chunk 运行时全局对象，但 core-js-pure 的特性检测需要的是标准库构造器；构建期 Adapter 因此精确替换 `core-js-pure/internals/global-this`，向其提供 `Array/Object/String/Promise` 等真实原生构造器，避免进入 `Function("return this")` 的浏览器兜底。启动边界只放行 Adapter、`@babel/runtime-corejs3` 和 `core-js-pure`；测试在删除原生 API 的独立进程中证明转换后代码可恢复能力且 prototype 仍保持未补丁状态，产物门禁扫描全部 JavaScript 并禁止 `eval`/`Function` 构造器及字符串定时器。

业务页面入口只同步装载薄壳、错误态和静态样式；页面实现及组合根通过带明确 owner 名称的 `import()` 形成所属分包下的物理异步块。构建插件保留 Webpack 官方 chunk registration payload 与 `installedChunks`/`onChunksLoaded` 状态机，只把浏览器脚本后端替换为微信原生 `require.async`。真机普通分包运行在 `WASubContext`，异步块不得再通过分包上下文中的 `wx.webpackJsonp.push` 全局副作用向主运行时注册；构建时须把 registration payload 编码为带版本标记的 CommonJS 信封，`require.async` 返回信封后由主运行时校验并显式推入自身的 Webpack queue。微信预编译器只能识别参数为单个静态字符串字面量的 `require.async`，因此运行时先按六个已注册分包的 `async/*.js` 生成穷举白名单，再由 chunk 路径分派到每个静态字面量调用；不得把运行时变量直接传给 `require.async`。同 URL 请求合并，失败可重新发起，迟到的旧 attempt 不能结算新 attempt；生成运行时固定使用微信全局对象，不允许 DOM、HTTP 脚本加载、`eval` 或 `new Function`。训练页的动作指南以可重试二级异步加载复用 `exercise-guide/async/detail` 块，避免 workout 冷包直接引用尚未装载的 exercise-guide 图片；加载前保留可见文字占位，失败可重试。

多个功能 API 客户端共享 `SessionRefreshCoordinator`，避免同时遇到 401 时并发轮换同一 refresh token；协调器跟随 token 轮换谱系并加入后代正在进行的刷新，迟到的 A 请求不会拿过期 B 重试或误清理已经轮换到 C 的会话。初始认证请求在异步读取会话之前、每次重试在发送之前记录单调递增的 rotation revision；即使源 token 的谱系记录因超过缓存上限而被淘汰，只要该操作在途期间发生过轮换，迟到的 401 也只能失效返回，不能再次刷新或清理当前登录态。共享刷新由唯一 owner 按“刷新响应、校验 generation、持久化、再次校验、发布轮换”的顺序完成，joiner 只读取同一结果，不能用迟到的旧会话覆盖新会话。账号登录开始、退出、切换、清理或认证失效都会变更共享 generation；每条异步业务链捕获固定 generation，并在每个 await 之后、下一次 API 之前以及所有会话/本地/单例状态副作用之前校验。旧账号 continuation 即使在新账号激活后恢复，也只能被拒绝，不能重新捕获新 generation。退出采用 Prepared Command，在本地清理前同步冻结旧账号 token，之后的远端退出只携带该快照且不再触碰本地状态；业务 mutation、遥测与导航由应用层组合用例绑定到同一个 generation lease。`session=null` 只有在内存与持久层均已成功清理后才被标记为可信空态；未知来源的空会话必须先清理孤儿草稿、队列和 AI 授权，才能激活新账号。

## 分包

- `subpackages/startup`：业务首页、登录与会话恢复。
- `subpackages/planning`：建档、计划候选、预设、计划和编辑。
- `subpackages/workout`：训练准备、训练过程、训练总结。
- `subpackages/progress`：历史、趋势、同步冲突。
- `subpackages/account`：我的与隐私、动作偏好。
- `subpackages/exercise-guide`：动作详情。

保留现有 presentation 页面作为组件，在每个分包下增加薄页面入口。这样页面实现与测试路径稳定，同时 Webpack 可按物理入口把功能依赖归属分包。

## 回归门禁

- TypeScript AST 门禁区分同步 import 与动态 import，检查首页首帧同步依赖闭包。
- 构建产物门禁解析 `dist/app.json`、Webpack 图和最终文件，检查路由唯一性、初始模块闭包、功能模块 owner、页面及声明的二级物理异步块存在；每个异步块必须导出注册信封且不得残留跨上下文 `webpackJsonp.push` 副作用，运行时不得出现 DOM/eval/`new Function` 加载后端；所有生成 JS/CSS/WXSS 的本地图片、字体、音视频引用还必须存在且不得跨越未装载的分包 owner。
- 构建产物门禁还必须证明所有注册页面都含原生首屏标记和当前构建诊断码、`app.wxss` 含配套样式；缺少页面产物、非 Taro 根模板或不安全路由路径必须阻断构建。
- 包体门禁按微信预览服务的有效依赖闭包计量：非独立分包的物理文件还要加上其执行所需的顶层共享 JavaScript，独立分包不叠加；固定回归必须阻断旧产物 `1,954,363 + 267,460 = 2,221,823 B` 的超限路径。动作指南图片从锁定源图以默认 JPEG 质量 80 重新生成，并把 186 张图片总量控制在 1.55 MiB 内，为代码和共享依赖保留余量。
- 页面运行时测试证明首页在启动根尚未加载或加载失败时仍有可见 UI，功能页面仅构造自身 Facade。
- generation 回归测试覆盖旧 200/401/ACCESS_REVOKED、登录竞争、退出/隐私清理、训练草稿、队列尾任务、AI 与跨功能多步骤调用。
- 定向测试、全量测试、类型检查、正式构建、OCR 独立评审与修复后复测，最后推送开发者工具预览并等待 Android 真机结果。
