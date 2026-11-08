// Shira as a library: a phone's network through a web page, over adb and a Wisp server.
//
// Install the app this package carries ("@wonderhoy-labs/shira/shira.apk") with a Phone around a Tango Adb, then
// run a ShiraLink to a Wisp server. Every connection the phone makes is written to a LogStore.

/// <reference path="./wisp-js.d.ts" />

export { ACTIVATE_SCREEN, PACKAGE, SCREEN, SERVICE, SOCKET } from "./constants";
export { PROTOCOL_VERSION } from "./link/frames";
export { ProtocolMismatchError } from "./link/relay";
export {
	describeApp,
	describeClose,
	describeQueryType,
	formatBytes,
	formatDuration,
	formatRate,
	formatTime,
} from "./log/format";
export { type LogRow, LogStore, type LogTotals, type RowKind, type RowState } from "./log/store";
export { type AppInfo, InstallError, Phone, type PhoneKind } from "./phone";
export {
	type ActivateMode,
	type LinkEvents,
	type LinkOptions,
	type LinkState,
	type ServerDetails,
	ShiraLink,
	type StopReason,
} from "./shira-link";
export {
	connectWisp,
	normalizeServerUrl,
	type ServerInfo,
	type WispConnection,
	type WispCredentials,
	WispError,
	type WispFailure,
} from "./wisp/connection";
