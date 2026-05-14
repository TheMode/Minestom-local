<script module lang="ts">
    import { ActionType } from '../../lib/routineWire.ts';

    export const KINDS = [
        { id: ActionType.inject, label: 'Inject', detail: 'Inject a packet — direction is derived from the selected packet.' },
        { id: ActionType.chat, label: 'Chat', detail: 'Send a system chat message — body is an expression evaluated per player.' },
        { id: ActionType.setCustom, label: 'Set custom', detail: 'Set a custom key on the player state — value is an expression.' },
        { id: ActionType.move, label: 'Move', detail: 'Transfer the player to another server — the address expression evaluates to "host", "host:port", or "[ipv6]:port".' },
        { id: ActionType.sequence, label: 'Sequence', detail: 'Run multiple actions in order.' },
    ];

    const KIND_IDS = new Set(KINDS.map(k => k.id));

    export const DEFAULTS: Record<string, () => Record<string, unknown>> = {
        [ActionType.inject]: () => ({ type: ActionType.inject, packet: '', fields: {} }),
        [ActionType.chat]: () => ({ type: ActionType.chat, component: '' }),
        [ActionType.setCustom]: () => ({ type: ActionType.setCustom, key: '', value: '' }),
        [ActionType.move]: () => ({ type: ActionType.move, address: '' }),
        [ActionType.sequence]: () => ({ type: ActionType.sequence, actions: [] }),
    };

    export function normalise(a: Record<string, unknown> | null | undefined) {
        const type = KIND_IDS.has(a?.type as string) ? (a!.type as string) : ActionType.chat;
        return { ...DEFAULTS[type](), ...a, type };
    }

    export function clean(s: Record<string, unknown>) {
        const type = s.type as string;
        if (type === ActionType.inject) {
            return { type: ActionType.inject, packet: String(s.packet ?? '').trim(), fields: (s.fields as object) || {} };
        }
        if (type === ActionType.chat) return { type: ActionType.chat, component: s.component ?? '' };
        if (type === ActionType.setCustom) return { type: ActionType.setCustom, key: s.key || '', value: s.value || '' };
        if (type === ActionType.move) return { type: ActionType.move, address: String(s.address ?? '') };
        if (type === ActionType.sequence) return { type: ActionType.sequence, actions: (s.actions as unknown[]) || [] };
        return { type: ActionType.chat };
    }

    export function actionSummary(a: Record<string, unknown> | null | undefined) {
        if (!a) return '(none)';
        const truncate = (s: string, n = 40) => (s || '').slice(0, n);
        return ({
            [ActionType.inject]: () => `inject: ${a.packet || '?'}`,
            [ActionType.chat]: () => typeof a.component === 'object' ? 'chat: [component]'
                : `chat: ${truncate(String(a.component ?? ''))}`,
            [ActionType.setCustom]: () => `set ${a.key || '?'} = ${a.value || '""'}`,
            [ActionType.move]: () => `move → ${truncate(String(a.address ?? '?'))}`,
            [ActionType.sequence]: () => `sequence (${((a.actions as unknown[]) || []).length} actions)`,
        } as Record<string, () => string>)[a.type as string]?.() ?? String(a.type ?? '(unknown)');
    }

    export { ActionType };
</script>

<script lang="ts">
    import { api } from '../../lib/api.ts';
    import { debounce } from '../../lib/util.ts';
    import { mqlError } from '../../lib/mql.ts';
    import PacketSelector from '../packets/PacketSelector.svelte';
    import PacketFieldsEditor from './PacketFieldsEditor.svelte';
    import CodeEditor from '../packets/CodeEditor.svelte';
    import ActionEditor from './ActionEditor.svelte';

    let { value, onChange } = $props();

    const groupId = 'act-' + Math.random().toString(36).slice(2, 9);
    const state = $derived(normalise(value));
    const kind = $derived(KINDS.find(k => k.id === state.type));

    function set(next: Record<string, unknown>) { onChange?.(clean(next)); }

    function makeValidator() {
        let status = $state(null);
        const debouncedValidate = debounce(async (src: string) => {
            if (!src.trim()) { status = null; return; }
            try {
                await api('/expression/compile', { method: 'POST', body: { src } });
                status = { kind: 'ok', message: 'OK' };
            } catch (e) {
                status = mqlError(e);
            }
        }, 220);
        return {
            get status() { return status; },
            validate: (src: string) => debouncedValidate(src),
        };
    }

    const chatValidator = makeValidator();
    const setCustomValidator = makeValidator();
    const moveAddressValidator = makeValidator();

    $effect(() => {
        if (state.type === ActionType.chat) {
            const c = state.component;
            if (typeof c === 'string') chatValidator.validate(c);
        }
        if (state.type === ActionType.setCustom) setCustomValidator.validate(String(state.value || ''));
        if (state.type === ActionType.move) moveAddressValidator.validate(String(state.address || ''));
    });

    function updateSequenceAction(index: number, action: Record<string, unknown>) {
        const actions = [...((state.actions as Record<string, unknown>[]) || [])];
        actions[index] = action;
        set({ ...state, actions });
    }

    function addSequenceAction() {
        set({ ...state, actions: [...((state.actions as Record<string, unknown>[]) || []), DEFAULTS[ActionType.chat]()] });
    }

    function removeSequenceAction(index: number) {
        const actions = [...((state.actions as Record<string, unknown>[]) || [])];
        actions.splice(index, 1);
        set({ ...state, actions });
    }
