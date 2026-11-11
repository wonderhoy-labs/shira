// Where Shira lives on the phone.

/** The Android app's package. */
export const PACKAGE = "com.wonderhoylabs.shira";
/** The VPN service, which listens on SOCKET while it runs. */
export const SERVICE = `${PACKAGE}/.vpn.ShiraVpnService`;
/** The app's own screen. */
export const SCREEN = `${PACKAGE}/.ui.MainActivity`;
/** Starts the VPN when opened. Only adb is allowed to open it. */
export const ACTIVATE_SCREEN = `${PACKAGE}/.Activate`;
/** The app's local socket, which adb can open. */
export const SOCKET = "localabstract:shira";

/**
 * An app on the phone that speaks Shira's link: Shira's own, or another built on its `vpn` Android library, such as
 * Shiho's. Phone uses one to install, start and reach it.
 */
export interface LinkApp {
	/** The Android package. */
	package: string;
	/** The VPN service, as `<package>/<class>`, which listens on [socket] while it runs. */
	service: string;
	/** The activity alias that turns it on, which only adb may open, as `<package>/.Activate`. */
	activateScreen: string;
	/** The local socket adb opens, like `localabstract:shira`. */
	socket: string;
}

/** Shira's own app. */
export const SHIRA_APP: LinkApp = {
	package: PACKAGE,
	service: SERVICE,
	activateScreen: ACTIVATE_SCREEN,
	socket: SOCKET,
};
