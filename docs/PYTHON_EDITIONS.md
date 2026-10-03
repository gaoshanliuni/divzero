# Python 双版本与 Java 维护

版本 1.0.32 的两个主 JAR 使用同一 Mod ID，只能选择一个。无 Python 版通过独立源码集和构建目录去掉 Python 进程运行代码，普通游戏功能保留；Worker 和主模组携带一致的构建标识。AI 读取的技能目录、工具参数和运行规则也跟随版本变化，无 Python 版明确说明“此版本不支持Python”，不建议下载或查找系统解释器。

内置版完整资源由 `client/src/main/resources/META-INF/divzero/python/bundle-lock.json` 固定 URL、大小、SHA-256 和版本。构建机取得官方分发与 wheel，校验后打入 JAR。玩家首次使用无需网络：Java 读取 JAR、验证归档、拒绝路径越界和链接、解包完整 CPython，再从本地 wheel 离线准备基础库。Python 标准库、DLL、CPython/pip 及各库的许可证完整保留；额外的库许可证也在 JAR 的 `META-INF/divzero/python/licenses/` 下。

固定基础库：requests 2.34.2、Pillow 12.3.0、NumPy 2.5.3、colorama 0.4.6。传递依赖 certifi 2026.7.22、charset-normalizer 3.5.2、idna 3.20、urllib3 2.8.0 一并锁定。此初始环境的准备命令使用 `--no-index`。用户明确需要的其他库仍可通过现有确认工具访问 PyPI，固定库约束继续生效；这不是解释器首次联网下载。

普通运行仍是 Windows x64 单人本机账号范围，并非远程服务器命令或文件系统沙箱。原生聊天按每条具体脚本确认；Java 直接启动专用解释器，超时／取消回收子进程，完整输出和回执保存于专用目录。游戏内容创建仍优先走原生内容工具。

## 退出后的维护

F2 启动扩展计划提供“安排退出后应用”和“安排退出后恢复旧版本”。Java 使用当前游戏自带的 Java 启动内置维护程序，绑定进程 PID 与启动时间，等待该进程退出；再取得原安装器的文件锁、复核批准计划、源文件哈希、依赖和当前目标。只替换准确的管理槽位，原文件和回执保留；取消计划、较新文件或依赖冲突均拒绝操作，不恢复世界快照。

Python 损坏诊断提供“退出后修复 Python”。修复将专用运行时／环境移入保留目录，下一次使用再从 JAR 重新准备；不删除工作区脚本、结果或历史。更新／修复不会在游戏中替换正在使用的解释器或 Mod。

无法启动游戏时也可使用游戏的 Java 25，直接启动主 JAR 内的维护入口。以下参数必须取自已有的准确计划／回执，并先停止共用目录的全部游戏或服务器：

```text
<游戏Java>/bin/java -jar <DivZero主JAR> --maintenance --game <游戏目录> --request <新的UUID> --kind boot-upgrade --mods <mods目录> --operation <计划UUID> --plan-hash <计划SHA256> --mod-id <ModID> --action apply --offline true
```

`--action rollback` 恢复该计划的旧版本；`--preview true` 仅核验预览。坏扩展的准确回执可用 `--kind boot-recovery --build <构建UUID> --mod-id <ModID> --mods <目录> --action remove` 移出，`restore` 恢复；其它通用参数相同。无需外置 PowerShell。维护回执位于 `mineagent-maintenance`。

## 构建与验收

`-PpythonRuntime=none` 和 `-PpythonRuntime=bundled` 分别编译，默认 none。输出隔离在 `build/editions/<edition>/`，避免旧 class/resource 混入。`verifyPythonEdition` 检查最终外层 JAR、Worker JAR、真实资源摘要、许可和被剔除的执行类；构建流水线必须两个版本都通过后才发布同一 Release 的五个运行附件。

已加入离线初始化、真实库/DLL import、脚本输出、超时停止、维护版本交换／备份／恢复、锁冲突、取消和较新文件拒绝等回归。实际构建及 Native 结果将在完成后补充；不以源码或测试定义冒充已通过。
