<script lang="ts">
    import { provFor } from '../../lib/profile.ts';
    import Panel from '../ui/Panel.svelte';
    import ProvBadge from '../overlay/ProvBadge.svelte';

    let { p }: { p: any } = $props();
</script>

{#if Object.entries(p.attributes || {}).length === 0}
    <Panel title="Attributes" meta="none"><div class="empty">No attributes reported.</div></Panel>
{:else}
    <Panel title="Attributes" meta={String(Object.entries(p.attributes).length)} flush>
        <table class="list">
            <tbody>
                {#each Object.entries(p.attributes) as [k, v] (k)}
                    {@const fld = 'attributes.' + k}
                    {@const src = provFor(p, fld)}
                    <tr>
                        <td class="dim mono">{k.replace(/^minecraft:/, '')}</td>
                        <td class="text-right">
                            {#if src}
                                <ProvBadge value={Number(v).toFixed(3)} source={src} field={fld} variant="tight" />
                            {:else}
                                <span class="prov prov--static">
                                    <span class="prov__val"><span class="mono num">{Number(v).toFixed(3)}</span></span>
                                    <span class="prov__src"><span class="dim">no source</span></span>
                                </span>
                            {/if}
                        </td>
                    </tr>
                {/each}
            </tbody>
        </table>
    </Panel>
{/if}
