# 事件查看器 (EventViewer)

一个**可复用的无障碍事件服务平台**：监听指定应用的界面切换事件，立刻把事件推给
本机其它应用。零第三方依赖（原生 Activity + org.json）。

## 它做什么

监听 Android 原生无障碍事件（`TYPE_WINDOW_STATE_CHANGED`），当**你选中的应用**的
指定界面出现在前台时，立刻把事件 POST 给已订阅的回调地址。

## 能力

- **可选监听目标**：App 内选择任意已安装应用，支持多个（逗号分隔）
- **本机 API**（只监听 127.0.0.1，不出网）：查询、配置、订阅回调
- **系统广播**：`com.birdnofoots.eventviewer.EVENT`（extra `json`）
- **可选节点树**：事件里带上完整无障碍节点树（vid/text/bounds/clickable）

## 权限

`QUERY_ALL_PACKAGES`（普通权限，manifest 声明即生效，无运行时弹窗）——
用于列出已安装应用。⚠️ Google Play 政策限制该权限；本项目自签自装不受影响。

## 构建

GitHub Actions 自动编译（推 main 或手动触发），产物在 Actions Artifacts。
签名密钥走 Secrets（`ANDROID_KEYSTORE_BASE64` 等），未配置则退回 debug 签名。

## 本机 API

```
GET  /v1/health              服务状态
GET  /v1/config              当前配置
POST /v1/config              {pkg, act, include_tree}   pkg 支持逗号分隔多个
GET  /v1/events/latest       最近一次事件
GET  /v1/events/tail?n=50    最近若干条
POST /v1/subscribe           {"url":"http://127.0.0.1:PORT/path"}  订阅回调
POST /v1/unsubscribe         {"url":"..."}
```
鉴权：App 首次启动会生成一个 token 显示在界面上；配置了 token 后请求需带 `?token=`。

## 使用

1. 安装 APK → 打开 App → 「选择监听的应用」勾选目标
2. 「打开无障碍设置」→ 启用「事件服务」
3. 目标应用的界面一变化，事件立刻推送
