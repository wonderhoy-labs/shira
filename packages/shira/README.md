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

`adb` is a connected `Adb` from [Tango](https://github.com/yume-chan/ya-webadb). The rest is in
[Shira's README](https://github.com/wonderhoy-labs/shira#use-it-in-your-app). Apache License 2.0.
