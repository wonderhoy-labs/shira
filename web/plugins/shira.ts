import { createReadStream, existsSync, readFileSync, statSync } from "node:fs";
import type { IncomingMessage, Server } from "node:http";
import type { Duplex } from "node:stream";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";
import { createAdbBridge } from "./dev-adb.ts";
import { createWispServer } from "./dev-wisp.ts";
import { thirdPartyNotices } from "./notices.ts";

const repo = (path: string) => fileURLToPath(new URL(`../../${path}`, import.meta.url));

const DEBUG_APK = "android/app/build/outputs/apk/debug/app-debug.apk";
const RELEASE_APK = "android/app/build/outputs/apk/release/app-release.apk";
const WISP_PATH = "/wisp/";
const ADB_PATH = "/__shira/adb";

/**
 * WebSockets aren't covered by the same-origin policy, so any site you visit could otherwise reach these
 * two and use them to browse your local network or drive adb. Only this page may use them.
 */
function isFromThisPage(request: IncomingMessage): boolean {
	const { origin, host } = request.headers;
	if (!origin || !host) return false;
	try {
		const url = new URL(origin);
		const local = url.hostname === "localhost" || url.hostname === "127.0.0.1" || url.hostname === "[::1]";
		return local && url.host === host;
	} catch {
		return false;
	}
}

function serveWisp(httpServer: Server | null, withAdb: boolean) {
	const wisp = createWispServer();
	const adb = withAdb ? createAdbBridge() : null;
	httpServer?.on("upgrade", (request: IncomingMessage, socket: Duplex, head: Buffer) => {
		const path = new URL(request.url ?? "/", "http://localhost").pathname;
		if (path !== WISP_PATH && (path !== ADB_PATH || !adb)) return;
		if (!isFromThisPage(request)) {
			socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
			socket.destroy();
			return;
		}
		if (path === WISP_PATH) wisp.handleUpgrade(request, socket, head);
		else adb?.handleUpgrade(request, socket, head);
	});
}

/**
 * Puts the APK next to the site, and tells the page what version it is.
 *
 * During development this also runs what production gets from elsewhere: a Wisp server for the page to
 * use, and a way to reach an emulator, which has no USB for the page to find it through.
 */
export function shira(): Plugin {
	const version = readFileSync(repo("VERSION"), "utf8").trim();
	const [major = 0, minor = 0, patch = 0] = version.split(".").map(Number);

	return {
		name: "shira",

		config: () => ({
			define: {
				__SHIRA_VERSION__: JSON.stringify(version),
				__SHIRA_VERSION_CODE__: major * 10_000 + minor * 100 + patch,
			},
		}),

		configureServer(server) {
			const httpServer = server.httpServer as Server | null;
			serveWisp(httpServer, true);

			server.middlewares.use("/shira.apk", (_request, response) => {
				const path = process.env.SHIRA_APK || repo(DEBUG_APK);
				if (!existsSync(path)) {
					response.statusCode = 404;
					response.end("Build the APK first: cd android && ./gradlew :app:assembleDebug");
					return;
				}
				response.setHeader("Content-Type", "application/vnd.android.package-archive");
				response.setHeader("Content-Length", statSync(path).size);
				createReadStream(path).pipe(response);
			});
		},

		// So that a production build can be tried from start to finish, without another server to run.
		configurePreviewServer(server) {
			serveWisp(server.httpServer as Server, false);
		},

		generateBundle() {
			const path = process.env.SHIRA_APK || repo(RELEASE_APK);
			if (!existsSync(path)) {
				this.error(
					`There's no APK at ${path}. The site carries it, so build it first: cd android && ./gradlew :app:assembleRelease`,
				);
			}
			this.emitFile({ type: "asset", fileName: "shira.apk", source: readFileSync(path) });
			this.emitFile({
				type: "asset",
				fileName: "third-party-licenses.txt",
				source: thirdPartyNotices(repo("web")),
			});
		},

		// This page can do anything adb can, so it only runs what came with it, and only talks to Wisp servers.
		transformIndexHtml: {
			order: "post",
			handler(html, context) {
				if (context.server) return html;
				const policy = [
					"default-src 'self'",
					"connect-src 'self' ws: wss:",
					"img-src 'self' data:",
					"style-src 'self' 'unsafe-inline'",
					"font-src 'self' data:",
					"worker-src 'self'",
					"object-src 'none'",
					"base-uri 'none'",
				].join("; ");
				return html.replace(
					'<meta charset="utf-8" />',
					`<meta charset="utf-8" />\n    <meta http-equiv="Content-Security-Policy" content="${policy}" />`,
				);
			},
		},
	};
}
