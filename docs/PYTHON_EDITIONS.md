# Python 双版本与 Java 维护

版本 1.0.32 的两个主 JAR 使用同一 Mod ID，只能选择一个。无 Python 版通过独立源码集和构建目录去掉 Python 进程运行代码，普通游戏功能保留；Worker 和主模组携带一致的构建标识。AI 读取的技能目录、工具参数和运行规则也跟随版本变化，无 Python 版明确说明“此版本不支持Python”，不建议下载或查找系统解释器。

内置版完整资源由 `client/src/main/resources/META-INF/divzero/python/bundle-lock.json` 固定 URL、大小、SHA-256 和版本。构建机取得官方分发与 wheel，校验后打入 JAR。玩家首次使用无需网络：Java 读取 JAR、验证归档、拒绝路径越界和链接、解包完整 CPython，再从本地 wheel 离线准备基础库。Python 标准库、DLL、CPython/pip 及各库的许可证完整保留；额外的库许可证也在 JAR 的 `META-INF/divzero/python/licenses/` 下。

固定基础库：requests 2.34.2、Pillow 12.3.0、NumPy 2.5.3、colorama 0.4.6。传递依赖 certifi 2026.7.22、charset-normalizer 3.5.2、idna 3.20、urllib3 2.8.0 一并锁定。此初始环境的准备命令使用 `--no-index`。用户明确需要的其他库仍可通过现有确认工具访问 PyPI，固定库约束继续生效；这不是解释器首次联网下载。

普通运行仍是 Windows x64 单人本机账号范围，并非远程服务器命令或文件系统沙箱。原生聊天按每条具体脚本确认；Java 直接启动专用解释器，超时／取消回收子进程，完整输出和回执保存于专用目录。游戏内容创建仍优先走原生内容工具。

解释器和库放在 `%LOCALAPPDATA%/DivZero/python/<游戏目录标识>`，由游戏的真实路径生成独立标识并校验归属。这避免深层 Minecraft 实例目录触发 Windows 可执行文件路径限制；不会查找或使用这里以外的系统 Python。用户脚本、工作区文件和操作输出仍留在实例的 `mineagent-host`。验收通过 `divzero.pythonRuntimeRoot` 将运行缓存限定在隔离测试的 `build` 目录；若管理员自定义此 JVM 属性，手动维护入口须使用相同设置，界面启动的维护程序会自动继承。

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

公开源码 `2361b65c10c965bd7a719c08892af3f8201d561d` 的 [Actions 37111280375](https://github.com/gaoshanliuni/divzero/actions/runs/37111280375) 对两个版本的编译、成品检查和测试全部通过。回归实际运行 CPython、SSL/SQLite、requests/Pillow/NumPy/colorama，包含图片写出、UTF-8 输出、长游戏目录、子进程、超时和取消；维护测试验证准确版本交换、备份、恢复、锁冲突、取消及较新文件拒绝。

隔离游戏验证：

- 无 Python 包：`90436c93-bb7a-45ef-b675-95aa0d647679` 通过。实际类加载确认解释器管理、执行器及相关运行类不存在，资源归档也不存在；技能明确不支持 Python，跟随正常，不创建 Python 环境。
- 内置包首次使用：`e8c8a5dd-456c-4943-8db3-e845b7c7d11b` 通过。原生聊天确认后导入全部基础库和 DLL，执行 NumPy 计算、写出 PNG。故意损坏固定库后返回 `PYTHON_ENV_INTEGRITY_FAILED`、`NOT_STARTED`；安排的 Java 维护程序在游戏存活时没有修改文件。
- 同一实例退出后：维护回执为 `REPAIR_STAGED_FOR_NEXT_USE`，原环境保留于隔离目录，工作区文件保留。重进同一世界后的 `persistent-skill-resume/result.json` 再次通过，重新从 JAR 离线准备并成功执行库检查。

成品 SHA-256：

| 版本 | SHA-256 |
| --- | --- |
| no-python | `a9276ebf484487fa42ee08ba1cee0c377ddb738412672bee8320a0744185d162` |
| with-python | `bed5f94da21e4d3390fe986f219dd40aa0911754927e07e2210892244537929a` |

首次内置包验证 `7b07e350-6238-48ec-b0da-c2d631509ba3` 曾因 pip 丢失 Windows 长路径前缀而失败，失败记录保留。后续回归进一步覆盖了 ensurepip 子进程和 Windows 可执行文件路径限制，最终采用 Java 初始化已校验的 pip 文件与专用短缓存，未更改系统长路径设置。测试未调用付费模型，未改动生产存档、配置或权重；BOOT 文件机制测试不代替任意扩展升级后的游戏兼容性验证。
