# 旧浏览器方案已迁移

**此页不再是当前安装指南。** 从 DivZero **1.0.20** 起，内置界面与 HUD 使用 LDLib2，AI 动态界面使用 KubeJS；新构建与 Release 不再打包、发行或下载 MCEF / MCEF-Offline / JCEF / WebGUI。

请使用 [当前安装指南](BUILD_JAR.md)。Release Assets 提供 DivZero、LDLib2、KubeJS 和 Better Advanced Tooltips 四个独立 JAR；内置 F2 仅需前两者，Rhino 已嵌入。

旧 HTML/CSS/DOM 包需要显式转换或重写；没有浏览器备用渲染。升级时保留存档与数据库，移除旧 DivZero 的浏览器依赖，按新包重新验证业务交互。

1.0.19 及更早版本的历史附件、源码与许可按其发布时状态保留，不能与当前主 JAR 混装。需要考证旧实现时，请查看对应版本 Tag，避免将历史说明用于新版本。