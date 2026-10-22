# 流式语音识别插件

本插件是新写的在线增量识别实现，MIT；不包含原 SenseVoice 的离线切句逻辑。模型资源、下载、导入暂存、语言包配置与导出全在 `appdata/Kigtts/plugin/plugindata/engine.streaming-asr/`。模型不随插件或主 APK 分发。

- sherpa-onnx 1.12.38 的 OnlineRecognizer / OnlineStream Kotlin API，Apache-2.0。构建使用本仓库 libs 保存的固定 AAR 文件，独立打入本插件包，主程序没有运行库依赖。源：https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.38/sherpa-onnx/kotlin-api 。在线模型列表：https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/index.html 。
- ONNX Runtime Android 1.24.3，MIT，Maven `com.microsoft.onnxruntime:onnxruntime-android`。
- Apache Commons Compress 1.28.0 / IO 2.20.0 / Codec 1.19.0 / Lang 3.18.0，Apache-2.0，仅用于插件内导入官方压缩包；对应许可证与 NOTICE 在 licenses/。
- 模型许可证由发布者决定，导入与导出保留包内 LICENSE/NOTICE/原始说明；模型本身未进入 Git。

端点与 EOF 的状态处理遵循固定版本在线 API：每次输入后解码全部可用块，从模型当前假设产生 Partial，端点 Final 后重置；Paraformer 句尾追加 0.8 秒右上下文，其它 EOF 追加 0.3 秒静音并调用 inputFinished。关闭/取消都释放流和识别器。语言标记是包的管理元数据，不会把单语言模型改成多语言模型。
