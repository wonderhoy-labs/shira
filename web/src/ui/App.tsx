import { useMemo, useSyncExternalStore } from "react";
import { Controller } from "../shira/controller";
import { AppBar, Footer } from "./Brand";
import { Monitor } from "./Monitor";
import { Setup } from "./Setup";

export function App() {
	const controller = useMemo(() => new Controller(), []);
	const state = useSyncExternalStore(controller.subscribe, controller.getState);
	return (
		<div className="app">
			<AppBar logo="./logo.webp" />
			{state.step === "live" ? (
				<Monitor controller={controller} state={state} />
			) : (
				<>
					<Setup controller={controller} state={state} />
					<Footer />
				</>
			)}
		</div>
	);
}
