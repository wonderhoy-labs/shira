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
| `android/app/src/main/kotlin/com/wonderhoylabs/shira/tunnel/` | TCP, UDP and DNS, with nothing Android-specific |
| `android/app/src/main/kotlin/com/wonderhoylabs/shira/vpn/` | The service and the thread that moves everything |
| `web/src/link/`, `web/src/wisp/` | The socket's messages, and the relay to the Wisp server |
| `web/src/adb/` | Reaching the phone |
| `web/src/shira/controller.ts` | Everything the page does, step by step |
| `web/plugins/` | The Vite plugin: the APK, the notices, and the development Wisp and adb servers |

## Build, test and format

```bash
pnpm install && pnpm unify
cd android && ./gradlew spotlessApply :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
cd web && pnpm test && pnpm build
```

The site's build carries the release APK, so build the app first. `pnpm dev` in `web/` serves the debug APK, a Wisp
server and a bridge to `adb`, so the real page runs against an emulator.

## Things to know

- **DNS goes through a resolver on the phone** that proxies to 1.1.1.1 over Wisp, not fake IPs. The page has to
  support Wisp v2 and the password extension. Both were the maintainer's choices.
- **wisp-js comes from the fork** `wonderhoy-labs/wisp-js`, pinned by commit in `web/package.json`. Fix its bugs
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
