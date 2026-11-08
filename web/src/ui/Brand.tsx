// Shared by every Wonderhoy Labs web app. Change it in wonderhoy-labs/template first.
import "mdui/components/button.js";
import "@mdui/icons/open-in-new.js";
import type { ReactNode } from "react";
import { brand } from "../brand";

/**
 * The bar along the top of the page: the logo and name, anything the page adds, and a link to the source.
 * The logo is the project's own art, from the maintainer. Without one the bar shows the name alone.
 */
export function AppBar({ logo, children }: { logo?: string; children?: ReactNode }) {
	return (
		<header className="app-bar">
			{logo && <img className="app-bar-logo" src={logo} alt="" />}
			<span className="app-bar-title">{brand.name}</span>
			<span className="app-bar-space" />
			{children}
			<mdui-button variant="text" href={brand.repository} target="_blank" rel="noreferrer">
				GitHub
				<mdui-icon-open-in-new slot="end-icon" />
			</mdui-button>
		</header>
	);
}

/** The line at the bottom of the page: who made it, and who it's named for. */
export function Footer() {
	return (
		<footer className="app-footer">
			<span>
				<span className="app-footer-mark" aria-hidden="true" />A{" "}
				<a href="https://github.com/wonderhoy-labs" target="_blank" rel="noreferrer">
					Wonderhoy Labs
				</a>{" "}
				project, named for {brand.character} of {brand.unit}
				{/* 25-ji, Nightcord de. already ends in one. */}
				{brand.unit.endsWith(".") ? "" : "."}
			</span>
		</footer>
	);
}
