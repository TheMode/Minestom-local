<script lang="ts">
    import Panel from '../ui/Panel.svelte';
    import PlayerInventory from './PlayerInventory.svelte';

    let { p }: { p: any } = $props();

    const openSlots = $derived(p.openedWindow ? (p.openedWindow.slots || []).length : -1);
    const inv = $derived(openSlots >= 0
        ? `open: ${openSlots} slot${openSlots === 1 ? '' : 's'}`
        : `slot ${p.selectedHotbar ?? 0}`);
</script>

<Panel title="Inventory" meta={inv}>
    <PlayerInventory
        armor={p.armor || []}
        main={p.mainInventory || []}
        hotbar={p.hotbar || []}
        offHand={p.offHand}
        cursor={p.cursor}
        selectedHotbar={p.selectedHotbar}
        openedWindow={p.openedWindow}
        recentClicks={p.recentClicks || []}
        profileProperties={p.profileProperties}
    />
</Panel>
