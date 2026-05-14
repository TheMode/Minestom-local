<script module lang="ts">
    /// Adventure text component shape. The builder surfaces text + color + decorations + extra
    /// inline; richer keys (translation, click events, …) round-trip in the underlying object
    /// but aren't editable here.
    export type ComponentValue = Record<string, unknown>;

    export const NAMED_COLORS = [
        'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple',
        'gold', 'gray', 'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple',
        'yellow', 'white',
    ] as const;

    export const COLOR_HEX: Record<string, string> = {
        black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
        dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa',
        dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
        red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff',
    };

    export const DECORATIONS = ['bold', 'italic', 'underlined', 'strikethrough', 'obfuscated'] as const;
</script>

<script lang="ts">
    import CodeEditor from '../packets/CodeEditor.svelte';
    import Self from './ComponentBuilder.svelte';
    import { libraryDrop } from '../../lib/libraryDrop.svelte.ts';

    type Props = {
        value: ComponentValue | null;
        onChange: (v: ComponentValue) => void;
        embedded?: boolean;
    };

    let { value, onChange, embedded = false }: Props = $props();

    const safe = $derived((value && typeof value === 'object' ? value : {}) as ComponentValue);
    const text = $derived(typeof safe.text === 'string' ? safe.text : '');
    const color = $derived(typeof safe.color === 'string' ? safe.color : '');
    const extras = $derived(Array.isArray(safe.extra) ? (safe.extra as ComponentValue[]) : []);

    function patch(key: string, val: unknown) {
        const next = { ...safe };
        if (val == null || val === '' || val === false) delete next[key];
        else next[key] = val;
        onChange(next);
    }

    function setExtraAt(i: number, v: ComponentValue) {
        const next = [...extras];
        next[i] = v;
        patch('extra', next);
    }

    function removeExtra(i: number) {
        const next = extras.filter((_, j) => j !== i);
        patch('extra', next.length ? next : null);
    }

    function addExtra() {
        patch('extra', [...extras, { text: '' }]);
    }

    const drop = libraryDrop('components', (v) => onChange(v as ComponentValue));
</script>

<div
    class="cb-builder builder {drop.over ? 'drop-over' : ''}"
    role="region"
    {...drop.handlers}
>
    <div class="builder__head">
        <span class="builder__title"><span class="builder__title-dot builder__title-dot--comp"></span>text component</span>
        {#if extras.length > 0}
            <span class="builder__tag">+{extras.length} extra</span>
        {/if}
    </div>

    <div class="cb-builder__body">
        <div class="cb-grid">
            <span class="cb-grid__lbl">text</span>
            <CodeEditor
                language="expression"
                value={text}
                onChange={(v) => patch('text', v)}
                rows={1}
                placeholder={'hello, or player.name, or "score: " + player.health'}
            />

            <span class="cb-grid__lbl">color</span>
            <div class="cb-swatches">
                <button
                    type="button"
                    class="cb-swatch cb-swatch--none {!color ? 'is-on' : ''}"
                    title="default"
                    aria-label="default color"
                    onclick={() => patch('color', null)}
                ></button>
                {#each NAMED_COLORS as c (c)}
                    <button
                        type="button"
                        class="cb-swatch {color === c ? 'is-on' : ''}"
                        title={c}
                        aria-label={c}
                        style:background={COLOR_HEX[c]}
                        onclick={() => patch('color', c)}
                    ></button>
                {/each}
                <input
                    type="text"
                    class="cb-swatch__hex"
                    placeholder="#rrggbb"
                    value={color && !COLOR_HEX[color] ? color : ''}
                    oninput={(e) => patch('color', (e.currentTarget as HTMLInputElement).value || null)}
                />
            </div>

            <span class="cb-grid__lbl">style</span>
            <div class="cb-deco">
                {#each DECORATIONS as d (d)}
                    <button
                        type="button"
                        class="cb-deco__chip {safe[d] === true ? 'is-on' : ''}"
                        onclick={() => patch(d, safe[d] === true ? false : true)}
                    >
                        <span class="cb-deco__chip-glyph cb-deco__chip-glyph--{d}">{d[0].toUpperCase()}</span>
                        <span>{d}</span>
                    </button>
                {/each}
            </div>

            <span class="cb-grid__lbl">extra</span>
            <div class="cb-extras">
                {#each extras as child, i (i)}
                    <div class="cb-extras__row">
                        <span class="cb-extras__idx">{i}</span>
                        <div class="cb-extras__slot">
                            <Self value={child} onChange={(c) => setExtraAt(i, c)} embedded={true} />
                        </div>
                        <button type="button" class="dc-row__del" title="Remove" onclick={() => removeExtra(i)}>×</button>
                    </div>
                {/each}
                <button type="button" class="coll-add" onclick={addExtra}>+ extra child component</button>
            </div>
        </div>
    </div>
</div>
