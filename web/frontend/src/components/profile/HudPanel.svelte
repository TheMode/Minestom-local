<script lang="ts">
    import { sidebarRows } from '../../lib/profile.ts';
    import Panel from '../ui/Panel.svelte';
    import ProgressBar from '../ui/ProgressBar.svelte';
    import MinecraftText from '../mctext/MinecraftText.svelte';
    import ChatLine from '../mctext/ChatLine.svelte';

    let { p }: { p: any } = $props();
</script>

<Panel meta="live mirror" className="hud-panel" flush>
    {#snippet title()}HUD <em>theater</em>{/snippet}
    <div class="hud-theater">
        {#if Object.keys(p.bossBars || {}).length > 0}
            <div class="hud-bossbars">
                {#each Object.entries(p.bossBars || {}).filter(([, b]) => b != null) as [id, b], i (id)}
                    <div class="hud-bossbar">
                        <div class="hud-bossbar-title mc-component"><MinecraftText value={b.title} /></div>
                        <ProgressBar variant="boss" value={b.progress ?? 0} color={b.color} />
                    </div>
                {/each}
            </div>
        {/if}
        {#if p.scoreboard}
            <div class="hud-scoreboard">
                <div class="sb-title"><MinecraftText value={p.scoreboard.displayName || p.scoreboard.objectiveName || '—'} /></div>
                {#each sidebarRows(p.scoreboard.rows) as row (row.key)}
                    <div class="sb-row">
                        <span class="sb-k"><MinecraftText value={row.display ?? row.key} /></span>
                        {#if row.numberFormat?.format === 'FIXED'}
                            <span class="v sb-v"><MinecraftText value={row.numberFormat.content} /></span>
                        {:else if row.numberFormat?.format === 'BLANK'}
                            <span class="v sb-v"></span>
                        {:else}
                            <span class="v sb-v">{row.score}</span>
                        {/if}
                    </div>
                {/each}
            </div>
        {/if}
        {#if p.lastActionBar != null}
            <div class="hud-actionbar"><MinecraftText value={p.lastActionBar} /></div>
        {/if}
        <div class="hud-chat">
            <div class="hud-chat-log mc-component">
                {#each (p.recentChat || []).slice(-12) as line, i (i)}
                    <ChatLine ts={line.ts} value={line.content} className="hud-chat-line" />
                {/each}
            </div>
        </div>
    </div>
</Panel>
