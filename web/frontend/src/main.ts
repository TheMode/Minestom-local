import { mount } from 'svelte';
import { bus } from './lib/api.ts';
import { mode } from './state/mode.svelte.ts';
import { players } from './state/players.svelte.ts';
import { throughput } from './state/throughput.svelte.ts';
import App from './App.svelte';

/// Boot:
///   1. Resolve mode + scope from the server.
///   2. In live mode (or replay with an already-attached scope) connect the bus + boot the
///      shared stores immediately.
///   3. In replay mode without a scope, defer — the WS would be rejected by the server. The
///      landing view renders, `mode.uploadReplay()` flips `mode.scope`, and the App component
///      hooks an `$effect` to call `ensureBoot()` once the scope arrives.
async function boot(): Promise<void> {
    await mode.boot();
    if (mode.mode === 'live' || mode.scope) ensureBoot();
}

let started = false;
export function ensureBoot(): void {
    if (started) return;
    started = true;
    bus.connect();
    players.boot();
    throughput.boot();
}

const target = document.getElementById('app');
if (!target) throw new Error('Missing #app mount target');

mount(App, { target });
boot();
