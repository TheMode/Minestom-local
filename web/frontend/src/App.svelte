<script module lang="ts">
    function pageKey(route) {
        const { root, segs } = route;
        if (!root) return 'dashboard';
        if (root === 'p' && segs[1]) return 'p';
        return root;
    }
</script>

<script lang="ts">
    import { route } from './state/route.svelte.ts';
    import { mode } from './state/mode.svelte.ts';
    import { ensureBoot } from './main.ts';
    import { onLinkClick } from './lib/nav.ts';
    import Sidebar from './components/Sidebar.svelte';
    import Toasts from './components/ui/Toasts.svelte';
    import TweaksPanel from './components/ui/TweaksPanel.svelte';
    import EntityTooltipHost from './components/overlay/EntityTooltipHost.svelte';
    import McJsonTooltipHost from './components/overlay/McJsonTooltipHost.svelte';
    import ContextMenuHost from './components/overlay/ContextMenuHost.svelte';

    import Landing from './views/Landing.svelte';
    import Dashboard from './views/Dashboard.svelte';
    import Players from './views/Players.svelte';
    import GlobalPackets from './views/GlobalPackets.svelte';
    import Profile from './views/Profile.svelte';
    import Trigger from './views/Trigger.svelte';
    import Actions from './views/Actions.svelte';
    import Query from './views/Query.svelte';
    import Routines from './views/Routines.svelte';
    import Terminal from './views/Terminal.svelte';
    import Throttle from './views/Throttle.svelte';

    const key = $derived(pageKey(route.current));
    const navKey = $derived(key === 'p' ? 'players' : key);
    const profileUuid = $derived(key === 'p' ? route.current.segs[1] : null);
    const profileTab  = $derived(key === 'p' ? (route.current.segs[2] || 'overview') : null);

    /// In replay mode with no scope attached, the entire app collapses to the Landing view —
    /// player lists, packet history, routines all depend on a scope's registry, so there's
    /// nothing useful to show yet.
    const showLanding = $derived(mode.mode === 'replay' && !mode.scope);
    /// True when the live proxy is present. Replay scopes have no socket to inject onto, no
    /// throttle manager, no server-side console — hide those views so they don't 405.
    const isReplay = $derived(mode.mode === 'replay');

    // After a successful upload, mode.scope flips from null to a summary — boot the bus +
    // shared stores at that point (deferred from boot() because the WS would be rejected
    // without a scope id in replay mode).
    $effect(() => { if (mode.scope) ensureBoot(); });

    $effect(() => {
        document.addEventListener('click', onLinkClick);
        return () => document.removeEventListener('click', onLinkClick);
    });

    let tweaksOpen = $state(false);
</script>

{#if showLanding}
    <main id="view" class="view"><Landing /></main>
{:else}
    <Sidebar {navKey} currentUuid={profileUuid} {profileTab} {isReplay} onTweaks={() => tweaksOpen = !tweaksOpen} />
    <main id="view" class="view" aria-live="polite">
        {#if key === 'dashboard'}<Dashboard />
        {:else if key === 'players'}<Players />
        {:else if key === 'packets'}<GlobalPackets />
        {:else if key === 'terminal' && !isReplay}<Terminal />
        {:else if key === 'trigger' && !isReplay}<Trigger />
        {:else if key === 'actions' && !isReplay}<Actions />
        {:else if key === 'query'}<Query />
        {:else if key === 'routines' && !isReplay}<Routines />
        {:else if key === 'throttle' && !isReplay}<Throttle />
        {:else if key === 'p'}<Profile uuid={profileUuid} tab={profileTab} />
        {:else}<Dashboard />
        {/if}
    </main>
    {#if tweaksOpen}<TweaksPanel onClose={() => tweaksOpen = false} />{/if}
    <EntityTooltipHost />
    <ContextMenuHost />
{/if}
<Toasts />
<McJsonTooltipHost />
