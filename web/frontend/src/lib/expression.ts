// Expression language tokenizer and completion helpers. Public metadata comes from
// the backend MQL constants endpoint.

import { api } from './api.ts';

export type MqlField = { name: string; detail?: string };
export type MqlFunction = { name: string; sig?: string; detail?: string; pipe?: boolean };
export type MqlOperator = { name: string; detail?: string; kind?: string };
export type MqlConstants = {
    fields: MqlField[];
    functions: MqlFunction[];
    operators: MqlOperator[];
    literals: string[];
};

const EMPTY_CONSTANTS: MqlConstants = {
    fields: [],
    functions: [],
    operators: [],
    literals: [],
};

let cached: MqlConstants | null = null;
let pending: Promise<MqlConstants> | null = null;

export const schemaOrDefault = (schema?: MqlConstants | null) => schema ?? cached ?? EMPTY_CONSTANTS;

const opDoc = (name: string, schema?: MqlConstants | null) =>
    operatorFor(name, schema)?.detail ?? '';
export const operatorFor = (name: string, schema?: MqlConstants | null) =>
    schemaOrDefault(schema).operators.find(o => o.name === name);
export const operatorNames = (schema: MqlConstants | null | undefined, ...kinds: string[]) =>
    schemaOrDefault(schema).operators.filter(o => kinds.includes(o.kind ?? '')).map(o => o.name);

export function appendArithAndPipeOps(out, schema?: MqlConstants | null) {
    for (const o of operatorNames(schema, 'arithmetic')) {
        out.push({ label: o, kind: 'op', insert: ' ' + o + ' ', detail: opDoc(o, schema) });
    }
    const pipe = operatorFor('|', schema);
    if (pipe) out.push({ label: '|', kind: 'op', insert: ' | ', detail: pipe.detail || '' });
}

export async function loadSchema() {
    if (cached) return cached;
    pending ??= api('/mql/constants')
        .then(raw => cached = normalizeConstants(raw))
        .catch(error => { pending = null; throw error; });
    return pending;
}

function normalizeConstants(raw: any): MqlConstants {
    const functions = array(raw?.functions).map(fn => typeof fn === 'string'
        ? { name: fn }
        : { name: String(fn.name), sig: fn.sig, detail: fn.detail, pipe: !!fn.pipe });
    return {
        fields: array(raw?.fields).map(field => typeof field === 'string'
            ? { name: field }
            : { name: String(field.name), detail: field.detail }),
        functions,
        operators: array(raw?.operators).map(op => typeof op === 'string'
            ? { name: op }
            : { name: String(op.name), detail: op.detail, kind: op.kind }),
        literals: array(raw?.literals).map(String),
    };
}

const array = (value: any) => Array.isArray(value) ? value : [];

// ---- Tokenizer ----

const TOKEN_RULES: Array<[string, RegExp]> = [
    ['ws',      /^\s+/],
    ['literal', /^(true|false)\b/],
    ['string',  /^"([^"\\]|\\.)*"?/],
    ['number',  /^\d+(\.\d+)?/],
    ['pipe',    /^\|/],
    ['op',      /^(!=|<=|>=|=|<|>|~|\+|-|\*|\/|%)/],
    ['paren',   /^[()]/],
    ['comma',   /^,/],
    ['dot',     /^\./],
    ['ident',   /^[A-Za-z_][A-Za-z_0-9]*/],
];

export function tokenize(src) {
    const tokens = [];
    outer: for (let i = 0; i < src.length; ) {
        const rest = src.slice(i);
        for (const [kind, re] of TOKEN_RULES) {
            const m = re.exec(rest);
            if (!m || !m[0]) continue;
            tokens.push({ kind, text: m[0], start: i, end: i + m[0].length });
            i += m[0].length;
            continue outer;
        }
        tokens.push({ kind: 'error', text: src[i], start: i, end: i + 1 });
        i++;
    }
    promotePaths(tokens);
    return tokens;
}

function promotePaths(tokens) {
    for (let j = 0; j < tokens.length; j++) {
        if (tokens[j].kind !== 'ident') continue;
        const next = tokens[j + 1];
        if (next?.kind === 'paren' && next.text === '(') { tokens[j].kind = 'function'; continue; }
        tokens[j].kind = 'root';
        for (let k = j + 1; tokens[k]?.kind === 'dot' && tokens[k + 1]?.kind === 'ident'; k += 2) {
            tokens[k + 1].kind = 'path';
        }
    }
}

