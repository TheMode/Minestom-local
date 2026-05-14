import * as esbuild from 'esbuild';
import esbuildSvelte from 'esbuild-svelte';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(__dirname, '../src/main/resources/web/app.js');

const watch = process.argv.includes('--watch');
const debug = process.argv.includes('--debug');
const prod = !watch && !debug;

const opts = {
    entryPoints: [path.resolve(__dirname, 'src/main.ts')],
    bundle: true,
    outfile: OUT,
    format: 'esm',
    target: 'es2022',
    sourcemap: prod ? false : 'inline',
    minify: prod,
    conditions: ['svelte', 'browser'],
    plugins: [
        esbuildSvelte({
            compilerOptions: { dev: !prod, css: 'injected' },
        }),
    ],
    logLevel: 'info',
};

if (watch) {
    const ctx = await esbuild.context(opts);
    await ctx.watch();
    console.log('Watching for changes…');
} else {
    await esbuild.build(opts);
    console.log('Built ' + OUT);
}
