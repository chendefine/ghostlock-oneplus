# GhostLock Pad

[中文](README_ZH.md)

**GhostLock Pad** is a directly installable Android app that gains temporary
root and loads [KernelSU](https://github.com/tiann/KernelSU) on the
**OnePlus Pad 3 Pro (CN) / OnePlus Pad 4 (global)** — a locked-bootloader
device the original [ghostlock-app](https://github.com/YuKongA/ghostlock-app)
does not support.

It exploits **CVE-2026-43499** (futex PI use-after-free) through a
prebuilt `preload.so` payload generated with
[x9u-preload-builder](https://github.com/koaaN/x9u-preload-builder) for the
exact kernel build these tablets ship:

```
6.12.58-android16-6-g7704a1ae279b-ab15213644-4k
```

That payload build was validated on OPPO Find X9 Ultra hardware, which
shares the same kernel build; the app hard-gates on the exact `uname -r`
string before doing anything.

## How it works

```
GhostLock Pad (app)
  └── Shizuku UserService (adb shell uid, no seccomp)
        ├── stage bundled libghostlock_preload.so → /data/local/tmp/ghostlock/
        ├── LD_PRELOAD it into /system/bin/true
        │     └── CVE-2026-43499 → cred overwrite → uid 0 + SELinux permissive
        │           └── payload installs /data/local/tmp/su + root daemon
        ├── verify: su -c id == uid=0
        └── KernelSU: su -c 'ksud late-load --kmi android16-6.12'
              └── kernelsu module + manager APK registration, SELinux re-enforced
```

Two design points are borrowed from the battle-tested ghostlock-app:

- **Shizuku is the execution context.** A shell-uid process has no zygote
  seccomp filter, which the exploit needs. Shizuku started through *wireless
  debugging* gives you that on-device, with no PC attached.
- **Logs travel through a file, never a pipe.** The exploit's output is
  redirected to a file and tailed asynchronously; reading a pipe applied
  backpressure inside the PI race window and ended in kernel panics.

## Usage

1. **Install a root manager APK** — any KernelSU-family manager that bundles
   `libksud.so`: [KernelSU](https://github.com/tiann/KernelSU),
   [SukiSU Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra),
   [ReSukiSU](https://github.com/ReSukiSU/ReSukiSU) (recommended),
   [BakaSU](https://github.com/Baka-SU/BakaSU), or
   [KowSU](https://github.com/KOWX712/KernelSU).
   The manager does not need to work yet — it just has to be installed.
2. **Install Shizuku** ([moe.shizuku.privileged.api](https://shizuku.rikka.app))
   and start it via **wireless debugging** (pair in
   *Settings → Developer options → Wireless debugging*, then start Shizuku
   from its own app — all on the tablet).
3. **Install the GhostLock Pad APK** from this repository's
   [releases](https://github.com/chendefine/ghostlock-oneplus/releases)
   (CI builds it on every push) and open it.
4. Check the status card: your kernel must show **Supported**, Shizuku
   **Ready**. Grant access when asked. Pick a manager if several are shown.
5. Tap **Run GhostLock** and watch the log. On success:
   - temporary root is live (`su` at `/data/local/tmp/su` until reboot),
   - the KernelSU module is late-loaded — open the manager app and grant
     yourself superuser.

Everything is **per-boot**: after a reboot the kernel is pristine again;
rerun GhostLock Pad. For an auto-run at boot, see
[ghostlock-anchor](https://github.com/byemaxx/ghostlock-anchor).

### No-PC adb alternative

The payload is also shipped standalone (`preload.so` in releases):

```sh
adb push preload.so /data/local/tmp/preload.so
adb shell 'chmod 0644 /data/local/tmp/preload.so'
adb shell 'LD_PRELOAD=/data/local/tmp/preload.so /system/bin/true'
adb shell '/data/local/tmp/su -c id'
```

## Warnings

- **Offsets are firmware-build-specific.** The app refuses to run on any
  kernel but the one above. Never preload the bundled payload onto another
  build — that can panic the kernel.
- Kernel exploitation can crash or reboot the device even on the right
  build; keep your firmware handy and rerun after a panic.
- For authorized security research on devices you own.

## Adding other firmware builds

The payload is regenerated from the tablet's own firmware, not from source:

```sh
git clone https://github.com/koaaN/x9u-preload-builder
python3 build.py --boot boot.img --xbl-config xbl_config.img \
  --ndk-root /path/to/android-ndk
```

Use the `boot.img` + `xbl_config.img` from the **exact OTA installed on the
device**, replace
`app/src/main/jniLibs/arm64-v8a/libghostlock_preload.so`, update
`EXPECTED_KERNEL` in [EngineContract.kt](app/src/main/kotlin/com/ghostlock/pad/EngineContract.kt)
and the digest in [.github/workflows/build.yml](.github/workflows/build.yml),
then rebuild the APK.

## Building

CI does this on every push (GitHub Actions):

```sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# build/app/outputs/apk/release/*.apk — signed with the committed keystore
```

The release keystore is intentionally public (sideload-only app, stable
signature for upgrades); see [keystore/README.md](keystore/README.md).

## Credits and license

Licensed under [Apache License 2.0](LICENSE). Built on the work of:

- [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app) — app
  architecture, Shizuku execution model, manager scanning (Apache-2.0)
- [JoinChang/ghostlock-oneplus](https://github.com/JoinChang/ghostlock-oneplus) —
  device offsets research, ksud late-load flow
- [koaaN/x9u-preload-builder](https://github.com/koaaN/x9u-preload-builder) —
  the preload payload this app ships
