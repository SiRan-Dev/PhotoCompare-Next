# Photo Compare Next

一款专注于「照片对比」的 Android 应用：把同一相册里的两张照片上下叠放，独立翻页、同步缩放平移，快速挑出更满意的那一张。本仓库 fork 自 [Simon Niederberger 的 image-compare](https://github.com/SiRan-Dev/image-compare)，并在其原功能基础上使用 **Jetpack Compose** 进行了完整重写与扩展。

> 上游原版基于 Java + View + XML（SubsamplingScaleImageView + Glide + ViewPager）。本仓库已迁移到 Kotlin + Compose + Coil + Media3，应用包名同步迁移至 `com.sirandev.photocompare`。

## 功能特性

### 对比页（核心）

- **双窗格上下对比**：两个垂直堆叠、可独立横向翻页的图片窗格，互斥排除对方当前照片，滑动时不会出现「跨过去又跳回」的纠正动画。
- **同步缩放与平移**：开启后整个对比屏作为一个手势面，捏合/拖动/惯性滑动/双击缩放可在任意位置（含信箱区、分隔线）触发，并由 `CompareMediator` 带尺寸补偿地镜像到另一窗格，使两张分辨率/构图不同的照片仍可逐像素对齐。
- **动图（Live Photo）播放**：长按任一窗格会同时解析两张照片的动态内容并双窗格同步播放；视频轨按静态图的变换矩阵精确叠加到照片之上。支持以下动图格式：
  - Google MicroVideo v1（Xiaomi HyperOS、旧版 Google 相机）
  - Google Motion Photo v2（Pixel、新版三星、OPPO）
  - 华为 / 荣耀（尾部扫描 `ftyp` box）
  - vivo（同名 `.mp4` 配对文件）
- **在系统相册中打开**：对比页底部右侧入口，调用系统相册并定位到当前照片。
- **替换上/下照片**：从 ⋮ 菜单打开任意相册的照片选择器替换对应窗格，对比索引自动重新锚定。
- **EXIF 摘要显示**、**深色复选框样式**、**重置状态**、**查看已选图片** 等操作集成在顶部栏与溢出菜单中。

### 图片来源选择（首页）

- 按相册文件夹浏览
- 按拍摄日期选择
- 「所有照片」跨文件夹混选，用于跨相册对比

### 图片列表页

- 标记对比（长按或点击进入对比）
- 排序：最新优先 / 按文件名排序
- 跨文件夹标记：在另一文件夹标记的照片可被带回当前列表进行对比

### 已选图片页

- 移除、反选、删除选中 / 删除未选中（带相册与数量确认）
- 分享选中图片

### 设置页

- 主题模式：跟随系统 / 浅色 / 深色
- 动态取色（Material You）
- 预测式返回手势（边缘滑动预览动画）
- GitHub 项目入口

### 适配与体验

- Material 3 组件 + 动态取色，浅/深色主题
- 启动器图标适配 Android 13+ 单色主题
- 多语言：English、简体中文、Deutsch
- 预测式返回手势（Android 14+）
- Edge-to-edge 适配

## 技术栈

| 维度 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose（BOM 2026.08.00）+ Material 3 |
| 架构 | 单 Activity + Navigation Compose + ViewModel + Repository |
| 图片加载 | Coil 3 |
| 动图播放 | Media3 ExoPlayer / UI |
| 偏好存储 | DataStore Preferences |
| 元数据 | AndroidX ExifInterface |
| 构建 | Gradle 9.5（Kotlin DSL）+ AGP 9.3 |
| 目标 / 最低 SDK | compileSdk 37 · targetSdk 37 · minSdk 24（Android 7.0+） |
| 版本 | versionCode 52 · versionName 1.1.0 |

## 架构概览

```
MainActivity  ──  NavGraph（Navigation Compose）
                  ├─ pool/SelectImagePoolScreen   选择图片来源（文件夹/日期/全部）
                  ├─ settings/SettingsScreen      设置
                  ├─ images/ListImagesScreen      图片列表与标记
                  ├─ compare/CompareScreen         对比页（核心）
                  └─ selected/SelectedImagesScreen 已选图片管理
```

- **SessionViewModel**：Activity 作用域，跨页面持有图片来源、图片列表、选中状态与对比索引，替代了原版 Intent/Bundle 链路。
- **CompareMediator**：协调上下窗格，移植自原 `PhotoViewMediator`；在同步开启时按 `CompareMath` 的尺寸补偿算法把缩放/平移映射到另一窗格。
- **zoomable**：自实现的可缩放状态与手势 Modifier（替代 SubsamplingScaleImageView），与 Pager 协作处理手势消费、惯性滑动与同步回调。
- **livephoto**：纯 JVM 的 `LivePhotoParser` 负责动图结构探测，`ContentLivePhotoResolver` / `VideoExtractor` 负责内容解析与视频抽取，`LivePhotoPlayerController` 负责双窗格同步播放。
- **data**：`MediaStoreRepository` 查询相册/图片，`DeleteController` 处理删除，`PreferencesRepository` 封装 DataStore。

## 构建

### 前置要求

- JDK 21
- Android SDK（compileSdk 37）
- 可选：用于 release 签名的 `keystore.properties`（缺失时 release 构建将退回使用 debug 签名）

`app/build.gradle.kts` 会按顺序查找以下路径的 `keystore.properties`：

1. 项目根目录 `keystore.properties`
2. `D:/EchoRan/Documents/Projects/KeyStore/keystore.properties`

### 构建命令

```bash
# Windows
.\gradlew.bat assembleRelease

# macOS / Linux
./gradlew assembleRelease
```

产物路径：`app/build/outputs/apk/release/app-release.apk`

## 致谢

- 原项目作者 [Simon Niederberger](https://github.com/SiRan-Dev/image-compare)，本仓库的对比同步逻辑、删除流程与整体交互范式均源自其设计与实现。
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Coil](https://github.com/coil-kt/coil)
- [Media3](https://github.com/androidx/media)
