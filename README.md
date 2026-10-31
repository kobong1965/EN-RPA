# EN-RPA · 执手

EN-RPA 是基于 [OculiX 4.0.0](https://github.com/oculix-org/Oculix) 改造的中文桌面任务执行器。执手负责运行预先配置的任务并展示结果，可配合独立的软件决定任务内容。

## 内容

- `API/`、`IDE/`：OculiX 基础源码、中文界面、执行中心、任务库、运行记录、本地指令接口和高级工具。
- `OculiX/*.sikuli/`：示例任务及数据采集任务入口。
- `oculix-flow/`：屏幕定位和 OCR、淘宝与抖店数据采集、缺失日期检查、调度以及店脉账本同步桥接。
- `IDE/src/main/resources/icons/zhishou-logo.*`：执手图标。
- [上游说明](README-UPSTREAM.md)和 [MIT 许可](LICENSE)：OculiX 项目信息和许可。

## 构建和运行

此仓库包含应用和工作流源码，不包含预编译应用、大型第三方运行时或用户的登录状态。构建脚本 `build-zhishou.ps1` 期望在仓库目录内找到 JDK 21、经过脚本校验的 OculiX Windows 4.0.0 基础 JAR 和 JRuby 编译依赖，具体路径和校验值见脚本。也可以参阅上游 Maven 构建方式。不能仅克隆后直接双击运行，需要先配置这些依赖。

每日数据流程另外需要安装 Python 及 `oculix-flow/ocr/requirements-lock.txt` 中的依赖、ego-browser、目标网站的有效登录会话，以及兼容的店脉软件和账本。店脉执行文件路径可通过 `DIANMAI_EXE` 环境变量指定。将 `daily/stores.example.json` 和 `douyin/stores.example.json` 复制为同目录的 `stores.json` 并填写、核对店铺绑定；淘宝 ego 采集的浏览器配置与店铺绑定还需在 `taobao_ego/sync.py` 和 `taobao_ego/collect.mjs` 中按实际环境配置。示例未确认前不会执行同步。

`oculix-flow/daily_scheduler.py` 是本机调度器；具体时间以该文件中的设置为准。启用前先检查店铺、时区、页面和目标账本，保留登录并确保桌面可操作。站点页面或权限变化可能需要重新调整采集步骤。

出于隐私，仓库不含原机器的账号绑定、运行日志、截图、浏览器数据或店铺账本。真实配置和执行结果应留在本地，不应提交到公开仓库。
