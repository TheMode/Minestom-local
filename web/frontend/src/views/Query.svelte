<script module lang="ts">
    const EXAMPLES = [
        { tag: 'Vitals',      desc: 'Players in serious trouble — low health, in survival.',         ql: 'health < 6 and gamemode = "SURVIVAL"' },
        { tag: 'Network',     desc: 'High-ping players. Useful for triaging laggy connections live.', ql: 'ping > 200' },
        { tag: 'World',       desc: 'Anyone currently in the overworld dimension.',                  ql: 'dimension = "minecraft:overworld"' },
        { tag: 'Geometry',    desc: 'Spawn-area campers — within 100 blocks of the world origin.',   ql: 'distance(pos, (0, 64, 0)) < 100' },
        { tag: 'Server data', desc: 'VIPs as marked by your plugin via the server-data channel.',    ql: 'server.rank = "vip" and server.kills > 10' },
        { tag: 'Pattern',     desc: 'Bot accounts — usernames matching a regex pattern.',            ql: 'name matches "Bot_.*"' },
        { tag: 'Text search', desc: 'Case-insensitive substring search — finds "Steve", "STEVE_42", …', ql: 'name ~ "steve"' },
        { tag: 'Logic',       desc: 'Compose boolean expressions with and / or / not and parentheses.', ql: 'not (gamemode = "CREATIVE") and (flying or health < 10)' },
        { tag: 'Collections', desc: 'Membership tests via has — useful for tags, attributes, lists.',  ql: 'server.tags has "staff" and not (server.muted)' },
    ];

    const GRAMMAR = [
        ['expr',  'or'],
        ['or',    "and ('or' and)*"],
        ['and',   "not ('and' not)*"],
        ['not',   "'not' not | cmp"],
        ['cmp',   'value op value | value'],
        ['op',    '= | != | < | <= | > | >= | ~ | matches | contains | has | in'],
        ['value', "ident('.'ident)* | number | string | tuple | call"],
    ];

    export { EXAMPLES, GRAMMAR };
</script>

<script lang="ts">
    import { api } from '../lib/api.ts';
    import { subscribeTopic } from '../state/bus.svelte.ts';
    import { Topics } from '../lib/topics.ts';
    import { debounce } from '../lib/util.ts';
    import { loadSchema, mqlError } from '../lib/mql.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import Pill from '../components/ui/Pill.svelte';
    import CodeEditor from '../components/packets/CodeEditor.svelte';
    import MqlSnippet from '../components/packets/MqlSnippet.svelte';
    import ReferenceList from '../components/ui/ReferenceList.svelte';

    let ql = $state('');
    let status = $state({ kind: 'dim', message: 'Empty expression' });
    let matches = $state([]);
    let players = $state(new Map());
    let schema = $state(null);

    $effect(() => { loadSchema().then(s => { schema = s; }); });

    const runQuery = debounce(async query => {
        if (!query.trim()) {
            matches = []; status = { kind: 'dim', message: 'Empty expression' };
            return;
        }
        try {
            const r = await api('/query', { method: 'POST', body: { ql: query } });
            const m = r.matches || [];
            matches = m;
            status = { kind: m.length ? 'ok' : 'dim', message: `Compiled · ${m.length} match${m.length === 1 ? '' : 'es'}` };
            try {
                const ps = await api('/players');
                players = new Map(ps.map(p => [p.uuid, p]));
            } catch {}
        } catch (e) {
            status = mqlError(e);
            matches = [];
        }
    }, 220);

    $effect(() => { runQuery(ql); });
    subscribeTopic(Topics.players, () => { if (ql.trim()) runQuery(ql); });

    const fields = $derived(schema?.fields || []);
    const operators = $derived(schema?.operators || []);
    const operatorRefs = $derived(operators.filter(o => ['comparison', 'keyword', 'arithmetic', 'pipe'].includes(o.kind)));
    const logicOperators = $derived(operators.filter(o => o.kind === 'logical'));
    const functions = $derived(schema?.functions || []);

    const fieldItems    = $derived(fields.map(f       => ({ name: f.name,         kind: 'field', detail: f.detail || '(custom field)'    })));
    const operatorItems = $derived(operatorRefs.map(o => ({ name: o.name,         kind: o.kind === 'keyword' ? 'kw' : 'op', detail: o.detail || '(custom operator)' })));
    const functionItems = $derived(functions.map(f    => ({ name: f.sig || f.name, kind: 'fn',    detail: f.detail || '(custom function)' })));
    const logicItems    = $derived(logicOperators.map(k => ({ name: k.name,       kind: 'kw',    detail: k.detail || '(custom keyword)'  })));
