<script lang="ts">
    import { fmtTime } from '../../lib/util.ts';
    import MinecraftText from './MinecraftText.svelte';

    let { value, ts, className = '', withTimestamp = true } = $props();
</script>

<div class={('chat-line ' + className).trim()}>
    {#if withTimestamp && ts != null}
        <span class="chat-line-ts dim mono small">{fmtTime(ts).slice(0, 8)}</span>
    {/if}
    <MinecraftText {value} />
</div>
