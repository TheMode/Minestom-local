<script lang="ts">
    import { contextMenu } from '../../state/contextMenu.svelte.ts';

    const MIN_W = 200;
    const PAD = 6;

    let menuEl: HTMLElement | undefined = $state();
    let highlighted = $state(0);

    /// Indices of items that can take the keyboard cursor — separators and headings are skipped
    /// when arrowing through the menu.
    const selectable = $derived(
        (contextMenu.state?.items ?? [])
            .map((it, i) => it.kind === 'item' && !it.disabled ? i : -1)
            .filter(i => i >= 0),
    );

    $effect(() => {
        if (!contextMenu.state) return;
        highlighted = selectable[0] ?? 0;

        const onDown = (e: MouseEvent) => {
            if (!menuEl?.contains(e.target as Node)) contextMenu.close();
        };
        const onKey = (e: KeyboardEvent) => {
            if (e.key === 'Escape') { contextMenu.close(); e.preventDefault(); return; }
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                if (!selectable.length) return;
                const cur = selectable.indexOf(highlighted);
                const step = e.key === 'ArrowDown' ? 1 : -1;
                highlighted = selectable[(cur + step + selectable.length) % selectable.length];
                e.preventDefault();
            } else if (e.key === 'Enter') {
                const item = contextMenu.state?.items[highlighted];
                if (item?.kind === 'item' && !item.disabled) {
                    item.onSelect();
                    contextMenu.close();
                    e.preventDefault();
                }
            }
        };
        const onBlur = () => contextMenu.close();
        const onScroll = () => contextMenu.close();
        // Capture-phase mousedown so the menu closes BEFORE click handlers underneath fire —
        // otherwise re-targeting the menu via another right-click leaks a phantom left-click.
        window.addEventListener('mousedown', onDown, true);
        window.addEventListener('keydown', onKey);
        window.addEventListener('blur', onBlur);
        window.addEventListener('scroll', onScroll, true);
        return () => {
            window.removeEventListener('mousedown', onDown, true);
            window.removeEventListener('keydown', onKey);
            window.removeEventListener('blur', onBlur);
            window.removeEventListener('scroll', onScroll, true);
        };
    });

    /// Position so the menu stays inside the viewport. Measured after mount because width
    /// depends on label content; falls back to MIN_W until measurement is available.
    const style = $derived.by(() => {
        const s = contextMenu.state;
        if (!s) return '';
        const w = menuEl?.offsetWidth ?? MIN_W;
        const h = menuEl?.offsetHeight ?? 0;
        const left = Math.max(PAD, Math.min(window.innerWidth  - w - PAD, s.x));
        const top  = Math.max(PAD, Math.min(window.innerHeight - h - PAD, s.y));
        return `left:${left}px;top:${top}px;min-width:${MIN_W}px`;
    });
</script>

{#if contextMenu.state}
    <div
        bind:this={menuEl}
        class="ctx-menu"
        role="menu"
        tabindex="-1"
        style={style}
        oncontextmenu={e => e.preventDefault()}
    >
        {#each contextMenu.state.items as it, i (i)}
            {#if it.kind === 'separator'}
                <div class="ctx-menu__sep" role="separator"></div>
            {:else if it.kind === 'heading'}
                <div class="ctx-menu__heading">{it.label}</div>
            {:else}
                <button
                    type="button"
                    role="menuitem"
                    class={'ctx-menu__item'
                        + (it.tone ? ' ctx-menu__item--' + it.tone : '')
                        + (it.active ? ' is-active' : '')
                        + (i === highlighted ? ' is-cursor' : '')}
                    disabled={it.disabled}
                    onmouseenter={() => { highlighted = i; }}
                    onclick={() => { it.onSelect(); contextMenu.close(); }}
                >
                    <span class="ctx-menu__icon">{it.icon ?? ''}</span>
                    <span class="ctx-menu__label">{it.label}</span>
                    {#if it.hint}<span class="ctx-menu__hint">{it.hint}</span>{/if}
                </button>
            {/if}
        {/each}
    </div>
{/if}
