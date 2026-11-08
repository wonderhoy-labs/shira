import { defineConfig } from "vitest/config";

// The library's own build: one ES module, with its dependencies left outside, so it works with any bundler. The APK
// sits beside it as a file of its own.
export default defineConfig({
	build: {
		target: "es2022",
		minify: false,
		lib: { entry: "src/index.ts", formats: ["es"], fileName: () => "index.js" },
		rollupOptions: { external: (id) => id.startsWith("@yume-chan/") || id.startsWith("@mercuryworkshop/") },
	},
	test: { environment: "node", include: ["src/**/*.test.ts"] },
});
