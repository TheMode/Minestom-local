<script lang="ts">
    import { toasts } from '../../state/toasts.svelte.ts';
</script>

<output id="toasts" class="toasts" aria-live="polite">
    {#each toasts.items as t (t.id)}
        <div class={'toast ' + (t.kind || '')}>{t.message}</div>
    {/each}
</output>
