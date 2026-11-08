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
