<script module lang="ts">
    function ageClass(ageMs) {
        if (ageMs == null) return '';
        if (ageMs < 1000)  return 'prov--hot';
        if (ageMs > 60000) return 'prov--stale';
        return '';
    }

    export { ageClass };
</script>

<script lang="ts">
    import { getContext } from 'svelte';
    import { PROV_OPEN_KEY, PROV_OPEN_FIELD_KEY } from '../../lib/provenance.ts';
    import { busStatus } from '../../state/bus.svelte.ts';
    import { provTooltip } from '../../state/provTooltip.svelte.ts';

    let { value, source, field, suffix, variant = '', children } = $props();

    const open = getContext(PROV_OPEN_KEY);
    const openField = getContext(PROV_OPEN_FIELD_KEY);

    const now = $derived(busStatus.now);
    const ageMs = $derived(source?.ts ? now - source.ts : null);
    const interactive = $derived(!!field && !!open);
    const isOpen = $derived(interactive && openField?.() === field);
    const cls = $derived([
        'prov',
        variant && `prov--${variant}`,
        ageClass(ageMs),
        isOpen && 'is-open',
        !interactive && 'prov--static',
    ].filter(Boolean).join(' '));

    function onClick(e) {
        if (!interactive) return;
        e.preventDefault();
        provTooltip.hide();
        open?.(field, e.currentTarget);
    }

    // Hover/focus surfaces the source in a floating tooltip instead of expanding the badge in
    // place — the value stays put. Skipped while this badge owns the pinned popover, and while
    // the profile's "hide all traces" toggle is off (it sets `data-traces="off"` on the shell).
    function onEnter(e) {
        if (!interactive || isOpen) return;
        if ((e.currentTarget as HTMLElement).closest('[data-traces="off"]')) return;
        // The host tolerates every absent field, so forward the source as-is rather than
        // re-normalising it here.
        provTooltip.show(e.currentTarget, { field, source: source ?? null });
    }
    function onLeave() { provTooltip.hide(); }
</script>

{#snippet content()}
    <span class="prov__val">
        {#if children}{@render children()}{:else}{value}{/if}
        {#if suffix}<span class="dim small ml-xs">{suffix}</span>{/if}
    </span>
{/snippet}

{#if interactive}
    <button
        type="button"
        class={cls}
        data-prov-field={field || undefined}
        onclick={onClick}
        onpointerenter={onEnter}
        onpointerleave={onLeave}
        onfocus={onEnter}
        onblur={onLeave}
    >
        {@render content()}
    </button>
{:else}
    <span class={cls} data-prov-field={field || undefined}>
        {@render content()}
    </span>
{/if}
