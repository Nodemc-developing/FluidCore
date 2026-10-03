# 可复现基准

作者：ydxc20091。基准模块只测量 FluidCore 自身的公开操作，独立于运行插件。工具为 JMH 1.37；默认 Java 21，3 forks、5 次预热、10 次测量，每次 1 秒，开启 `gc` profiler。

```powershell
$env:FLUIDCORE_CE_JAR = 'C:/dependencies/craft-engine-paper-plugin-26.10-SNAPSHOT.jar'
.\gradlew.bat :benchmarks:jmh
.\gradlew.bat collectVerificationResults
```

原始 JSON 汇总到 `verification/results/jmh-native.json`。每次结果应记录 Git 提交、实际制品 SHA256、JDK、CPU、内存、OS、JMH 参数及复现命令；本模块的存在不表示发布包已经完成正式性能测量。

仅用于确认基准能运行的快速检查：

```powershell
.\gradlew.bat :benchmarks:jmh '-PjmhArgs=FluidCoreBenchmark.transferSimulation -f 1 -wi 1 -i 1 -w 100ms -r 100ms -p components=0 -p scenario=success'
```

## 场景与结果含义

覆盖填充、抽取、转移的成功／拒绝／部分成功；组件数量 0／4／16；组件相等与不相等、编码及解码。数量使用可复现的固定输入，一桶为 1000 mB。`long` 边界和溢出属于行为测试，不混入常规耗时测量。

执行类基准使用“操作＋恢复操作”保持每次输入一致，名称包含 `AndRestore`，其结果为整个往返成本。状态在每个 worker 的 trial 初始化，不在测量调用里创建新储罐。tear-down 核验初始容量／数量，测量结果会被 JMH 消费。

测量前核验操作返回值、数量守恒和模拟无副作用。JMH 的平均耗时与分配量不代表玩家交互延迟、服务端 TPS 或设备网络性能；实服分位数需要另行定义、运行和记录对应场景。
