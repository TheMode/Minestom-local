<script lang="ts">
    import Panel from '../ui/Panel.svelte';
    import ProvValue from './ProvValue.svelte';

    let { p }: { p: any } = $props();
</script>

<Panel title="Abilities">
    <dl class="kv-list">
        <dt>Flying</dt>       <dd><ProvValue {p} field="flying"       value={String(!!p.flying)} /></dd>
        <dt>Invulnerable</dt> <dd><ProvValue {p} field="invulnerable" value={String(!!p.invulnerable)} /></dd>
        <dt>Allow flying</dt> <dd><ProvValue {p} field="allowFlying"  value={String(!!p.allowFlying)} /></dd>
        <dt>Fly speed</dt>    <dd><ProvValue {p} field="flySpeed"     value={((p.flySpeed ?? 0) * 1).toFixed(3)} /></dd>
        <dt>Walk speed</dt>   <dd><ProvValue {p} field="walkSpeed"    value={((p.walkSpeed ?? 0) * 1).toFixed(3)} /></dd>
    </dl>
</Panel>
