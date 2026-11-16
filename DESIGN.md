# GhostLock Pad — design notes

This repository contains a *new*, deliberately small app. It is not a fork of
ghostlock-app: the original app's C++ exploit runtime cannot support the
target kernel (its `select_stack`/`tcp`/`multicast` routes need the
`rt_mutex_waiter` to land in the user-controlled pselect fd words, and this
kernel's PGO inlining places it out of reach), while the x9u-preload-builder
payload *has* been validated on the exact kernel build. So the app's job is
orchestration, not exploitation.

## Why these pieces

| Decision | Reason |
| --- | --- |
| Ship the prebuilt `preload.so` (89 KB) inside the APK (`jniLibs`, legacy packaging) | Regenerating it requires the device's own `boot.img` + `xbl_config.img`; the prebuilt matches the target build exactly. Legacy packaging extracts it as a real file the shell-uid UserService can read. |
| Run via Shizuku UserService (shell uid) | The exploit needs a seccomp-free process; an app process is always seccomp-filtered. Wireless-debugging Shizuku provides shell on-device, no PC. |
| Execute as `LD_PRELOAD=… /system/bin/true` | The payload is an ELF with a `constructor` that runs the whole exploit chain and leaves a root `su` daemon behind; the README of the builder validates exactly this invocation. |
| Log via file + async tailer | Copied from ghostlock-app: binder/pipe backpressure inside the PI race window stalls the route → kernel panic. The file is the transport; binder only displays. |
| Hard gate on exact `uname -r` | Payload offsets are firmware-build-specific; a mismatch can panic. The gate lives in two places (UI badge + UserService preflight) with the constant shared through `EngineContract`. |
| KernelSU via `ksud late-load --kmi android16-6.12` | Same flow as ghostlock-oneplus / ghostlock-app: after temporary root, the manager APK's bundled `libksud.so` late-loads the module; the script then registers the manager APK and re-enforces SELinux. |

## Module map

- `app/src/main/kotlin/com/ghostlock/pad/EngineContract.kt` — single source
  of truth for the kernel gate, paths, phases, exit codes.
- `engine/KernelGate.kt`, `engine/KsuScript.kt` — pure, unit-tested logic
  (gate matching, KMI derivation, install-script generation).
- `data/ManagerScanner.kt` — KernelSU-family manager discovery (from
  ghostlock-app, Apache-2.0).
- `shizuku/EngineUserService.kt` — runs in the Shizuku server process:
  stage → exploit → verify → KernelSU, streaming logs through the AIDL
  callback.
- `shizuku/ShizukuEngine.kt` — app-side binder dance (status, permission,
  UserService binding with connect timeout, retry).
- `ui/MainActivity.kt` — single-screen Compose UI.

## Run pipeline (shell side)

1. **Preflight** — uid must be `SHELL_UID`, `Seccomp:` must be `0`, kernel
   must equal `EngineContract.EXPECTED_KERNEL`. Any mismatch aborts before
   the kernel is touched.
2. **Stage** — copy `nativeLibraryDir/libghostlock_preload.so` to
   `/data/local/tmp/ghostlock/preload.so`, `chmod 0644`.
3. **Exploit** — `ProcessBuilder("/system/bin/true")` with
   `LD_PRELOAD=<staged>`; stdout+stderr redirect to `exploit.log`, tailed
   to the app. The payload: KASLR slide → futex PI race (sched_setattr
   consumer) → cred overwrite (`init_cred`) → SELinux permissive + policy
   reload → writes `/data/local/tmp/su` (root client) + daemon on
   `/data/local/tmp/temp_su.sock`.
4. **Verify** — `su -c id` must report `uid=0`.
5. **KernelSU** — write `ksu_install.sh`, run `su -c sh <script>`: skip if
   `kernelsu` already in `/proc/modules`, else `ksud late-load --kmi <KMI>`,
   wait ≤30 s, register manager APK via `ksud kernel dynamic-manager
   set-apk`, `echo 1 > /sys/fs/selinux/enforce`.

Exit contract: `0` root live (summary says whether KSU loaded), `1` failure.

## Testing

- JVM unit tests cover the gate, KMI derivation and script shape.
- The CI workflow additionally sha256-checks the bundled payload against the
  digest recorded when it was copied from `build/preload/preload.so`
  (`32ac2f03…`) so silent payload swaps fail the build.
