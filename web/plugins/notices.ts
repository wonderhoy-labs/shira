import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

interface Manifest {
  name: string;
  version: string;
  license?: string;
  homepage?: string;
  dependencies?: Record<string, string>;
}

function readManifest(directory: string): Manifest {
  return JSON.parse(readFileSync(join(directory, 'package.json'), 'utf8')) as Manifest;
}

/** Everything the page's code depends on, directly or not, with its license text. */
export function thirdPartyNotices(webDirectory: string): string {
  const modules = join(webDirectory, 'node_modules');
  const seen = new Map<string, Manifest>();
  const pending = Object.keys(readManifest(webDirectory).dependencies ?? {});
  while (pending.length > 0) {
    const name = pending.pop() as string;
    if (seen.has(name) || !existsSync(join(modules, name, 'package.json'))) continue;
    const manifest = readManifest(join(modules, name));
    seen.set(name, manifest);
    pending.push(...Object.keys(manifest.dependencies ?? {}));
  }

  const sections = [...seen.values()]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((manifest) => {
      const directory = join(modules, manifest.name);
      const licenseFile = readdirSync(directory).find((file) => /^(licen[cs]e|copying)/i.test(file));
      const text = licenseFile ? readFileSync(join(directory, licenseFile), 'utf8').trim() : '(No license file in the package.)';
      return `${manifest.name} ${manifest.version} (${manifest.license ?? 'unknown'})\n${manifest.homepage ?? ''}\n\n${text}`;
    });
  return `${sections.join(`\n\n${'-'.repeat(72)}\n\n`)}\n`;
}
