<script lang="ts">
    import type { DslQuery } from '../../lib/packetTraceDsl.ts';

    interface Props {
        query?: string;
        parsed?: DslQuery | null;
        live?: boolean;
        paused?: boolean;
        rate?: number;
        totalPackets?: number;
        jump?: string;
        breakOn?: boolean;
        searchRef?: HTMLInputElement | null;
        onQuery?: (v: string) => void;
        onPaused?: (v: boolean) => void;
        onStep?: (d: number) => void;
        onLive?: () => void;
        onJump?: () => void;
        onJumpChange?: (v: string) => void;
        onHelp?: () => void;
        onTweaks?: () => void;
    }

    let {
        query = '',
        parsed = null,
        live = false,
        paused = false,
        rate = 0,
        totalPackets = 0,
        jump = '',
        breakOn = false,
        searchRef = $bindable<HTMLInputElement | null>(null),
        onQuery = () => {},
        onPaused = () => {},
        onStep = () => {},
        onLive = () => {},
        onJump = () => {},
        onJumpChange = () => {},
        onHelp = () => {},
        onTweaks = () => {},
    }: Props = $props();
</script>

<header class="pt-top">
    <div class="search-inline search-inline--bar" title="Filter DSL: class:Position dir:cb size:>200">
        <span class="icon">⌕</span>
        <input
            bind:this={searchRef}
            value={query}
            oninput={e => onQuery(e.currentTarget.value)}
            placeholder={'filter — try   class:Position dir:sb size:>20   or just "chest"'}
            spellcheck={false}
        />
        {#if query}
            <button class="clear" type="button" onclick={() => onQuery('')} title="clear">✕</button>
        {/if}
    </div>

    {#if parsed && parsed.tokens.length}
        <div class="row pt-query-tokens">
            {#each parsed.tokens as t, i (i)}
                <span
                    class="chip-filter chip-filter--sm"
                    class:is-exclude={t.neg}
                    class:is-include={!t.neg}
                    title={t.kind === 'kv' ? `${t.key} ${t.op} ${t.val}` : 'text'}
                >
                    {t.neg ? '−' : '+'} {t.raw}
                </span>
            {/each}
        </div>
    {/if}

    <div class="pt-controls" aria-label="Packet trace controls">
        <button
            class="btn sm icon"
            class:is-on={!paused}
            type="button"
            onclick={() => onPaused(!paused)}
            title={paused ? 'Resume (space)' : 'Pause (space)'}
        >{paused ? '▶' : '▮▮'}</button>
        <button class="btn sm icon" type="button" onclick={() => onStep(-1)} title="Prev (←)">◂</button>
        <button class="btn sm icon" type="button" onclick={() => onStep(1)} title="Next (→)">▸</button>
        <button class="btn sm" class:is-on={live} type="button" onclick={onLive} title="Jump to live (F)">⤓ live</button>

        <div class="divider-v"></div>

        <div class="pt-jump">
            <span class="label">#seq</span>
            <input
                value={jump}
                oninput={e => onJumpChange(e.currentTarget.value)}
                onkeydown={e => { if (e.key === 'Enter') onJump(); }}
                placeholder="…"
            />
        </div>

        <div class="divider-v"></div>

        <div class="gauge-inline" title="Current packets / sec">
            <span class="num">{rate}</span>
            <span class="lbl">p/s</span>
        </div>
        <div class="gauge-inline" title="Total in buffer">
            <span class="num">{totalPackets.toLocaleString()}</span>
            <span class="lbl">pkts</span>
        </div>

        <div class="divider-v"></div>

        <button
            class="btn sm icon"
            class:danger={breakOn}
            class:is-on={breakOn}
            type="button"
            onclick={onTweaks}
            title="Tweaks & breakpoints"
        >⏻</button>
        <button class="btn sm icon" type="button" onclick={onHelp} title="Help (?)">?</button>
    </div>
</header>
