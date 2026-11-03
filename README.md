<div align="center">

# 广油驿站青春版

**一个 App，取遍所有驿站。**

拼多多 · 淘宝菜鸟 · 京东 · 顺丰 —— 身份码与快递信息一站式聚合

**[⬇ 下载最新 APK](https://github.com/olenxane/GDUPT_KD/releases/latest/download/gyyz-latest.apk)**

本项目基于 [MaShangQu-APP](https://github.com/njpemo/MaShangQu-APP) 逆向出的接口进行二次开发，特别感谢作者“拼好饭给你”。

</div>

---

<div align="center">
</div>

## 功能说明
| 功能 | 说明 |
|:---:|---|
|**三平台身份码聚合** | 拼多多 / 淘宝菜鸟 / 京东，大幅二维码条码一屏出示，下拉刷新 + 可配置自动刷新；**双击条码进全屏亮码**，单击退出 |
|**快递列表聚合** | 多平台站点包裹统一视图，按平台 / 站点筛选，跨平台按运单号去重合并、互补查码（菜鸟优先 → 拼多多 → 顺丰）；支持手动输入运单号加入跟踪，30s 自动刷新节流 |
|**取件码直达** | 单件快递取件码大字大图展示，驿站柜台一步到位；他人代查走**手机尾号验证** |
|**登录态守护** | 启动自动巡检各平台凭据有效性（失效标红、一键重登）+ WorkManager 周期性保活巡检 |


### 登录方式

| 平台 | 方式 |
|---|---|
| 淘宝 | 手机号 + 短信验证码（App 内协议登录，含风控二次验证） |
| 京东 | 手机号 + 短信验证码（协议直连：prepare → 滑块 WebView 截 vt → 发码；兜底粘贴 pt_key/pin） |
| 拼多多 | 扫码登录（App 内生成二维码，拼多多 App 扫一扫相册识别） |

## 安装与构建

**从源码构建安装**：

- Android Studio ｜ Kotlin 2.x + Jetpack Compose ｜ minSdk 26 / targetSdk 34

```bash
cd app
.\gradlew.bat assembleDebug        # 调试包
.\gradlew.bat assembleRelease      # 正式包
```

产物位于 `app/app/build/outputs/apk/`。

## 项目结构

```
GDUPT_KD/
├── app/                    Android 工程
│   ├── app/                宿主壳：页面、导航、调试日志导出
│   ├── core/
│   │   ├── model/          统一数据模型与错误模型
│   │   ├── network/        OkHttp 基础配置
│   │   ├── storage/        Keystore 加密存储、设置
│   │   └── webview/        通用登录 WebView 组件
│   └── platform/
│       ├── api/            PlatformAdapter 接口契约
│──     └── pdd/ taobao/ jd/ sf/   平台适配器实现
```