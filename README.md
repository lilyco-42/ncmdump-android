# ncmdump-android

![Build](https://github.com/lilyco-42/ncmdump-android/actions/workflows/build.yml/badge.svg)

Android 平台上的网易云音乐 .ncm 文件解密工具。

基于 [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) C++ 版移植，解密核心通过 Android NDK 直接复用原仓库代码，经 AES 解密 + XOR 解码将 ncm 缓存文件还原为标准 mp3/flac 格式。

## 功能

- 解密 .ncm 文件为 mp3 / flac 格式
- 自动识别输出格式（ID3 标签头 → mp3，否则 → flac）
- 提取专辑封面图为独立文件
- 自动保存到设备 Music/ncmdump/ 目录
- 批量选择、批量解密
- 模块化翻译（中文 / 英文）

## 下载

在 [GitHub Releases](https://github.com/lilyco-42/ncmdump-android/releases) 页面下载最新的 APK 安装包。

## 截图

| 空状态 | 文件列表 | 解密完成 |
|---|---|---|
| 选择 .ncm 文件后显示列表 | 显示文件名与状态（待解密/解密中/已完成/失败） | 解密后自动导入系统音乐库 |

## 构建

### 环境要求

- Android SDK (compileSdk = 36)
- NDK (CMake 4.1.2+)
- JDK 17+

### 本地构建

```bash
git clone https://github.com/lilyco-42/ncmdump-android.git
cd ncmdump-android
./gradlew assembleDebug
```

APK 产出在 `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions

推送至 main/master 分支会自动触发 CI 构建，构建产物可在 Actions 页面下载。手动触发：

```
仓库 → Actions → Build Android APK → Run workflow
```

## 翻译系统

支持模块化翻译，接口与实现分离。

### 添加语言

在 `app/src/main/assets/translations/` 下新建 `{语言代码}.json`，参照 `zh.json` 或 `en.json` 的键结构填写。

### 切换语言

```kotlin
// MainActivity.kt
TranslationService.init(this, languageCode = "zh")  // 中文
TranslationService.init(this, languageCode = "en")  // 英文
TranslationService.init(this, languageCode = "ja")  // 日文（需先添加 ja.json）
```

### 自定义翻译源

实现 `Translator` 接口，可以从任意数据源加载翻译：

```kotlin
class RemoteTranslator : Translator {
    override fun translate(key: String, vararg args: Any?) = /* 从远程 API 获取 */
    override val languageCode = "fr"
}
TranslationService.setTranslator(RemoteTranslator())
```

## 项目结构

```
├── app/
│   └── src/main/
│       ├── assets/translations/     ← 翻译 JSON 文件
│       ├── cpp/                     ← C++ 原生代码 (NDK)
│       │   ├── ncmcrypt.cpp/h       ← 核心解密逻辑
│       │   ├── aes.cpp/h            ← AES 加解密
│       │   ├── base64.h             ← Base64 编解码
│       │   ├── cJSON.cpp/h          ← JSON 解析
│       │   ├── jni_bridge.cpp       ← JNI 桥接层
│       │   └── CMakeLists.txt       ← NDK 构建配置
│       ├── java/com/ncmdump/
│       │   ├── MainActivity.kt      ← Compose UI + 文件选择 + 导出
│       │   ├── NcmDecryptor.kt      ← JNI 声明 + Kotlin 封装
│       │   └── i18n/                ← 翻译模块
│       │       ├── Translator.kt          ← 接口
│       │       ├── JsonTranslator.kt      ← JSON 文件实现
│       │       └── TranslationService.kt  ← 全局访问点
│       └── res/                     ← 资源文件
├── .github/workflows/build.yml      ← CI 工作流
└── build.gradle.kts                 ← 项目配置
```

## 与原版的区别

| 特性 | ncmdump (C++) | ncmdump-android |
|---|---|---|
| 平台 | Windows / macOS / Linux | Android |
| 界面 | CLI | Jetpack Compose Material 3 |
| 元数据写入 | taglib | Android MediaStore API |
| 封面图 | 嵌入音频文件 | 提取为独立 jpg/png 文件 |
| 构建 | CMake + vcpkg | Gradle + NDK |
| 翻译 | 无 | 模块化 i18n 系统 |

## 致谢

- [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) — 原始 C++ 项目
- [anonymous5l/ncmdump](https://github.com/anonymous5l/ncmdump) — 原始算法参考

## License

本项目基于原项目 [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) 的 [Apache-2.0 License](LICENSE.txt) 发布。
