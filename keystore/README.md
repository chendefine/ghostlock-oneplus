The release signing key for the sideload-only GhostLock Pad APK.

This keystore is intentionally public: the app is distributed outside any
store, and a stable, reproducible signature lets users upgrade installs
across CI builds without manual uninstalls. Anyone can build and sign the
same APK from this repository.

- keystore: `ghostlock-pad-release.jks`
- alias: `ghostlock-pad`
- store/key password: `ghostlock-pad`

Override with `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASSWORD` environment
variables (or `local.properties`) if you fork and want your own key — in
that case replace this file and never reuse the one committed here.
