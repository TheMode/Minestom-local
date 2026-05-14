<script lang="ts">
    import Panel from '../ui/Panel.svelte';
    import ProvValue from './ProvValue.svelte';

    let { p }: { p: any } = $props();
</script>

<Panel title="Identity">
    <dl class="kv-list">
        <dt>UUID</dt>        <dd><ProvValue {p} field="uuid" value={p.uuid || '—'} /></dd>
        <dt>Locale</dt>      <dd><ProvValue {p} field="locale" value={p.locale || '—'} /></dd>
        <dt>Client</dt>      <dd><ProvValue {p} field="clientBrand" value={p.clientBrand || '—'} /></dd>
        <dt>Server</dt>      <dd><ProvValue {p} field="serverBrand" value={p.serverBrand || '—'} /></dd>
        <dt>Address</dt>     <dd>
            <span class="prov prov--static">
                <span class="prov__val">{p.address || '—'}</span>
                <span class="prov__src"><span class="dim">tcp-accept</span></span>
            </span>
        </dd>
        <dt>Protocol</dt>    <dd><ProvValue {p} field="protocolVersion" value={String(p.protocolVersion || '—')} /></dd>
        <dt>Compression</dt> <dd><ProvValue {p} field="traffic.compressionThreshold" value={String(p.traffic.compressionThreshold)} suffix="bytes" /></dd>
    </dl>
</Panel>
