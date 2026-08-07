<script lang="ts">
    import { mode } from '../state/mode.svelte.ts';
    import { navigate } from '../lib/nav.ts';

    let dragOver = $state(false);
    let uploading = $state(false);
    let error = $state<string | null>(null);
    let respectTimestamps = $state(true);
    let fileInput: HTMLInputElement;

    async function handleFile(file: File) {
        if (uploading) return;
        uploading = true;
        error = null;
        try {
            const buf = await file.arrayBuffer();
            await mode.uploadReplay(buf, file.name, respectTimestamps);
            navigate('/');
        } catch (e: any) {
            error = e?.message ?? String(e);
        } finally {
            uploading = false;
        }
    }

    function onDrop(e: DragEvent) {
        e.preventDefault();
        dragOver = false;
        const file = e.dataTransfer?.files?.[0];
        if (file) handleFile(file);
    }

    function onDragOver(e: DragEvent) {
        e.preventDefault();
        dragOver = true;
    }

    function onDragLeave(e: DragEvent) {
        // Don't flicker when crossing child elements — only un-highlight when the drag
        // truly leaves the drop zone container.
        if (e.currentTarget === e.target) dragOver = false;
    }

    function onPick() { fileInput.click(); }

    function onChange(e: Event) {
        const f = (e.target as HTMLInputElement).files?.[0];
        if (f) handleFile(f);
    }
</script>

<div class="landing">
    <div class="landing__head">
        <h1>Replay <em>a session</em></h1>
        <p class="dim">
            Drop a <code>sessions.sqlite</code> file produced by a live proxy run. The dashboard
            decodes its packet stream into players, lifecycle events, and minimaps — visible only
            in this browser tab.
        </p>
    </div>

    <div class="landing__zone {dragOver ? 'landing__zone--over' : ''} {uploading ? 'landing__zone--busy' : ''}"
         role="button"
         tabindex="0"
         ondragover={onDragOver}
         ondragleave={onDragLeave}
         ondrop={onDrop}
         onclick={onPick}
         onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') onPick(); }}>
        <div class="landing__icon">⇣</div>
        <div class="landing__title">
            {uploading ? 'Uploading & decoding…' : 'Drop a .sqlite file, or click to pick'}
        </div>
        <div class="landing__hint dim">
            Protocol version must match this build (v{mode.protocolVersion ?? '?'}).
        </div>
        <input type="file" accept=".sqlite,.db,application/vnd.sqlite3,application/octet-stream"
               bind:this={fileInput} onchange={onChange} hidden />
    </div>

    <label class="landing__option">
        <input type="checkbox" bind:checked={respectTimestamps} />
        <span>Respect recorded timestamps</span>
    </label>

    {#if error}
        <div class="landing__error">{error}</div>
    {/if}

    {#if mode.scope}
        <div class="landing__current">
            <span class="dim">Current scope:</span>
            <code>{mode.scope.label}</code>
            <a href="/dashboard" class="btn sm">Open dashboard →</a>
            <button class="btn sm ghost" onclick={() => mode.deleteCurrentScope()}>Drop scope</button>
        </div>
    {/if}
</div>

<style>
    @layer pages {
        :global {
    /* ---- Landing (replay upload) ----------------------------------- */
    .landing {
        max-width: 720px;
        margin: var(--pad-7) auto;
        padding: 0 var(--pad-5);
    }
    .landing__head h1 { margin: 0 0 var(--pad-2); }
    .landing__head p { margin: 0 0 var(--pad-5); line-height: 1.25; }
    .landing__zone {
        border: 2px dashed var(--line);
        padding: var(--pad-7) var(--pad-5);
        text-align: center;
        cursor: pointer;
        transition: border-color var(--motion), background var(--motion);
        background: var(--bg-1);
        box-shadow: var(--bevel);
    }
    .landing__zone:hover,
    .landing__zone--over {
        border-color: var(--acc-line);
        background: var(--acc-soft);
    }
    .landing__zone--busy { cursor: progress; opacity: 0.7; }
    .landing__icon {
        font-size: var(--t-2xl);
        line-height: 1;
        margin-bottom: var(--pad-3);
        color: var(--acc);
    }
    .landing__title { font-size: var(--t-md); margin-bottom: 6px; color: var(--ink); }
    .landing__option {
        display: inline-flex;
        align-items: center;
        gap: var(--pad-2);
        margin-top: var(--pad-3);
        font-size: var(--t-sm);
    }
    .landing__error {
        margin-top: var(--pad-5);
        padding: var(--pad-3) var(--pad-4);
        background: var(--danger-soft);
        color: var(--danger);
        font-size: var(--t-sm);
    }
    .landing__current {
        margin-top: var(--pad-5);
        display: flex;
        align-items: center;
        gap: var(--pad-3);
        font-size: var(--t-sm);
        flex-wrap: wrap;
    }
    .landing__current code {
        padding: 2px var(--pad-2);
        background: var(--bg-2);
        border: 1px solid var(--line);
        font-size: var(--t-xs);
    }
        }
    }
</style>
