中文 | [English](./README_en.md)

# Baby Buddy for Android

这是开源 Web 应用 [Baby Buddy](https://docs.baby-buddy.net/)（[源代码](https://github.com/babybuddy/babybuddy)）的安卓集成客户端**汉化版**，
专门针对照顾孩子时**快速**启动和停止计时器以及记录尿布更换进行了优化。

![应用示例图片](doc/images/demo_screenie-smaller.png)

## 获取方式

您可以直接从 [最新版本](https://github.com/babybuddy/babybuddy-for-android/releases/latest) 安装 原版APK，
也可以在 Google Play 商店购买此应用：

<a href='https://play.google.com/store/apps/details?id=eu.pkgsoftware.babybuddywidgets&pcampaignid=pcampaignidMKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img height='75' alt='在 Google Play 上获取' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a>

### 第三方仓库

此应用也可在 F-Droid 仓库中获取原版应用，由 [@IzzySoft](https://github.com/IzzySoft) 友情提供

<a href='https://apt.izzysoft.de/packages/eu.pkgsoftware.babybuddywidgets'><img height='75' alt='在 IzzyOnDroid 获取' src='https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png'/></a>

## 用户文档

[用户文档](doc/index.md) 可在此仓库中获取，也可以在应用内作为幻灯片查看。

## 构建说明

**警告**：此仓库使用了**非免费**的第三方资源。重新分发需要注明原始作者。请参阅"许可证"部分了解详情。

构建此项目需要一系列相对复杂的构建依赖项。
有一个 shell 脚本 `check-build-prerequisites.sh` 可以执行，它会验证所有构建依赖项是否满足。
但是，这里是构建环境所需软件包的完整列表：

- bash + coreutils
- curl
- GNU make
- gcc/g++ 用于构建 python 包（仅在某些情况下需要，取决于架构）
- jq（JSON 查询工具）
- ImageMagick
  - 具体需要命令：convert, composite
- pandoc >= 3.1.1
- Python3 >=3.8：用于转换和下载应用程序的资源文件
  - Python 包，可使用 `pip` 安装：pipenv, cython
- JDK 17，环境变量 `JAVA_HOME` 需要指向安装路径
- 已安装 Android SDK 和 Android NDK。环境变量 `ANDROID_HOME` 需要指向 Android SDK 安装路径

接下来，确保您已**递归**克隆了仓库：

~~~~~~.sh
$ git submodule update --init --recursive
~~~~~~

这将确保下载 zxing 库，准备好与 babybuddy-for-android 一起构建。

现在检查构建环境是否就绪。它应该产生如下输出：

~~~~~~.sh
$ bash check-build-prerequisites.sh
== Checking build tools ==
Check if GNU make is installed...
Check if curl is installed...
Check if ImageMagick is installed...
Check if python3, cython, and pipenv are installed...
Check if jq is installed...
Check if java 18.* is installed and accessible via JAVA_HOME...
Verify if ANDROID_HOME is set and contains the android sdk we need...
Reading targetSdk value from app/build.gradle...
Check if Android NDK is installed...
== Checking build dependencies ==
Check if zxing is downloaded...
== All required build tools and dependencies are installed ==
~~~~~~

接下来，您可以使用默认的 gradlew 命令来构建项目。
但是，仅从控制台运行时，不太可能有可用的安卓模拟器来运行完整的测试套件。
某些与安卓系统库交互的集成测试需要安卓模拟器。
要构建但不运行集成测试，请使用：

~~~~~~~~.sh
./gradlew build -PskipIntegrationTests
~~~~~~~~

希望这些都能正常工作，祝您构建愉快！

## 许可证

软件（代码）采用 MIT 许可证提供。
详情请参阅 [LICENSE.md](LICENSE.md)。

此外，此软件包含**媒体**文件，采用非免费署名许可证。
请参阅[署名页面](ATTRIBUTIONS.md)了解第三方媒体的来源和相应许可证。
