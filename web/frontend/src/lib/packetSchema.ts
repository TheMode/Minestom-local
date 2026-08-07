import { api } from './api.ts';

export type Kind =
    | 'byte' | 'short' | 'int' | 'long'
    | 'float' | 'double'
    | 'boolean' | 'char' | 'string'
    | 'uuid' | 'enum' | 'record'
    | 'list' | 'map' | 'item' | 'component';

export type Field = {
    name: string;
    kind: Kind;
    values?: string[];
    components?: Field[];
    element?: Field;
    key?: Field;
    value?: Field;
};

export type Schema = {
    class: string;
    analyzable: boolean;
    components?: Field[];
};

const EXPRESSION_KINDS: ReadonlySet<Kind> = new Set([
    'byte', 'short', 'int', 'long', 'float', 'double', 'char', 'string', 'uuid',
]);

const BLOCK_KINDS: ReadonlySet<Kind> = new Set(['record', 'list', 'map', 'item', 'component']);

export function isExpressionKind(k: Kind): boolean { return EXPRESSION_KINDS.has(k); }

export function isBlockKind(k: Kind): boolean { return BLOCK_KINDS.has(k); }

export function kindLabel(f: Field): string {
    if (f.kind === 'list' && f.element) return `list<${f.element.kind}>`;
    if (f.kind === 'map' && f.key && f.value) return `map<${f.key.kind}, ${f.value.kind}>`;
    return f.kind;
}

/// Default value for a freshly-added field. Expression-kind fields default to the empty
/// string so the user sees an empty CodeEditor; the backend treats empty as null.
export function defaultFor(field: Field): unknown {
    if (isExpressionKind(field.kind)) return '';
    if (field.kind === 'boolean') return false;
    if (field.kind === 'enum') return field.values?.[0] ?? '';
    if (field.kind === 'record') {
        const o: Record<string, unknown> = {};
        for (const c of field.components ?? []) o[c.name] = defaultFor(c);
        return o;
    }
    if (field.kind === 'list') return [];
    if (field.kind === 'map') return {};
    if (field.kind === 'item') return { id: 'minecraft:stone', count: 1 };
    if (field.kind === 'component') return { text: '' };
    return '';
}

const cache = new Map<string, Promise<Schema>>();

/// Cached schema fetch — describe is a static reflection result, no need to re-hit the
/// server when the user toggles between packets.
export function fetchSchema(name: string): Promise<Schema> {
    if (!cache.has(name)) {
        cache.set(name, api<Schema>('/packet/describe/' + encodeURIComponent(name))
            .catch(e => { cache.delete(name); throw e; }));
    }
    return cache.get(name)!;
}
