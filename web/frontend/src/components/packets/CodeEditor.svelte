<script module lang="ts">
    import { renderTokens } from '../../lib/mql.ts';
    import { escapeHtml } from '../../lib/util.ts';

    const ICONS = { field: '◆', function: 'ƒ', keyword: '·', op: '=', literal: '∎', hint: '…' };

    const MIRROR_PROPS = ['boxSizing', 'height',
        'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
        'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
        'fontStyle', 'fontWeight', 'fontSize', 'lineHeight', 'fontFamily',
        'letterSpacing', 'tabSize'];

    let sharedMirror = null;

    function mirrorFrom(ta) {
        if (!sharedMirror) {
            sharedMirror = document.createElement('div');
            sharedMirror.className = 'mql-mirror';
            document.body.appendChild(sharedMirror);
        }
        const cs = window.getComputedStyle(ta);
        for (const p of MIRROR_PROPS) sharedMirror.style[p] = cs[p];
        sharedMirror.style.width = `${ta.clientWidth}px`;
        return cs;
    }

    function mirrorHeight(ta, text) {
        mirrorFrom(ta);
        sharedMirror.textContent = text.endsWith('\n') ? text + '\n ' : text;
        return sharedMirror.scrollHeight;
    }

    function caretCoords(ta, caret) {
        const cs = mirrorFrom(ta);
        sharedMirror.textContent = ta.value.slice(0, caret);
        const marker = document.createElement('span');
        marker.textContent = '​';
        sharedMirror.appendChild(marker);
        return {
            left:  marker.offsetLeft - ta.scrollLeft,
            top:   marker.offsetTop  - ta.scrollTop,
            lineH: parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.2,
        };
    }

    const schemaCache = new WeakMap();

    export { ICONS, caretCoords, schemaCache };
</script>

<script lang="ts">
    import * as mql from '../../lib/mql.ts';
    import * as expr from '../../lib/expression.ts';
    import { ComboboxPopover } from '../../lib/comboboxPopover.ts';

    let {
        value = '',
        onChange,
        onSubmit,
        language = 'mql',
        placeholder = 'gamemode = "SURVIVAL" and ping < 100',
        rows = 3,
        status = null,
        className = '',
        big = false,
        compact = false,
        focus = $bindable(null),
    } = $props();

    const lang = $derived(language === 'expression' ? expr : mql);

    let ta: HTMLTextAreaElement;
    let hl: HTMLElement;
    let pop: ComboboxPopover<any>;
    let errorAt = $state(null);
    let schema = $state(null);

    $effect(() => {
        let promise = schemaCache.get(lang);
        if (!promise) { promise = lang.loadSchema(); schemaCache.set(lang, promise); }
        let alive = true;
        promise.then(s => { if (alive) schema = s; });
        return () => { alive = false; };
    });

    $effect(() => {
        if (!hl) return;
        hl.innerHTML = renderTokens(lang, value, errorAt, schema) + '\n';
    });

    $effect(() => {
        pop = new ComboboxPopover('mql-pop', (c, i, selected) => `
            <li role="option" data-i="${i}" aria-selected="${selected}">
                <span class="ico t-${c.kind}">${ICONS[c.kind] || '·'}</span>
                <span class="lbl">${escapeHtml(c.label)}</span>
                <span class="kind">${escapeHtml(c.kind)}</span>
                <span class="detail">${escapeHtml(c.detail || '')}</span>
            </li>`, accept);
        pop.mount();
        return () => pop.destroy();
    });

    $effect(() => {
        if (status?.kind === 'error' && Number.isInteger(status.position)) errorAt = status.position;
        else errorAt = null;
    });

    $effect(() => {
        const onDown = e => {
            if (!pop?.open) return;
            if (pop?.contains(e.target) || ta?.contains(e.target as Node)) return;
            closePop();
        };
        document.addEventListener('pointerdown', onDown, true);
        return () => document.removeEventListener('pointerdown', onDown, true);
    });

    $effect(() => {
        if (focus && typeof focus === 'object') {
            focus.focus = () => ta?.focus();
        }
    });

    function closePop() {
        if (!pop) return;
        pop.hide();
    }

    function openPop() {
        if (!ta || !pop) return;
        const items = lang.complete(ta.value, ta.selectionStart, schema);
        if (items.length === 0) { closePop(); return; }
        pop.setItems(items);

        const parent = ta.closest('dialog') || document.body;
        pop.ensureParent(parent);
        pop.show();
        const { left, top, lineH } = caretCoords(ta, ta.selectionStart);
        const rect = ta.getBoundingClientRect();
        pop.position(rect.left + left, rect.top + top + lineH + 2);
    }

    function accept(idx) {
        const c = pop?.items[idx];
        if (!ta || !c) return;
        const [s, e] = c.range;
        const next = ta.value.slice(0, s) + c.insert + ta.value.slice(e);
        const caret = s + c.insert.length;
        ta.value = next;
        ta.setSelectionRange(caret, caret);
        closePop();
        errorAt = null;
        onChange?.(next);
        queueMicrotask(openPop);
    }

    function onKeyDown(e) {
        if (pop?.handleKey(e)) return;
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { onSubmit?.(ta.value); e.preventDefault(); return; }
        if (e.key === ' '     && (e.ctrlKey || e.metaKey)) { openPop(); e.preventDefault(); }
    }

    function grow() {
        if (!ta || !compact) return;
        ta.style.height = `${mirrorHeight(ta, ta.value || ta.placeholder || '\u200b')}px`;
    }

    function onInput(e) {
        errorAt = null;
        onChange?.(e.target.value);
        grow();
        openPop();
    }

    function onScroll() {
        if (hl && ta) {
            hl.scrollTop = ta.scrollTop;
            hl.scrollLeft = ta.scrollLeft;
        }
    }

    $effect(() => {
        value;
        placeholder;
        compact;
        if (!ta || !compact) return;
        grow();
        const ro = new ResizeObserver(grow);
        ro.observe(ta);
        return () => ro.disconnect();
    });

    const cls = $derived(['mql-editor', className, big && 'big', compact && 'compact'].filter(Boolean).join(' '));
</script>

<div class={cls}>
    <pre class="mql-hl" bind:this={hl} aria-hidden="true"></pre>
    <textarea
        bind:this={ta}
        class="mql-input"
        {rows}
        spellcheck="false"
        wrap="soft"
        autocapitalize="off"
        autocorrect="off"
        {placeholder}
        {value}
        oninput={onInput}
        onkeydown={onKeyDown}
        onscroll={onScroll}
        onclick={openPop}
    ></textarea>
    {#if language === 'mql'}
        <a class="mql-help" href="/query" title="MQL syntax guide" aria-label="MQL syntax guide">?</a>
    {/if}
    {#if status}
        <div class={'mql-status ' + (status.kind || '')} aria-live="polite">{status.message}</div>
    {/if}
</div>
