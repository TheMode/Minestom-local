<script lang="ts">
    import { humanBytes } from '../../lib/util.ts';
    import Panel from '../ui/Panel.svelte';
    import ProvValue from './ProvValue.svelte';

    let { p }: { p: any } = $props();
</script>

<Panel title="Position">
    {#snippet meta()}<ProvValue {p} field="dimension" value={(p.dimension || '—').replace('minecraft:', '')} />{/snippet}
    <dl class="kv-list">
        <dt>X</dt>          <dd><ProvValue {p} field="posX" value={(p.posX ?? 0).toFixed(2)} /></dd>
        <dt>Y</dt>          <dd><ProvValue {p} field="posY" value={(p.posY ?? 0).toFixed(2)} /></dd>
        <dt>Z</dt>          <dd><ProvValue {p} field="posZ" value={(p.posZ ?? 0).toFixed(2)} /></dd>
        <dt>Yaw</dt>        <dd><ProvValue {p} field="yaw" value={(p.yaw ?? 0).toFixed(1) + '°'} /></dd>
        <dt>Pitch</dt>      <dd><ProvValue {p} field="pitch" value={(p.pitch ?? 0).toFixed(1) + '°'} /></dd>
        <dt>On ground</dt>  <dd><ProvValue {p} field="onGround" value={String(!!p.onGround)} /></dd>
        <dt>Bytes in</dt>   <dd>{humanBytes(p.traffic.bytesIn)}</dd>
        <dt>Bytes out</dt>  <dd>{humanBytes(p.traffic.bytesOut)}</dd>
    </dl>
</Panel>
