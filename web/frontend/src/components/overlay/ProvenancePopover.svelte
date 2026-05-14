<script lang="ts">
    import { api } from '../../lib/api.ts';
    import { shortClass, fmtAge } from '../../lib/util.ts';
    import { busStatus } from '../../state/bus.svelte.ts';
    import { anchoredPopover } from '../../lib/floatingPopover.svelte.ts';

    /// `sourceSeq` reflects the parent's latest provenance seq for `field`; a change refetches.
    let { uuid, field, anchor, valueOf, sourceSeq, onClose } = $props<{
        uuid: string;
        field: string;
        anchor: HTMLElement | null;
        valueOf: (field: string) => unknown;
        sourceSeq: number | null;
        onClose: () => void;
    }>();

    let pop = $state(null);
    let history = $state(null);
    let err = $state(null);
    let lastSeq: number | null = null;

    async function refetch() {
        try {
            const data = await api(`/players/${uuid}/provenance?field=${encodeURIComponent(field)}`);
            history = data[field] || [];
            err = null;
        } catch (e) { err = String(e.message || e); }
    }

    $effect(() => {
        uuid; field;
        lastSeq = null;
        refetch();
    });

    $effect(() => {
        const seq = sourceSeq;
        if (seq == null || seq === lastSeq) return;
        lastSeq = seq;
        refetch();
    });

    const { pos } = anchoredPopover(
        () => anchor,
        () => pop,
        (r, p) => ({
            left: Math.max(8, Math.min(window.innerWidth - p.offsetWidth - 8, r.left + window.scrollX)),
            top:  Math.max(8, r.top + window.scrollY - p.offsetHeight - 6),
        }),
        () => onClose(),
        {
            escape: true,
            closeEvent: 'pointerdown',
            deferOutsideClick: true,
            repositionWhen: () => history,
            // Absolute + document-space placement, so the pop scrolls with its anchor — don't
            // close on scroll (and don't let scrolling the inner history list dismiss it).
            closeOnScroll: false,
        },
    );

    const entries = $derived(history ? history.slice().reverse() : null);
    const now = $derived(busStatus.now);
</script>

<div class="prov-pop" bind:this={pop} style:left="{pos.left}px" style:top="{pos.top}px" role="dialog" aria-label="Provenance for {field}">
    <header class="prov-pop__head">
        <div class="prov-pop__title">
            <span class="prov-pop__eyebrow">Provenance</span>
            <span class="prov-pop__field" title={field}>{field}</span>
        </div>
        <button type="button" class="prov-pop__close" aria-label="Close" onclick={onClose}>×</button>
    </header>

    <div class="prov-pop__current">
        <span class="prov-pop__current-label">Current</span>
        <span class="prov-pop__current-val">{String(valueOf?.(field) ?? '—')}</span>
    </div>

    <div class="prov-pop__history">
        {#if err}
            <div class="prov-pop__note prov-pop__note--err">{err}</div>
        {:else if entries == null}
            <div class="prov-pop__note">Loading…</div>
        {:else if entries.length === 0}
            <div class="prov-pop__note">No mutation history yet.</div>
        {:else}
            <ol class="prov-pop__timeline">
                {#each entries as e, i (i)}
                    {@const src = e.source || {}}
                    {@const ageMs = src.ts ? now - src.ts : null}
                    {@const href = src.seq != null
                        ? `/p/${encodeURIComponent(uuid)}/packets?seq=${src.seq}`
                        : `/p/${encodeURIComponent(uuid)}/packets`}
                    <li class="prov-pop__step" class:prov-pop__step--latest={i === 0}>
                        <a class="prov-pop__entry" {href} onclick={() => onClose?.()}>
                            <span class="prov-pop__node" aria-hidden="true"></span>
                            <span class="prov-pop__meta">
                                <span class="prov-pop__pkt">{shortClass(src.packetClass || '') || 'unknown'}</span>
                                <span class="prov-pop__age">{fmtAge(ageMs)} ago</span>
                            </span>
                            <span class="prov-pop__change">
                                <span class="prov-pop__to">{String(e.value)}</span>
                                {#if e.prev != null}<span class="prov-pop__from">was {String(e.prev)}</span>{/if}
                            </span>
                            <span class="prov-pop__seq">#{src.seq ?? '—'}</span>
                        </a>
                    </li>
                {/each}
            </ol>
        {/if}
    </div>
</div>
