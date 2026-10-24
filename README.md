# DidiWatch · 滴滴看单

自建的无障碍事件监听器, 用来**替代 `adb dumpsys` 轮询发现来单**, 实现零 adb。

## 它是什么

一个**可复用的无障碍事件平台**：

- `:lib` —— 核心库（别的 App `implementation project(':lib')` 即可，manifest 合并会自动把
  `WatchService` 声明进宿主）
- `:app` —— 演示壳 APK，也是滴滴这套的宿主

同机其它 App 有**两种方式**拿到事件（都不需要无障碍授权）：
1. **localhost HTTP API**（127.0.0.1:8766）—— 推荐，跨语言、可跨进程、可查询历史
2. **系统广播** `com.birdnofoots.didiwatch.EVENT`（extra `json`）—— 运行时注册接收器即可

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

## 本机 API

```
GET  /v1/health              服务状态
GET  /v1/config              当前配置
POST /v1/config              {pkg, act, include_tree}
GET  /v1/events/latest       最近一次事件
GET  /v1/events/tail?n=50    最近若干条
POST /v1/subscribe           {"url":"http://127.0.0.1:PORT/path"}  订阅回调
POST /v1/unsubscribe         {"url":"..."}
```
鉴权：App 首次启动会生成一个 token 显示在界面上；配置了 token 后请求需带 `?token=`。
**服务只监听 127.0.0.1**，不出网。

## 使用

1. 安装 APK, 打开 App → 「打开无障碍设置」→ 启用「滴滴看单 · 无障碍」
2. 保持 Termux 里的脚本在跑(它监听 8765 端口)
3. 订单卡一弹出 → App 立刻通知脚本

上报地址可改: `adb shell setprop debug.didiwatch.url http://127.0.0.1:8765/didi_event`
