import { type ReactNode, useEffect, useRef, useState } from "react";
import { isUsbAvailable } from "../adb/usb";
import { RELEASES_URL } from "../shira/constants";
import type { Controller, ShiraState, Step } from "../shira/controller";

const STEPS: Step[] = ["phone", "app", "server", "activate"];

interface Props {
	controller: Controller;
	state: ShiraState;
}

export function Setup({ controller, state }: Props) {
	const current = STEPS.indexOf(state.step);
	return (
		<main className="setup">
			<section className="hero">
				<h1>Get your phone online through this browser</h1>
				<p>
					Plug your phone into this computer and it gets a network connection through a Wisp server, even with
					Wi-Fi and mobile data turned off. There's nothing to install here, just one app on the phone.
				</p>
			</section>
			<ol className="steps">
				<StepCard
					number={1}
					title="Connect your phone"
					done={current > 0}
					active={current === 0}
					summary={state.phone}
				>
					<PhoneStep controller={controller} state={state} />
				</StepCard>
				<StepCard
					number={2}
					title="Install the app"
					done={current > 1}
					active={current === 1}
					summary={state.app?.versionName ? `Shira ${state.app.versionName}` : null}
				>
					<AppStep controller={controller} state={state} />
				</StepCard>
				<StepCard
					number={3}
					title="Choose a Wisp server"
					done={current > 2}
					active={current === 2}
					summary={state.serverLabel}
				>
					<ServerStep controller={controller} state={state} />
				</StepCard>
				<StepCard number={4} title="Activate" done={false} active={current === 3} summary={null}>
					<ActivateStep controller={controller} state={state} />
				</StepCard>
			</ol>
			{state.error ? (
				<div className="error-banner" role="alert">
					<mdui-icon-error-outline />
					<span>{state.error}</span>
					<mdui-button-icon onClick={() => controller.dismissError()} aria-label="Dismiss">
						<mdui-icon-close />
					</mdui-button-icon>
				</div>
			) : null}
			<footer className="setup-footer">
				Only use this page from an address you trust. It can do anything adb can on the phone you connect.
			</footer>
		</main>
	);
}

interface StepCardProps {
	number: number;
	title: string;
	done: boolean;
	active: boolean;
	summary: string | null;
	children: ReactNode;
}

function StepCard({ number, title, done, active, summary, children }: StepCardProps) {
	return (
		<li className={`step ${active ? "step-active" : ""} ${done ? "step-done" : ""}`}>
			<div className="step-head">
				<span className="step-badge" aria-hidden="true">
					{done ? <mdui-icon-check /> : number}
				</span>
				<span className="step-title">{title}</span>
				{done && summary ? <span className="step-summary">{summary}</span> : null}
			</div>
			{active ? <div className="step-body">{children}</div> : null}
		</li>
	);
}

function PhoneStep({ controller, state }: Props) {
	return (
		<>
			<p>
				Turn on <strong>USB debugging</strong> in Developer options on your phone, and plug it in with a cable
				that carries data. Your computer doesn't need adb or anything else.
			</p>
			{state.busy ? (
				<Waiting text={state.busy} />
			) : isUsbAvailable ? (
				<div className="actions">
					<mdui-button variant="filled" onClick={() => void controller.connectUsb()}>
						<mdui-icon-usb slot="icon" />
						Connect phone
					</mdui-button>
				</div>
			) : (
				<p className="note">
					This browser can't use USB. Chrome, Edge and other Chromium browsers can, on a computer. Safari and
					Firefox don't have WebUSB.
				</p>
			)}
			{import.meta.env.DEV && !state.busy ? <DevDevices controller={controller} /> : null}
		</>
	);
}

/** Emulators have no USB, so in development the page can also find devices through the adb server. */
function DevDevices({ controller }: { controller: Controller }) {
	const [devices, setDevices] = useState<import("../adb/dev-server").DevDevice[]>([]);
	useEffect(() => {
		let stopped = false;
		const load = async () => {
			try {
				const { listDevDevices } = await import("../adb/dev-server");
				const found = await listDevDevices();
				if (!stopped) setDevices(found);
			} catch (error) {
				console.info("no adb server to list devices from", error);
			}
		};
		void load();
		const timer = setInterval(() => void load(), 3000);
		return () => {
			stopped = true;
			clearInterval(timer);
		};
	}, []);
	if (devices.length === 0) return null;
	return (
		<div className="dev-devices">
			<span className="note">From the adb server (development only):</span>
			<div className="actions">
				{devices.map((device) => (
					<mdui-button
						key={device.serial}
						variant="tonal"
						onClick={() => void controller.connectThroughAdbServer(device)}
					>
						{device.label}
					</mdui-button>
				))}
			</div>
		</div>
	);
}

