<script lang="ts">
    import { provFor } from '../../lib/profile.ts';
    import ProvBadge from '../overlay/ProvBadge.svelte';

    interface Props {
        p: any;
        field: string;
        value: unknown;
        suffix?: string | null;
        variant?: string;
    }
    let { p, field, value, suffix = null, variant = 'tight' }: Props = $props();
</script>

{#if !provFor(p, field)}
    <span class="prov prov--static">
        <span class="prov__val">{value}{#if suffix}<span class="dim small ml-xs">{suffix}</span>{/if}</span>
        <span class="prov__src"><span class="dim">no source yet</span></span>
    </span>
{:else}
    <ProvBadge {value} source={provFor(p, field)} {field} {suffix} {variant} />
{/if}
