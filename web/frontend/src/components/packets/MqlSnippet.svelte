<script lang="ts">
    import * as mql from '../../lib/mql.ts';
    import { renderTokens } from '../../lib/mql.ts';

    let { src } = $props();
    let schema = $state(null);

    $effect(() => {
        let alive = true;
        mql.loadSchema().then(s => { if (alive) schema = s; });
        return () => { alive = false; };
    });

    const html = $derived(renderTokens(mql, src || '', null, schema));
</script>

<code class="mql-snippet">{@html html}</code>
