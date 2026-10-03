# 第三方声明

FluidCore 自研部分 Copyright © 2026 ydxc20091。`api` 模块为 Apache-2.0，其他自研模块为 GPL-3.0-only。

## 运行与构建依赖

| 依赖 | 用途 | 分发说明 |
| --- | --- | --- |
| CraftEngine 26.10-SNAPSHOT | 必需服务端运行依赖 | 由使用者单独安装；本库插件不内嵌 CE JAR |
| Paper API | 编译服务端适配 | provided / compileOnly，不内嵌 |
| [bStats](https://bstats.org/) 3.2.1 | Basic usage metrics (plugin ID `34449`) | MIT, Bastian Oppermann; [license](LICENSES/bStats.txt) |
| [Sparrow YAML](https://github.com/Xiao-MoMi/sparrow-yaml) 1.0.22 | 运行插件配置读取 | GPL-3.0，XiaoMoMi；[许可证](LICENSES/Sparrow-YAML.txt) |
| [SnakeYAML Engine](https://bitbucket.org/snakeyaml/snakeyaml-engine/src) 3.1-SNAPSHOT-forked | Sparrow YAML 内嵌 YAML 引擎 | Apache-2.0，SnakeYAML contributors（Andrey Somov、Alexander Maslov 等）；[许可证](LICENSES/SnakeYAML-Engine.txt) |
| [Sparrow UI](https://github.com/Catnies/sparrow-ui) beta.38 | 按需诊断界面 | Apache-2.0，Catnies；[许可证](LICENSES/Sparrow-UI.txt) |
| JMH | 独立基准工具 | GPL-2.0 with Classpath Exception；只进入基准模块 |
| JUnit | 自动化验证 | EPL-2.0；只进入测试依赖 |

构建依赖版本由构建配置固定。发布前检查 shaded 产物中所有实际包含的组件，补齐各组件许可和必要 NOTICE，不能把本表当作打包依赖的自动许可审查结果。

## 随包交付的依赖源码

Sparrow YAML 1.0.22 的官方 Maven 源码、固定提交完整源码和原始 GPL 文本，以及它内嵌 SnakeYAML 分支的固定源码、Maven 构建和 Apache 许可，均实际提供在 [libs/source/sparrow-yaml-1.0.22](libs/source/sparrow-yaml-1.0.22/README.md)。清单记录官方地址、固定提交、SHA-256 与实际校验范围；完整重建尚未执行，不能把源码和文件校验当作二进制逐字节复现证明。
