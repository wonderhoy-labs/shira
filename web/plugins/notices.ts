import { existsSync, readdirSync, readFileSync, realpathSync } from "node:fs";
import { dirname, join } from "node:path";

interface Manifest {
	name: string;
	version: string;
	license?: string;
	homepage?: string;
	dependencies?: Record<string, string>;
	optionalDependencies?: Record<string, string>;
}

function readManifest(directory: string): Manifest {
	return JSON.parse(readFileSync(join(directory, "package.json"), "utf8")) as Manifest;
}

/**
 * Where Node would find `name` from `directory`: the nearest `node_modules/<name>` above it. pnpm keeps each
 * package's own dependencies next to it, so this has to start from the package that depends on it.
 */
function resolve(name: string, directory: string): string | undefined {
	for (let at = directory; ; at = dirname(at)) {
		const candidate = join(at, "node_modules", name);
		if (existsSync(join(candidate, "package.json"))) return realpathSync(candidate);
		if (dirname(at) === at) return undefined;
	}
}

/** Everything the page's code depends on, directly or not, with its license text. */
export function thirdPartyNotices(webDirectory: string): string {
	const seen = new Map<string, { manifest: Manifest; directory: string }>();
	const pending = Object.keys(readManifest(webDirectory).dependencies ?? {}).map((name) => ({
		name,
		from: webDirectory,
	}));
	for (let next = pending.pop(); next; next = pending.pop()) {
		const directory = resolve(next.name, next.from);
		// Optional dependencies for other platforms aren't installed, and aren't shipped either.
		if (!directory) continue;
		const manifest = readManifest(directory);
		const key = `${manifest.name}@${manifest.version}`;
		if (seen.has(key)) continue;
		seen.set(key, { manifest, directory });
		const dependencies = { ...manifest.dependencies, ...manifest.optionalDependencies };
		pending.push(...Object.keys(dependencies).map((name) => ({ name, from: directory })));
	}

	const sections = [...seen.values()]
		.sort(
			(a, b) =>
				a.manifest.name.localeCompare(b.manifest.name) || a.manifest.version.localeCompare(b.manifest.version),
		)
		.map(({ manifest, directory }) => {
			// All of them, because an LGPL package has the GPL alongside it.
			const licenseFiles = readdirSync(directory)
				.filter((file) => /^(licen[cs]e|copying)/i.test(file))
				.sort();
			const text =
				licenseFiles.map((file) => readFileSync(join(directory, file), "utf8").trim()).join("\n\n") ||
				"(No license file in the package.)";
			return `${manifest.name} ${manifest.version} (${manifest.license ?? "unknown"})\n${manifest.homepage ?? ""}\n\n${text}`;
		});
	return `${sections.join(`\n\n${"-".repeat(72)}\n\n`)}\n`;
}