</script>

<div class="action-editor">
    <div class="seg-control editor-tabs" role="radiogroup">
        {#each KINDS as k (k.id)}
            <label class="seg-control__item editor-tab">
                <input
                    type="radio"
                    name={groupId}
                    value={k.id}
                    checked={state.type === k.id}
                    onchange={() => state.type === k.id ? null : set(DEFAULTS[k.id]())}
                />
                <span class="editor-tab__label">{k.label}</span>
            </label>
        {/each}
    </div>
    <div class="editor-detail">{kind?.detail || ''}</div>
    <div class="editor-config">
        {#if state.type === ActionType.inject}
            <label class="editor-field editor-field--col">
                <span class="editor-label">Packet</span>
                <PacketSelector
                    value={String(state.packet || '')}
                    onChange={v => set({ ...state, packet: v, fields: {} })}
                    analyzable={true}
                />
            </label>
            <div class="editor-field editor-field--col">
                <span class="editor-label">Fields</span>
                <PacketFieldsEditor
                    packet={String(state.packet || '')}
                    fields={(state.fields as Record<string, unknown>) || {}}
                    onChange={v => set({ ...state, fields: v })}
                />
            </div>
        {:else if state.type === ActionType.chat}
            {@const isJson = state.component != null && typeof state.component === 'object'}
            <label class="editor-field editor-field--col">
                <span class="editor-label">{isJson ? 'Message (component)' : 'Message (expression)'}</span>
                <CodeEditor
                    language={isJson ? 'json' : 'expression'}
                    value={isJson ? JSON.stringify(state.component, null, 2) : String(state.component ?? '')}
                    onChange={v => {
                        if (isJson || v.trimStart().startsWith('{')) {
                            try { set({ ...state, component: JSON.parse(v) }); return; } catch {}
                        }
                        set({ ...state, component: v });
                    }}
                    rows={isJson ? 6 : 2}
                    placeholder={isJson ? '{"text":"hello"}' : '"Hello " + name + "!"'}
                    status={isJson ? null : chatValidator.status}
                />
            </label>
        {:else if state.type === ActionType.setCustom}
            <label class="editor-field">
                <span class="editor-label">Key</span>
                <input value={String(state.key || '')} placeholder="flagged" onchange={e => set({ ...state, key: e.currentTarget.value })} />
            </label>
            <label class="editor-field editor-field--col">
                <span class="editor-label">Value (expression)</span>
                <CodeEditor
                    language="expression"
                    value={String(state.value || '')}
                    onChange={v => set({ ...state, value: v })}
                    rows={2}
                    placeholder="health + food"
                    status={setCustomValidator.status}
                />
            </label>
        {:else if state.type === ActionType.move}
            <label class="editor-field editor-field--col">
                <span class="editor-label">Address (expression)</span>
                <CodeEditor
                    language="expression"
                    value={String(state.address || '')}
                    onChange={v => set({ ...state, address: v })}
                    rows={1}
                    placeholder='"play.example.com" or "host:port"'
                    status={moveAddressValidator.status}
                />
            </label>
        {:else if state.type === ActionType.sequence}
            <div class="editor-hint">Actions run in order.</div>
            {#each (state.actions as Record<string, unknown>[]) || [] as act, i (i)}
                <div class="act-seq-item">
                    <div class="act-seq-head">
                        <span class="editor-label">Step {i + 1}</span>
                        <button type="button" class="ghost sm" onclick={() => removeSequenceAction(i)}>Remove</button>
                    </div>
                    <ActionEditor value={act} onChange={v => updateSequenceAction(i, v)} />
                </div>
            {/each}
            <button type="button" class="ghost sm" onclick={addSequenceAction}>+ Add step</button>
        {/if}
    </div>
</div>
