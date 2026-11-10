# AGENTS.md

Read the Wonderhoy Labs unification skill, [.claude/skills/wonderhoy-unification/SKILL.md](.claude/skills/wonderhoy-unification/SKILL.md),
before changing anything. It's the same in every Wonderhoy Labs repository. This file only adds what's particular
to Shira.

## What Shira is

A phone's network through a browser tab. The Android app is a VPN that ends TCP and UDP in Kotlin and speaks
length-prefixed Wisp over `localabstract:shira`. The web page reaches it over WebUSB with Tango, relays it through
wisp-js to a Wisp server, and shows every connection in a log. Named for Shiraishi An of Vivid BAD SQUAD. The site
is at <https://wonderhoy-labs.github.io/shira/>.

## Layout

| Path | What |
| --- | --- |
| `android/tunnel/` | The `shira-tunnel` library: TCP, UDP and DNS, and the socket's frames, with nothing Android-specific |
| `android/app/src/main/kotlin/com/wonderhoylabs/shira/vpn/` | The service and the thread that moves everything |
| `packages/shira/` | The `@wonderhoy-labs/shira` library: messages, Wisp relay, `ShiraLink`, the log, the phone |
| `web/src/adb/` | Reaching the phone over WebUSB or the adb server |
| `web/src/shira/controller.ts` | Everything the page does, step by step |
| `web/plugins/` | The Vite plugin: the APK, the notices, and the development Wisp and adb servers |
| `crates/shira-vm/` | The tool for Android's Linux Terminal: the VM's packets to the app over TCP, and routing rules that block the rest |

## Build, test and format

```bash
pnpm install && pnpm unify
cd android && ./gradlew spotlessApply :tunnel:testDebugUnitTest :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cd packages/shira && pnpm test
cd web && pnpm build
```

The site's build carries the release APK, so build the app first. `pnpm dev` in `web/` serves the debug APK, a Wisp
server and a bridge to `adb`, so the real page runs against an emulator.

## Things to know

- **DNS goes through a resolver on the phone** that proxies to 1.1.1.1 over Wisp, not fake IPs. The page has to
  support Wisp v2 and the password extension. Both were the maintainer's choices.
- **Both libraries are public.** Asahi uses `@wonderhoy-labs/shira` from releases, and other apps may use
  `shira-tunnel`. What `packages/shira/src/index.ts` exports, and the tunnel's public classes, are the API: keep them
  documented, and bump the minor version for a breaking change.
- **wisp-js comes from the fork** `wonderhoy-labs/wisp-js`, pinned by commit in `packages/shira/package.json` and `web/package.json`. Fix its bugs
  there, on the `fixes` branch, and move the pin. Never open issues or pull requests on `MercuryWorkshop/wisp-js`.
  wisp-js still writes an old draft of the password message, and Shira overrides it with the final layout.
- **WebUSB and adb can't share a phone**, and Android Studio restarts adb. WebUSB can't be automated, because
  Chrome's device chooser needs a click.
- **Never put real phone traffic in public screenshots.** The log shows every app and site.
- `ScreenTest` draws every app state into `files/screens`. Run it with `am instrument` on the emulator.
- Never replace the Android signing key. CI signs with the `SHIRA_KEYSTORE_BASE64`, `SHIRA_KEYSTORE_PASSWORD`,
  `SHIRA_KEY_ALIAS` and `SHIRA_KEY_PASSWORD` secrets.

## Exceptions to the guideline

None.
