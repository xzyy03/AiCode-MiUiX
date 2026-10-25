# 在容器中构建 Flutter release APK

《在容器中编译 Flutter 应用》完成了 debug 包的构建链路。本文在同一套 Debian 12 (bookworm) aarch64 PRoot 容器里补齐 **release 包**：用 qemu 用户态转译运行 Flutter 只为 x64 提供的 AOT 编译器，即可在本地产出 release APK，已在容器内实测通过。

::: tip 支持范围
本文构建 **android-arm64** 目标的 release 包，环境为 Flutter 3.27.4（Dart 3.6.2）+ Android SDK 35。android-arm / android-x64 的处理方式相同（换成各自目标的 linux-x64 版 gen_snapshot），profile 包换成 `android-arm64-profile/linux-x64/gen_snapshot`，机制一致。
:::

::: tip 镜像与网络说明
qemu 与 x86_64 运行时库均从国内镜像下载。镜像产物与官方完全等价：即使网络可直接访问官方源，也无需还原为官方配置。
:::

## 环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Debian GNU/Linux 12 (bookworm) aarch64（PRoot） |
| Flutter | 3.27.4 stable（Dart 3.6.2） |
| SDK | platforms android-35，build-tools 35.0.0 |
| qemu | qemu-user-static 7.2（Debian 1:7.2+dfsg-7+deb12u18+b3） |
| x86_64 运行时 | libc6 2.36（amd64） |

::: tip 前置条件
先按《在容器中编译 Flutter 应用》第 1~5 节装好 JDK、Android SDK（含 ARM64 原生二进制的替换）、Flutter SDK 与镜像配置，并确认 debug APK 能正常构建，再继续本文。
:::

## release 包为什么默认构建不出来

Flutter 打 release 包时会调用 `gen_snapshot`，把 Dart 代码 AOT 编译成机器码。这个工具按宿主平台分发，而官方只为 Android 目标提供了 `linux-x64` 版本，所以引擎产物目录里只有这一个子目录：

```
bin/cache/artifacts/engine/android-arm64-release/
└── linux-x64/gen_snapshot
```

在 Linux ARM64 宿主上，Flutter 会去找 `android-arm64-release/linux-arm64/gen_snapshot`，目录不存在，构建直接失败：

```
Target android_aot_release_android-arm64 failed:
  Failed to find ".../android-arm64-release/linux-arm64/gen_snapshot" in the search path.
```

这不是配置问题，上游至今未支持 Linux ARM64 宿主。gen_snapshot 产出的 AOT snapshot 内嵌 Dart SDK 的版本哈希，由运行时的 `libflutter.so` 校验，因此**不能拿别的 Flutter 版本编译的 gen_snapshot 顶替**（会报 `Invalid SDK hash` 或 snapshot 版本不符），社区第三方产物同样绑定各自的 Flutter 版本。

可行做法只有一条：让 x64 版 gen_snapshot 在本机跑起来，即 qemu 用户态转译。

## 1. 安装 qemu 用户态模拟器

直接 `apt install qemu-user-static` 会连带更新 systemd 等组件，在容器里没必要。改为只下载 deb 解包到独立目录：

```bash
cd /tmp
apt-get download qemu-user-static            # 约 56 MB，走容器内已配置的镜像
mkdir -p /opt/qemu-user-static
dpkg-deb -x qemu-user-static_*.deb /opt/qemu-user-static
/opt/qemu-user-static/usr/bin/qemu-x86_64-static --version
# qemu-x86_64 version 7.2.22 (Debian 1:7.2+dfsg-7+deb12u18+b3)
```

可选：建个软链方便调用（容器 PATH 不含 `/usr/local/bin`，用绝对路径亦可）：

```bash
ln -sf /opt/qemu-user-static/usr/bin/qemu-x86_64-static /usr/local/bin/qemu-x86_64-static
```

## 2. 补齐 x86_64 运行时库

gen_snapshot 是动态链接的 x86_64 程序，只依赖 loader 与 libc，但容器里没有任何 x86_64 库：

```bash
qemu-x86_64-static <gen_snapshot> --version
# qemu-x86_64-static: Could not open '/lib64/ld-linux-x86-64.so.2': No such file or directory
```

::: warning 注意
qemu 的 `-L <prefix>` 与 `QEMU_LD_PREFIX` 对这个二进制实测无效（仍按绝对路径找 loader），必须把库放到系统路径下。
:::

从 Debian 镜像取 amd64 版 libc6：

```bash
cd /tmp
curl -fsSL -o libc6-amd64.deb \
  http://mirrors.huaweicloud.com/debian/pool/main/g/glibc/libc6_2.36-9+deb12u14_amd64.deb
mkdir -p /opt/x86_64-root
dpkg-deb -x libc6-amd64.deb /opt/x86_64-root

# 放到系统库路径（容器 /lib 是指向 usr/lib 的软链）
mkdir -p /usr/lib/x86_64-linux-gnu /lib64
cp -a /opt/x86_64-root/lib/x86_64-linux-gnu/. /usr/lib/x86_64-linux-gnu/
ln -sfn ../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
```

