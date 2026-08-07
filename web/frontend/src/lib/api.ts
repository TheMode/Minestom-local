import type { JsonValue, PacketTopicMessage } from './types.ts';

// HTTP + WebSocket bridge — singleton; lives outside of Svelte.

const params = new URLSearchParams(location.search);
const token = params.get('token') || sessionStorage.getItem('mw-token') || '';
if (params.get('token')) sessionStorage.setItem('mw-token', token);

/// Replay scope id — identifies "which uploaded SQLite this browser tab is viewing". Stored in
/// sessionStorage so a page reload re-attaches to the same scope, but not localStorage so a
/// fresh tab starts blank (each tab can hold its own replay).
///
/// The reactive view of this value lives in `state/mode.svelte.ts`; this module-level cache
/// is just what `headers()` and the bus URL read at request/connect time.
const SCOPE_STORAGE_KEY = 'mw-scope';
let scopeId: string | null = sessionStorage.getItem(SCOPE_STORAGE_KEY);

export function getScope(): string | null { return scopeId; }

/// Update the active scope. Triggers a bus reconnect so the new `?replay=` param takes effect.
/// Callers in `state/mode.svelte.ts` also mirror this into their reactive `$state` for UI.
export function setScope(id: string | null): void {
    if (id === scopeId) return;
    scopeId = id;
    if (id) sessionStorage.setItem(SCOPE_STORAGE_KEY, id);
    else sessionStorage.removeItem(SCOPE_STORAGE_KEY);
    bus.reconnect();
}

type ApiBody = BodyInit | JsonValue | Record<string, unknown>;
type ApiInit = Omit<RequestInit, 'body'> & { body?: ApiBody };

const headers = (): Record<string, string> => {
    const h: Record<string, string> = {};
    if (token) h['X-Auth-Token'] = token;
    if (scopeId) h['X-Replay-Id'] = scopeId;
    return h;
};

export async function api<T = unknown>(path: string, opts: ApiInit = {}): Promise<T> {
    const { body, ...rest } = opts;
    const init: RequestInit = { ...rest };
    const requestHeaders = new Headers(opts.headers);
    for (const [key, value] of Object.entries(headers())) requestHeaders.set(key, value);
    init.headers = requestHeaders;
    init.body = body as BodyInit | null | undefined;
    if (body && typeof body === 'object' && !(body instanceof FormData) && !(body instanceof URLSearchParams) && !(body instanceof Blob) && !(body instanceof ArrayBuffer)) {
        requestHeaders.set('Content-Type', 'application/json');
        init.body = JSON.stringify(body);
    }
    const r = await fetch('/api' + path, init);
    if (!r.ok) {
        const text = await r.text().catch(() => '');
        const ct = r.headers.get('content-type') || '';
        let msg = r.statusText || `HTTP ${r.status}`;
        if (text && ct.includes('application/json')) {
            try { msg = JSON.parse(text).error ?? msg; } catch {}
        }
        const err = new Error(msg) as Error & { status?: number };
        err.status = r.status;
        throw err;
    }
    const ct = r.headers.get('content-type') || '';
    if (ct.includes('application/json')) return r.json() as Promise<T>;
    return r.text() as Promise<T>;
}

/// Reconnecting WebSocket multiplex with topic subscriptions.
type TopicHandler<T = PacketTopicMessage> = (message: T) => void;

class TopicEvent<T = PacketTopicMessage> extends CustomEvent<T> {}

class Bus extends EventTarget {
    ws: WebSocket | null = null;
    subs = new Map<string, number>(); // topic → refcount
    connected = false;
    reconnectMs = 500;
    #started = false;

    connect(): void {
        this.#started = true;
        this.#open();
    }

    #open(): void {
        const proto = location.protocol === 'https:' ? 'wss' : 'ws';
        const qs = new URLSearchParams();
        if (token) qs.set('token', token);
        if (scopeId) qs.set('replay', scopeId);
        const q = qs.toString();
        const url = `${proto}://${location.host}/ws${q ? '?' + q : ''}`;
        this.ws = new WebSocket(url);
        this.ws.addEventListener('open', () => {
            this.connected = true;
            this.reconnectMs = 500;
            this.dispatchEvent(new Event('open'));
            if (this.subs.size) this.send({ subscribe: [...this.subs.keys()] });
        });
        this.ws.addEventListener('close', () => {
            this.connected = false;
            this.dispatchEvent(new Event('close'));
            setTimeout(() => this.#open(), this.reconnectMs = Math.min(this.reconnectMs * 1.8, 10_000));
        });
        this.ws.addEventListener('message', (e: MessageEvent<string>) => {
            let outer: PacketTopicMessage & { batch?: PacketTopicMessage[] };
            try { outer = JSON.parse(e.data); } catch { return; }
            const msgs = Array.isArray(outer.batch) ? outer.batch : [outer];
            for (const msg of msgs) {
                if (msg.topic) this.dispatchEvent(new CustomEvent('topic:' + msg.topic, { detail: msg }));
                this.dispatchEvent(new CustomEvent('message', { detail: msg }));
            }
        });
    }

    reconnect(): void {
        // No-op before boot — the eventual `connect()` picks up the current scopeId.
        if (!this.#started) return;
        if (this.ws) {
            try { this.ws.close(); } catch {}
            this.ws = null;
        }
        this.#open();
    }

    send(obj: JsonValue | Record<string, unknown>): void {
        if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj));
    }

    /// Subscribe to a topic. Returns an unsubscribe function. Refcounted so multiple components
    /// on the same topic only result in one server subscription.
    subscribe<T extends PacketTopicMessage = PacketTopicMessage>(topic: string, handler: TopicHandler<T>): () => void {
        const count = this.subs.get(topic) || 0;
        if (count === 0 && this.connected) this.send({ subscribe: [topic] });
        this.subs.set(topic, count + 1);
        const wrapped = (e: Event) => handler((e as TopicEvent<T>).detail);
        this.addEventListener('topic:' + topic, wrapped);
        return () => {
            this.removeEventListener('topic:' + topic, wrapped);
            const n = (this.subs.get(topic) || 1) - 1;
            if (n <= 0) {
                this.subs.delete(topic);
                if (this.connected) this.send({ unsubscribe: [topic] });
            } else {
                this.subs.set(topic, n);
            }
        };
    }
}

export const bus = new Bus();
