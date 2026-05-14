// MQL — boolean/comparison layer on top of expression.js.

import * as expr from './expression.ts';
import { escapeHtml } from './util.ts';

export const loadSchema = expr.loadSchema;

const span = (kind, text) => `<span class="t-${kind}">${escapeHtml(text)}</span>`;

/// Tokenize `src` with `lang` (an mql/expression module) and emit highlighted HTML spans.
/// When `errorAt` is an integer offset, the single character at that position is marked.
export function renderTokens(lang, src, errorAt, schema = null) {
    const marked = Number.isInteger(errorAt);
    const tokens = lang.tokenize(src, schema);
    let out = '';
    for (const t of tokens) {
        if (!marked || errorAt < t.start || errorAt >= t.end) { out += span(t.kind, t.text); continue; }
        const i = errorAt - t.start;
        if (i > 0)            out += span(t.kind, t.text.slice(0, i));
        out += span('error',  t.text.slice(i, i + 1));
        if (i + 1 < t.text.length) out += span(t.kind, t.text.slice(i + 1));
    }
    if (marked && errorAt >= src.length) out += span('error', ' ');
    return out;
}

export type Status =
    | { kind: 'ok' | 'dim'; message: string; position?: number | null }
    | { kind: 'error'; message: string; position: number | null };

/// Extract the character offset of a syntax error from a backend error message of the
/// shape `"… at N …"`. Returns null if no position can be parsed.
export function errorPos(message: string | null | undefined): number | null {
    const m = / at (\d+)\b/.exec(message ?? '');
    return m ? Number(m[1]) : null;
}

/// Convert a caught error into the shared `Status` shape used by the editor status bar.
export function mqlError(e: unknown, fallback = 'invalid expression'): Status {
    const msg = (e as Error)?.message || fallback;
    return { kind: 'error', message: msg, position: errorPos(msg) };
}

export function tokenize(src, schema = null) {
    const keywords = new Set(expr.operatorNames(schema, 'keyword', 'logical'));
    const tokens = expr.tokenize(src);
    for (const t of tokens) {
        if ((t.kind === 'root' || t.kind === 'function') && keywords.has(t.text)) {
            t.kind = 'keyword';
        }
    }
    return tokens;
}

const isKeyword = keywords => t => {
    return t.kind === 'keyword' || (t.kind === 'root' && keywords.has(t.text));
};

function isCmpClosed(tokens, fromIdx, schema, cmpOpKws, cmpBoundary) {
    const arithOps = new Set(expr.operatorNames(schema, 'arithmetic'));
    let depth = 0;
    for (let i = fromIdx - 1; i >= 0; i--) {
        const t = tokens[i];
        if (t.kind === 'ws') continue;
        if (t.kind === 'paren') {
            if (t.text === ')') { depth++; continue; }
            if (depth === 0) return false;
            depth--; continue;
        }
        if (depth > 0) continue;
        if (t.kind === 'comma') return false;
        if (t.kind === 'keyword' && cmpBoundary.has(t.text)) return false;
        if (t.kind === 'op' && !arithOps.has(t.text)) return true;
        if (t.kind === 'keyword' && cmpOpKws.has(t.text)) return true;
    }
    return false;
}

export function complete(src, caret, schema) {
    if (expr.isInString(src, caret)) return [];
    schema = expr.schemaOrDefault(schema);
    const cmpOpKws = new Set(expr.operatorNames(schema, 'keyword'));
    const cmpBoundary = new Set(expr.operatorNames(schema, 'logical'));
    const keywords = new Set([...cmpOpKws, ...cmpBoundary]);
    const valueStart = new Set([...cmpOpKws, ...cmpBoundary]);
    const tokens = tokenize(src, schema);
    const ctx = expr.contextAt(tokens, caret, {
        isKeyword: isKeyword(keywords),
        valueStartKw: valueStart,
        cmpBoundaryKw: cmpBoundary,
    });

    const out = [];
    if (ctx.wants === 'value') {
        for (const f of schema.fields)    out.push({ label: f.name, kind: 'field',    insert: f.name,       detail: f.detail || '' });
        for (const f of schema.functions) out.push({ label: f.name, kind: 'function', insert: f.name + '(', detail: f.detail || 'function' });
        const not = expr.operatorFor('not', schema);
        if (not) out.push({ label: 'not', kind: 'keyword', insert: 'not ', detail: not.detail || '' });
        for (const l of schema.literals) out.push({ label: l, kind: 'literal', insert: l });
    } else if (ctx.wants === 'path') {
        out.push({ label: '(any nbt key)', kind: 'hint', insert: '', detail: 'NBT / server-data sub-key' });
    } else if (ctx.wants === 'transform') {
        for (const f of schema.functions.filter(f => f.pipe)) {
            out.push({ label: f.name, kind: 'transform', insert: f.name, detail: f.detail || 'transform' });
        }
    } else if (ctx.wants === 'op') {
        const detail = name => expr.operatorFor(name, schema)?.detail || '';
        if (!isCmpClosed(tokens, ctx.prevIdx, schema, cmpOpKws, cmpBoundary)) {
            for (const o of expr.operatorNames(schema, 'comparison')) {
                out.push({ label: o, kind: 'op', insert: o + ' ', detail: detail(o) });
            }
            for (const k of expr.operatorNames(schema, 'keyword')) {
                out.push({ label: k, kind: 'keyword', insert: k + ' ', detail: detail(k) });
            }
        }
        expr.appendArithAndPipeOps(out, schema);
        for (const k of expr.operatorNames(schema, 'logical').filter(k => k !== 'not')) {
            out.push({ label: k, kind: 'keyword', insert: k + ' ', detail: detail(k) });
        }
    }

    return expr.finalize(out, ctx);
}