// ---- Completion ----

const EDITING_KINDS   = new Set(['root', 'path', 'ident', 'function', 'literal']);
const VALUE_END_KINDS = new Set(['root', 'path', 'literal', 'number', 'string']);
const EMPTY = new Set();

export function isInString(src, caret) {
    let inStr = false;
    for (let i = 0; i < caret && i < src.length; i++) {
        const c = src[i];
        if (inStr && c === '\\' && i + 1 < caret) { i++; continue; }
        if (c === '"') inStr = !inStr;
    }
    return inStr;
}

export function complete(src, caret, schema) {
    if (isInString(src, caret)) return [];
    schema = schemaOrDefault(schema);
    const ctx = contextAt(tokenize(src), caret);
    const out = [];

    if (ctx.wants === 'value') {
        for (const f of schema.fields)    out.push({ label: f.name, kind: 'field',    insert: f.name,       detail: f.detail || '' });
        for (const f of schema.functions) out.push({ label: f.name, kind: 'function', insert: f.name + '(', detail: f.detail || 'function' });
        for (const l of schema.literals)  out.push({ label: l,      kind: 'literal',  insert: l });
    } else if (ctx.wants === 'path') {
        out.push({ label: '(any nbt key)', kind: 'hint', insert: '', detail: 'NBT / server-data sub-key' });
    } else if (ctx.wants === 'transform') {
        for (const f of schema.functions.filter(f => f.pipe)) {
            out.push({ label: f.name, kind: 'transform', insert: f.name, detail: f.detail || 'transform' });
        }
    } else if (ctx.wants === 'op') appendArithAndPipeOps(out, schema);

    return finalize(out, ctx);
}

export function finalize(items, ctx) {
    const lo = ctx.partial.toLowerCase();
    // No partial = no suggestions. Showing the full catalog the moment the caret crosses
    // whitespace is noisy and steals focus from typing. Users who want the full list can
    // press Ctrl/Cmd+Space which calls openPop() again with a non-empty partial after they
    // start typing.
    if (!lo) return [];

    const scored = items
        .map(c => {
            const ll = c.label.toLowerCase();
            const score = ll.startsWith(lo) ? 0 : ll.includes(lo) ? 1 : -1;
            return { ...c, score, range: ctx.range };
        })
        .filter(c => c.score >= 0);

    // If the only prefix-match is the partial itself, the user has already finished typing
    // a valid term — surface nothing rather than re-suggesting what they just wrote.
    const prefixHits = scored.filter(c => c.score === 0);
    if (prefixHits.length === 1 && prefixHits[0].label.toLowerCase() === lo) return [];

    return scored
        .sort((a, b) => a.score - b.score || a.label.localeCompare(b.label))
        .slice(0, 12);
}

export function contextAt(tokens, caret, opts: any = {}) {
    const { isKeyword = () => false, valueStartKw = EMPTY, cmpBoundaryKw = EMPTY } = opts;
    let cur = null, here = -1;
    for (let i = 0; i < tokens.length; i++) {
        if (tokens[i].start <= caret && caret <= tokens[i].end) { cur = tokens[i]; here = i; break; }
    }
    const editing = !!(cur && cur.start < caret && cur.end >= caret && EDITING_KINDS.has(cur.kind));
    const partial = editing ? cur.text.slice(0, caret - cur.start) : '';
    const range   = editing ? [cur.start, cur.end] : [caret, caret];
    let prevIdx = -1, prev = null;
    const from = editing ? here : (cur ? here + 1 : tokens.length);
    for (let i = from - 1; i >= 0; i--) if (tokens[i].kind !== 'ws') { prevIdx = i; prev = tokens[i]; break; }
    const base = { partial, range, prev, prevIdx };
    if (!prev)               return { ...base, wants: 'value' };
    if (prev.kind === 'dot') return { ...base, wants: 'path' };
    if (prev.kind === 'pipe') return { ...base, wants: 'transform' };
    if (prev.kind === 'op' || prev.kind === 'comma'
            || (prev.kind === 'paren' && prev.text === '(')
            || (isKeyword(prev) && (valueStartKw.has(prev.text) || cmpBoundaryKw.has(prev.text)))) {
        return { ...base, wants: 'value' };
    }
    if (VALUE_END_KINDS.has(prev.kind) || (prev.kind === 'paren' && prev.text === ')')) {
        return { ...base, wants: partial ? 'value' : 'op' };
    }
    return { ...base, wants: 'value' };
}