</script>

{#snippet guideCrumb()}MQL guide{/snippet}
{#snippet title()}<em>MQL</em> guide &amp; sandbox{/snippet}
{#snippet actions()}
    <button class="ghost" onclick={() => { ql = ''; runQuery(''); }}>Clear</button>
    <button class="primary" onclick={() => runQuery(ql)}>Evaluate</button>
{/snippet}

<ViewHead crumbs={[guideCrumb]} {title} {actions} />

<section class="query-hero">
    <div class="eyebrow">Minestom Query Language</div>
    <p class="lede">A small, total expression language with comparisons, boolean logic, dotted paths,
        regex matches, collection membership, and a tiny library of functions. Used by the trigger
        page, routine filters, and the in-app evaluators. Browse the examples, grammar, and reference
        below — or paste your own into the sandbox.</p>
    <div class="legend">
        <span class="lg-keyword">Keyword</span>
        <span class="lg-field">Field</span>
        <span class="lg-function">Function</span>
        <span class="lg-string">String</span>
        <span class="lg-number">Number</span>
        <span class="lg-op">Operator</span>
    </div>
</section>

<div class="query-showcase">
    <div class="col gap-lg">
        <Panel title="Sandbox">
            {#snippet meta()}press <kbd>cmd</kbd><kbd>↵</kbd> to run{/snippet}
            <CodeEditor value={ql} onChange={v => ql = v} rows={3} big placeholder='health < 6 and gamemode = "SURVIVAL"' {status} onSubmit={() => runQuery(ql)} />
            <div class="row between mt">
                <span class="dim small">Press <kbd>↩</kbd> to accept · <kbd>esc</kbd> to dismiss</span>
                <span class="acc small mono">{matches.length === 0 ? '—' : `${matches.length} match${matches.length === 1 ? '' : 'es'}`}</span>
            </div>
            <div class="mt">
                {#if matches.length === 0 && status?.kind === 'error'}
                    <div class="empty">{status.message}</div>
                {:else if matches.length > 0}
                    <div class="query-result">
                        {#each matches as u (u)}
                            {@const p = players.get(u)}
                            <a href={'/p/' + u} class="match-row">
                                <Pill kind="on">{p?.username || u.slice(0, 8)}</Pill>
                                <span class="match-uuid mono">{u}</span>
                                <span class="match-dim">{(p?.dimension || '—').replace('minecraft:', '')}</span>
                            </a>
                        {/each}
                    </div>
                {/if}
            </div>
        </Panel>

        <Panel title="Examples" meta="click to load">
            <div class="examples-grid">
                {#each EXAMPLES as e, i (i)}
                    <button type="button" class="example-card" onclick={() => { ql = e.ql; runQuery(e.ql); }}>
                        <div class="tag">{e.tag}</div>
                        <div class="desc">{e.desc}</div>
                        <pre class="snippet"><MqlSnippet src={e.ql} /></pre>
                        <div class="try">▸ try this</div>
                    </button>
                {/each}
            </div>
        </Panel>
    </div>

    <aside class="col gap-lg">
        <Panel title="Grammar" meta="Pratt">
            {#each GRAMMAR as [name, rule], i (i)}
                <div class="grammar-rule"><span class="name">{name}</span><span class="rule">{rule}</span></div>
            {/each}
        </Panel>

        <ReferenceList title="Fields"    items={fieldItems} />
        <ReferenceList title="Operators" items={operatorItems} />
        <ReferenceList title="Functions" items={functionItems} />
        <ReferenceList title="Logic"     items={logicItems} />
    </aside>
</div>
