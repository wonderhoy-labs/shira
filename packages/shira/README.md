# @wonderhoy-labs/shira

A phone's network through a web page: Shira's app on the phone is a VPN that hands every connection to the page over
`adb`, and the page relays them through a Wisp server. Every connection is written to a log. The app is
`@wonderhoy-labs/shira/shira.apk`.

```ts
import { LogStore, normalizeServerUrl, Phone, ShiraLink } from "@wonderhoy-labs/shira";

const phone = new Phone(adb, "My phone", "usb");
const server = normalizeServerUrl("wss://wisp.example/");
if ("url" in server) {
	const link = new ShiraLink({
		phone,
		serverUrl: server.url,
		credentials: null,
		log: new LogStore(),
		events: { onState: (state) => console.log(state) },
	});
	link.start("auto");
}
```

`adb` is a connected `Adb` from [Tango](https://github.com/yume-chan/ya-webadb). The library runs on wisp-js from
Wonderhoy Labs' fork, which pnpm won't fetch from git for a dependency's dependency, so add it yourself, pinned as in
[Shira's own package](https://github.com/wonderhoy-labs/shira/blob/main/packages/shira/package.json). The rest is in
[Shira's README](https://github.com/wonderhoy-labs/shira#use-it-in-your-app). Apache License 2.0.
