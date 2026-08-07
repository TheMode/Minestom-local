<script lang="ts">
    import { type Field, type Schema, defaultFor, fetchSchema } from '../../lib/packetSchema.ts';
    import { loadCatalog } from '../packets/PacketSelector.svelte';
    import FieldRow from './FieldRow.svelte';

    type Props = {
        packet?: string;
        components?: Field[];
        fields: Record<string, unknown>;
        onChange: (fields: Record<string, unknown>) => void;
    };

    let { packet, components, fields = {}, onChange }: Props = $props();

    let schema = $state<Schema | null>(null);
    let loading = $state(false);
    let err = $state<string | null>(null);
    let lastFetched = '';
    let fetchToken = 0;
    let catalog = $state<{ simple: string }[] | null>(null);

    $effect(() => { loadCatalog(true).then(c => catalog = c).catch(() => catalog = []); });
    const catalogNames = $derived(catalog ? new Set(catalog.map(p => p.simple)) : null);

    $effect(() => {
        if (components !== undefined) { schema = null; return; }
        const name = (packet || '').trim();
        if (!name) { schema = null; err = null; lastFetched = ''; return; }
        if (!catalogNames) return; // catalog still loading
        if (!catalogNames.has(name)) { schema = null; err = null; lastFetched = ''; return; }
        if (name === lastFetched) return;
        lastFetched = name;
        const id = ++fetchToken;
        loading = true; err = null;
        fetchSchema(name)
            .then(s => {
                if (id !== fetchToken) return;
                schema = s; loading = false; seedFields(s);
            })
            .catch(e => {
                if (id !== fetchToken) return;
                schema = null; err = (e as Error).message; loading = false;
                lastFetched = ''; // allow user-driven retry by re-selecting the same packet
            });
    });

    /// Only fire onChange when the schema actually contributes a new/changed key —
    /// otherwise switching between packets the user has already populated would dirty the
    /// routine and trigger a parent re-render storm.
    function seedFields(s: Schema | null) {
        if (!s?.analyzable || !s.components) return;
        const next: Record<string, unknown> = {};
        let changed = Object.keys(fields).length !== s.components.length;
        for (const c of s.components) {
            if (c.name in fields) next[c.name] = fields[c.name];
            else { next[c.name] = defaultFor(c); changed = true; }
        }
        if (changed) onChange(next);
    }

    const list = $derived(components ?? schema?.components ?? null);
    const unknownPacket = $derived(components === undefined && !!packet && !!catalogNames && !catalogNames.has(packet.trim()));

    function setField(name: string, value: unknown) {
        onChange({ ...fields, [name]: value });
    }
</script>

{#if components === undefined && !packet}
    <div class="editor-hint">Select a packet to edit its fields.</div>
{:else if loading}
    <div class="editor-hint">Loading packet schema…</div>
{:else if err}
    <div class="editor-hint pkt-fields__err">Failed to describe <code>{packet}</code>: {err}</div>
{:else if unknownPacket}
    <div class="editor-hint pkt-fields__err">
        <code>{packet}</code> is not in the analyzable packet catalog. Pick a different packet, or fix the name.
    </div>
{:else if components === undefined && schema && !schema.analyzable}
    <div class="editor-hint pkt-fields__err">
        <code>{packet}</code> is not analyzable. It contains components this editor can't break down.
    </div>
{:else if list && list.length === 0}
    <div class="editor-hint">No fields — this packet has no components.</div>
{:else if list}
    <div class="pkt-fields">
        {#each list as f (f.name)}
            <FieldRow
                field={f}
                value={fields[f.name]}
                onChange={(v) => setField(f.name, v)}
            />
        {/each}
    </div>
{/if}
