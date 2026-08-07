import { api } from '../lib/api.ts';

/// Fetch a path once and expose the result as a reactive `{ data }`. Call from a component
/// `<script>` block — does not subscribe to topic updates.
export function fetchApi<T = unknown>(path: string | null): { data: T | null } {
    const state = $state<{ data: T | null }>({ data: null });
    if (path) {
        api<T>(path).then(data => { state.data = data; }).catch(() => {});
    }
    return state;
}
