<script lang="ts">
    import { api } from '../../lib/api.ts';
    import Panel from './Panel.svelte';
    import ActionSelector from '../editors/ActionSelector.svelte';
    import { toast } from '../../state/toasts.svelte.ts';

    let { p } = $props();
    let action = $state(null);
    let result = $state(null);

    async function run() {
        if (!action) { toast('No action defined', 'error'); return; }
        try {
            const r = await api('/trigger', { method: 'POST', body: { query: `name = "${p.username}"`, action } });
            result = JSON.stringify(r, null, 2);
            toast(`Action fired on ${r.fired}/${r.matched}`);
        } catch (e) { toast('Failed: ' + e.message, 'error'); }
    }
</script>

<Panel title="Run action" meta={`on ${p.username || 'this player'}`}>
    {#snippet actions()}<button class="primary sm" onclick={run}>Run</button>{/snippet}
    <ActionSelector value={action} onChange={v => action = v} />
    {#if result}
        <pre class="code small mt-sm">{result}</pre>
    {/if}
</Panel>