验证：

```bash
qemu-x86_64-static ~/.local/flutter-3.27.4/bin/cache/artifacts/engine/android-arm64-release/linux-x64/gen_snapshot --version
# Dart SDK version: 3.6.2 (stable) ... on "linux_simarm64"
```

输出里的 `linux_simarm64` 表示 x64 宿主、arm64 目标，正是 android-arm64 release 需要的那份。

## 3. 把 gen_snapshot 换成 qemu 包装脚本

保留原二进制并改名，用 shell 脚本转发：

```bash
R=~/.local/flutter-3.27.4/bin/cache/artifacts/engine/android-arm64-release
Q=/opt/qemu-user-static/usr/bin/qemu-x86_64-static

mv $R/linux-x64/gen_snapshot $R/linux-x64/gen_snapshot.x64-real
cat > $R/linux-x64/gen_snapshot <<EOF
#!/bin/sh
exec $Q "\$(dirname "\$0")/gen_snapshot.x64-real" "\$@"
EOF
chmod +x $R/linux-x64/gen_snapshot

# Flutter 按宿主平台选目录，linux-arm64 下也放一份
mkdir -p $R/linux-arm64
cp -a $R/linux-x64/. $R/linux-arm64/
```

::: warning 注意
`flutter upgrade` 或 `flutter precache` 重装引擎产物后，这两个目录会被覆盖，需要重新执行本节。
:::

## 4. 编译

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$PATH:~/.local/flutter-3.27.4/bin
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # 引擎产物等走中国镜像（默认推荐）
unset PUB_HOSTED_URL    # 使用官方 pub.dev（默认推荐，可直连）

cd ~/workspace/<项目>
flutter build apk --release --target-platform android-arm64
```

上方环境变量为默认推荐配置（与 debug 教程第 5 节一致），网络可达官方源时也可保留，无需还原。

产物：

```
build/app/outputs/flutter-apk/app-release.apk
```

首次构建约 15 分钟，绝大部分花在下载 lint、kotlin-compiler、intellij-core 等 release 专用 Gradle 依赖上，耗时取决于网络。AOT 由 qemu 单线程转译，约 2~3 分钟，产出 `build/app/intermediates/flutter/release/arm64-v8a/app.so`；打包签名数十秒。qemu 的转译比 x64 原生慢，但不是耗时主因，增量构建会快得多。

## 5. 验证产物

```bash
A=~/workspace/<项目>/build/app/outputs/flutter-apk/app-release.apk

~/android/sdk/build-tools/35.0.0/aapt2 dump badging $A | head -3
unzip -l $A | grep 'lib/.*\.so'
~/android/sdk/build-tools/35.0.0/apksigner verify --print-certs $A
```

正常时 `aapt2` 会输出 `package` 与 `minSdkVersion` / `targetSdkVersion`，`unzip -l` 中应出现以下文件（说明是 AOT 产物），`apksigner` 退出码为 0：

```
lib/arm64-v8a/libapp.so
lib/arm64-v8a/libflutter.so

Signer #1 certificate DN: ...
```

- `lib/arm64-v8a/libapp.so` 存在，说明确实是 AOT 产物（debug 包没有这个文件）。
- 只包含 `arm64-v8a` 一个 ABI，符合 `--target-platform android-arm64`。
- 未配置 release 签名时，Gradle 自动使用 debug keystore 签名，可安装验证；正式发布需按 Flutter 官方文档配置 `android/key.properties` 与 keystore。

## 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| `Failed to find .../android-arm64-release/linux-arm64/gen_snapshot` | 未做第 3 节，或引擎产物被重装覆盖 | 按第 3 节重新放包装脚本 |
| `qemu-x86_64-static: Could not open '/lib64/ld-linux-x86-64.so.2'` | 缺 x86_64 glibc，或用 `-L` 试图绕过 | 按第 2 节把库放到 `/usr/lib/x86_64-linux-gnu` 与 `/lib64` |
| AOT 阶段长时间无输出、CPU 占用高 | qemu 正在转译，属正常现象 | 等待，不要中断 |
| `Invalid SDK hash` / snapshot 版本不符 | 用了其他 Flutter 版本的 gen_snapshot | 必须使用当前 SDK 自带的那份 x64 二进制 |
| Release 构建提示缺少签名 | 项目未配置 release 签名 | 配置 `android/key.properties` 与 keystore，或先产出 debug 签名版本验证链路 |

## 限制与提示

- **本文只覆盖 android-arm64**：其它 ABI 与 profile 包的目录对应关系相同，需自行验证后再用于正式出包。
- **引擎产物重装后需重做第 3 节**：`flutter upgrade`、`flutter precache` 会覆盖 qemu 包装脚本。
