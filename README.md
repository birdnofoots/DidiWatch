# DidiWatch · 滴滴看单

自建的无障碍事件监听器, 用来**替代 `adb dumpsys` 轮询发现来单**, 实现零 adb。

## 它做什么

监听 Android 原生无障碍事件(`TYPE_WINDOW_STATE_CHANGED`), 一旦滴滴车主的
订单卡界面 `com.sdu.didi.gsui/...BroadOrderActivity` 出现在前台, 立刻
POST 一条事件到本机脚本:

```
POST http://127.0.0.1:8765/didi_event
{"kind":"order_card","ts":1758600000123,"origin":"apk","pkg":"com.sdu.didi.gsui",
 "cls":"com.sdu.didi.gsui.broadorder.ordercard.BroadOrderActivity","etype":32}
```

服务端(webserve.py)收到后立即唤醒脚本去取无障碍树, 完成"发现→记录"闭环。

## 特点

- **零第三方依赖**: 原生 Activity + org.json, 构建快、APK 极小
- **只上报界面切换事件**, 不碰任何业务数据(节点树是可选项, 默认关)
- 事件去抖 1.5 秒, 避免同一张卡重复上报

## 构建

GitHub Actions 自动编译(推 main 或手动触发), 产物在 Actions Artifacts 里。
固定签名密钥通过 Secrets 注入(`ANDROID_KEYSTORE_BASE64` 等), 未配置时退回 debug 签名。

## 使用

1. 安装 APK, 打开 App → 「打开无障碍设置」→ 启用「滴滴看单 · 无障碍」
2. 保持 Termux 里的脚本在跑(它监听 8765 端口)
3. 订单卡一弹出 → App 立刻通知脚本

上报地址可改: `adb shell setprop debug.didiwatch.url http://127.0.0.1:8765/didi_event`
