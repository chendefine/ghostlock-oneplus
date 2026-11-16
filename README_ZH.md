# GhostLock Pad

[English](README.md)

**GhostLock Pad** 是一个可直接安装的安卓应用，在**一加平板 3 Pro（国行）/ OnePlus Pad 4（海外）**
这台锁定 Bootloader 的设备上获取临时 root 并加载
[KernelSU](https://github.com/tiann/KernelSU)——原版
[ghostlock-app](https://github.com/YuKongA/ghostlock-app) 并不支持该设备。

它通过一份预构建的 `preload.so` 载荷利用 **CVE-2026-43499**（futex PI
UAF），该载荷由 [x9u-preload-builder](https://github.com/koaaN/x9u-preload-builder)
针对这批平板自带的内核版本生成：

```
6.12.58-android16-6-g7704a1ae279b-ab15213644-4k
```

该载荷构建已在 OPPO Find X9 Ultra 真机上验证通过（与本机内核构建完全一致）；
应用在任何动作之前都会先严格校验 `uname -r` 是否精确匹配。

## 工作原理

```
GhostLock Pad（应用）
  └── Shizuku UserService（adb shell uid，无 seccomp）
        ├── 把内置 libghostlock_preload.so 释放到 /data/local/tmp/ghostlock/
        ├── 以 LD_PRELOAD 注入 /system/bin/true
        │     └── CVE-2026-43499 → 凭据覆盖 → uid 0 + SELinux 宽容
        │           └── 载荷安装 /data/local/tmp/su 与 root 守护进程
        ├── 校验：su -c id == uid=0
        └── KernelSU：su -c 'ksud late-load --kmi android16-6.12'
              └── 加载 kernelsu 模块、注册管理器 APK，恢复 SELinux 强制
```

两个设计要点继承自久经真机验证的 ghostlock-app：

- **Shizuku 是执行环境。** shell uid 进程没有 zygote 的 seccomp 过滤，
  这正是内核利用需要的。通过*无线调试*启动的 Shizuku 可以在平板上
  直接提供该环境，全程无需电脑。
- **日志走文件，绝不走管道。** 利用输出被重定向到文件并异步 tail；
  直接读管道会在 PI 竞争窗口内产生背压，最终导致内核 panic。

## 使用方法

1. **安装一个 root 管理器 APK**——任意内置 `libksud.so` 的 KernelSU 系
   管理器：[KernelSU](https://github.com/tiann/KernelSU)、
   [SukiSU Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra)、
   [ReSukiSU](https://github.com/ReSukiSU/ReSukiSU)（推荐）、
   [BakaSU](https://github.com/Baka-SU/BakaSU) 或
   [KowSU](https://github.com/KOWX712/KernelSU)。
   管理器此时不需要能工作，装上即可。
2. **安装 Shizuku**（[moe.shizuku.privileged.api](https://shizuku.rikka.app)），
   并通过**无线调试**启动：在 *设置 → 开发者选项 → 无线调试* 里配对，
   然后在 Shizuku 应用里启动服务——全部在平板上完成。
3. **安装 GhostLock Pad APK**（本仓库
   [Releases](https://github.com/chendefine/ghostlock-oneplus/releases)，
   每次推送都由 CI 构建）并打开。
4. 检查状态卡片：内核须显示**受支持**，Shizuku **就绪**；按提示授权，
   若检测到多个管理器则选择一个。
5. 点击**运行 GhostLock** 并观察日志。成功后：
   - 临时 root 生效（`su` 位于 `/data/local/tmp/su`，重启前有效）；
   - KernelSU 模块已延迟加载——打开管理器应用给自己授权。

一切**仅本次开机有效**：重启后内核恢复原状，重新运行 GhostLock Pad
即可。开机自动运行可参考
[ghostlock-anchor](https://github.com/byemaxx/ghostlock-anchor)。

### 无电脑 adb 方式

载荷同时以独立文件发布（Release 中的 `preload.so`）：

```sh
adb push preload.so /data/local/tmp/preload.so
adb shell 'chmod 0644 /data/local/tmp/preload.so'
adb shell 'LD_PRELOAD=/data/local/tmp/preload.so /system/bin/true'
adb shell '/data/local/tmp/su -c id'
```

## 警告

- **偏移与固件构建一一对应。** 应用只在上文列出的内核上运行。切勿把
  内置载荷注入其他构建的内核——可能导致内核 panic。
- 即使内核完全匹配，内核利用本身也可能导致设备崩溃或重启；保持对应
  固件可刷写，panic 后重试即可。
- 仅供对自有设备进行授权的安全研究。

## 支持其他固件构建

载荷由设备自己的固件重新生成，而非修改源码：

```sh
git clone https://github.com/koaaN/x9u-preload-builder
python3 build.py --boot boot.img --xbl-config xbl_config.img \
  --ndk-root /path/to/android-ndk
```

务必使用设备**当前 OTA 的 boot.img 与 xbl_config.img**，替换
`app/src/main/jniLibs/arm64-v8a/libghostlock_preload.so`，更新
[EngineContract.kt](app/src/main/kotlin/com/ghostlock/pad/EngineContract.kt)
中的 `EXPECTED_KERNEL` 以及
[.github/workflows/build.yml](.github/workflows/build.yml) 中的校验值，
然后重新构建 APK。

## 构建

每次推送 CI（GitHub Actions）都会构建：

```sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# build/app/outputs/apk/release/*.apk —— 使用仓库内 keystore 签名
```

发布签名密钥刻意公开（纯侧载应用，保证升级签名稳定）；见
[keystore/README.md](keystore/README.md)。

## 致谢与许可

以 [Apache License 2.0](LICENSE) 发布。基于以下工作：

- [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app) ——
  应用架构、Shizuku 执行模型、管理器扫描（Apache-2.0）
- [JoinChang/ghostlock-oneplus](https://github.com/JoinChang/ghostlock-oneplus) ——
  设备偏移研究、ksud late-load 流程
- [koaaN/x9u-preload-builder](https://github.com/koaaN/x9u-preload-builder) ——
  本应用内置的 preload 载荷