function AppStep({ controller, state }: Props) {
	const outdated = state.app?.installed && !state.appCurrent;
	const verb = outdated ? "Update" : "Install";
	if (state.installing) {
		return (
			<>
				<p>
					{state.installProgress < 1
						? `Sending Shira ${__SHIRA_VERSION__} to your phone.`
						: "Your phone is installing it."}
				</p>
				<mdui-linear-progress value={state.installProgress < 1 ? state.installProgress : undefined} max={1} />
			</>
		);
	}
	if (state.installManually) {
		return (
			<>
				<p>
					Install the APK on your phone however you like. This page carries on by itself when it sees the app.
				</p>
				<div className="actions">
					<mdui-button variant="tonal" href="shira.apk" download="shira.apk">
						<mdui-icon-download slot="icon" />
						Download APK
					</mdui-button>
					<mdui-button variant="text" href={RELEASES_URL} target="_blank" rel="noreferrer">
						All releases
					</mdui-button>
					<mdui-button variant="text" onClick={() => controller.cancelManualInstall()}>
						Back
					</mdui-button>
				</div>
				<Waiting text="Waiting for the app to appear on your phone." />
			</>
		);
	}
	return (
		<>
			<p>
				{outdated
					? `Your phone has Shira ${state.app?.versionName}, and this page needs ${__SHIRA_VERSION__}.`
					: "The app is what gives your phone its network connection."}
			</p>
			<div className="actions">
				{state.offerUninstall ? (
					<mdui-button variant="filled" onClick={() => void controller.uninstallAndInstall()}>
						Remove and reinstall
					</mdui-button>
				) : (
					<mdui-button variant="filled" onClick={() => void controller.installApp()}>
						<mdui-icon-install-mobile slot="icon" />
						{verb} it for me
					</mdui-button>
				)}
				<mdui-button variant="outlined" onClick={() => void controller.installManually()}>
					I'll {verb.toLowerCase()} it myself
				</mdui-button>
			</div>
		</>
	);
}

function ServerStep({ controller, state }: Props) {
	const passwordRef = useRef<HTMLInputElement & { value: string }>(null);
	const submit = () => void controller.chooseServer(passwordRef.current?.value ?? "");
	return (
		<>
			<p>All your phone's traffic goes through this server, so use one you trust.</p>
			<form
				className="server-form"
				onSubmit={(event) => {
					event.preventDefault();
					submit();
				}}
			>
				<mdui-text-field
					label="Wisp server"
					placeholder="wss://example.com/wisp/"
					value={state.serverInput}
					variant="outlined"
					autocomplete="url"
					onInput={(event: React.FormEvent<HTMLElement>) =>
						controller.setServerInput((event.target as HTMLInputElement).value)
					}
				/>
				{state.loginNeeded ? (
					<div className="login-fields">
						<mdui-text-field
							label="Username"
							value={state.username}
							variant="outlined"
							autocomplete="username"
							onInput={(event: React.FormEvent<HTMLElement>) =>
								controller.setUsername((event.target as HTMLInputElement).value)
							}
						/>
						<mdui-text-field
							ref={passwordRef as never}
							label="Password"
							type="password"
							toggle-password
							variant="outlined"
							autocomplete="current-password"
						/>
					</div>
				) : null}
				<div className="actions">
					<mdui-button
						variant="filled"
						type="submit"
						loading={state.checkingServer}
						disabled={state.checkingServer}
					>
						Continue
					</mdui-button>
					{state.loginNeeded ? null : (
						<mdui-button variant="text" onClick={() => controller.showLogin()}>
							<mdui-icon-vpn-key slot="icon" />
							Add a login
						</mdui-button>
					)}
				</div>
			</form>
		</>
	);
}

function ActivateStep({ controller, state }: Props) {
	const waiting = state.mode !== null;
	return (
		<>
			{state.serverMessage ? <p className="motd">{state.serverMessage}</p> : null}
			{waiting ? (
				<>
					<Waiting
						text={
							state.busy ??
							(state.link === "connecting"
								? `Connecting to ${state.serverLabel}.`
								: state.mode === "auto"
									? "Starting Shira on your phone."
									: "Open Shira on your phone, and tap Activate.")
						}
					/>
					<div className="actions">
						<mdui-button variant="text" onClick={() => controller.cancelActivate()}>
							Cancel
						</mdui-button>
					</div>
				</>
			) : (
				<>
					<p>
						Android asks for your OK the first time an app acts as a VPN. This page can give it for you
						through USB debugging, or you can do it on the phone.
					</p>
					<div className="actions">
						<mdui-button variant="filled" onClick={() => controller.activate("auto")}>
							<mdui-icon-power-settings-new slot="icon" />
							Activate it for me
						</mdui-button>
						<mdui-button variant="outlined" onClick={() => controller.activate("manual")}>
							I'll activate it on my phone
						</mdui-button>
						<mdui-button variant="text" onClick={() => controller.changeServer()}>
							Change server
						</mdui-button>
					</div>
				</>
			)}
		</>
	);
}

function Waiting({ text }: { text: string }) {
	return (
		<div className="waiting" role="status">
			<mdui-circular-progress />
			<span>{text}</span>
		</div>
	);
}
