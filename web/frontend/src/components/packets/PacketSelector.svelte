<script module lang="ts">
    import { api } from '../../lib/api.ts';
    import { escapeHtml } from '../../lib/util.ts';

    const SIDE_BADGE  = { client: '◀ client', server: '▶ server' };
    const STATE_ORDER = { PLAY: 0, CONFIGURATION: 1, LOGIN: 2, STATUS: 3, HANDSHAKE: 4 };

    const catalogPromises = { full: null, analyzable: null };
    function loadCatalog(analyzable = false) {
        const key = analyzable ? 'analyzable' : 'full';
        if (!catalogPromises[key]) {
            const path = analyzable ? '/packets/known?analyzable=true' : '/packets/known';
            catalogPromises[key] = api(path).catch(() => []);
        }
        return catalogPromises[key];
    }

    export { SIDE_BADGE, STATE_ORDER, loadCatalog };
</script>

<script lang="ts">
    import { ComboboxPopover } from '../../lib/comboboxPopover.ts';

    let { value = '', onChange, placeholder = 'ClientChatMessagePacket', analyzable = false } = $props();

    let input: HTMLInputElement;
    let pop: ComboboxPopover<any>;
    let catalog = [];

    $effect(() => {
        pop = new ComboboxPopover('ps-pop', (p, i, selected) => `
            <li role="option" data-i="${i}" aria-selected="${selected}">
                <span class="ps-simple">${escapeHtml(p.simple)}</span>
                <span class="ps-meta">
                    <span class="ps-side ps-${p.side}">${escapeHtml(SIDE_BADGE[p.side] || p.side)}</span>
                    <span class="ps-state">${escapeHtml(p.state.toLowerCase())}</span>
                </span>
            </li>`, accept);
        pop.mount();
        return () => pop.destroy();
    });

    $effect(() => {
        loadCatalog(analyzable).then(c => { catalog = c; });
    });

    function refresh() {
        if (!input) return;
        const q = input.value.trim().toLowerCase();
        const items = catalog
            .map(p => {
                const sl = p.simple.toLowerCase();
                const score = !q ? 0 : sl.startsWith(q) ? 0 : sl.includes(q) ? 1 : -1;
                return { p, score };
            })
            .filter(x => x.score >= 0)
            .sort((a, b) =>
                a.score - b.score
                || (STATE_ORDER[a.p.state] ?? 9) - (STATE_ORDER[b.p.state] ?? 9)
                || a.p.simple.localeCompare(b.p.simple))
            .map(x => x.p);
        if (items.length === 0) { pop?.hide(); return; }
        pop.setItems(items);
        position();
        pop.show();
    }

    function position() {
        if (!pop || !input) return;
        const parent = input.closest('dialog') || document.body;
        pop.ensureParent(parent);
        const r = input.getBoundingClientRect();
        pop.position(r.left, r.bottom + 2, r.width);
    }

    function accept(idx) {
        const p = pop?.items[idx];
        if (!p) return;
        onChange?.(p.simple);
        pop.hide();
    }
</script>

<div class="packet-selector">
    <input
        bind:this={input}
        class="packet-input"
        {value}
        {placeholder}
        spellcheck="false"
        autocapitalize="off"
        autocorrect="off"
        oninput={e => { onChange?.(e.target.value); refresh(); }}
        onfocus={refresh}
        onclick={refresh}
        onblur={() => setTimeout(() => pop?.hide(), 100)}
        onkeydown={e => pop?.handleKey(e)}
    />
</div>
