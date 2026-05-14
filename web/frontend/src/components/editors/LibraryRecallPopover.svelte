<script lang="ts">
    import { packetLibrary, type LibraryBucket } from '../../lib/packetLibrary.svelte.ts';
    import { anchoredPopover } from '../../lib/floatingPopover.svelte.ts';

    type Props = {
        bucket: LibraryBucket;
        anchor: HTMLElement;
        onPick: (value: unknown) => void;
        onClose: () => void;
    };

    let { bucket, anchor, onPick, onClose }: Props = $props();

    const entries = $derived(packetLibrary.list(bucket));

    let pop = $state<HTMLDivElement | null>(null);

    const { pos } = anchoredPopover<HTMLDivElement>(
        () => anchor,
        () => pop,
        (r) => ({ left: Math.max(8, r.right - 260), top: r.bottom + 4 }),
        () => onClose(),
    );

    function pick(v: unknown) {
        onPick(structuredClone(v));
        onClose();
    }
</script>

<div
    bind:this={pop}
    class="lib-recall-pop"
    style:left="{pos.left}px"
    style:top="{pos.top}px"
    role="menu"
>
    <div class="lib-recall-pop__head">SAVED {bucket.toUpperCase()}</div>
    {#if entries.length === 0}
        <div class="lib-recall-pop__empty">
            No saved {bucket} yet. Hit ☆ on a row to save one.
        </div>
    {:else}
        {#each entries as e, i (i)}
            <div class="lib-recall-pop__item">
                <button type="button" class="lib-recall-pop__name" onclick={() => pick(e.value)}>{e.name}</button>
                <button
                    type="button"
                    class="lib-recall-pop__rm"
                    title="Remove"
                    onclick={() => packetLibrary.remove(bucket, i)}
                >×</button>
            </div>
        {/each}
    {/if}
</div>
