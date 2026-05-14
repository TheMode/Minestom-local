<script lang="ts">
    const BOSS: Record<string, string> = {
        PINK: 'oklch(70% 0.22 320)', BLUE: 'oklch(62% 0.16 250)', RED: 'oklch(58% 0.22 25)',
        GREEN: 'oklch(68% 0.18 145)', YELLOW: 'oklch(82% 0.16 95)', PURPLE: 'oklch(58% 0.2 300)',
        WHITE: 'oklch(92% 0.02 250)',
    };

    let {
        value = 0,
        variant = 'spectrum',
        class: className = '',
        color,
        children,
    }: {
        value?: number;
        variant?: 'spectrum' | 'boss';
        class?: string;
        color?: string | null;
        children?: import('svelte').Snippet;
    } = $props();

    const pct = $derived(Math.max(0, Math.min(1, value ?? 0)));
    const fill = $derived(variant === 'boss' && color ? (BOSS[color.toUpperCase()] ?? BOSS.PINK) : undefined);
</script>

<div
    class="progress-bar progress-bar--{variant} {className}"
    role="progressbar"
    aria-valuenow={Math.round(pct * 100)}
    aria-valuemin={0}
    aria-valuemax={100}
>
    <div class="progress-bar__track">
        <div class="progress-bar__fill" style:width={(pct * 100) + '%'} style:--fill={fill}></div>
    </div>
    {#if children}
        <div class="progress-bar__meta">{@render children()}</div>
    {/if}
</div>
