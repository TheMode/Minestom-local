<script lang="ts">
    interface Props {
        onClose: () => void;
    }
    let { onClose }: Props = $props();
</script>

<div
    class="overlay-scrim overlay-scrim--center" role="button" tabindex="0"
    onclick={onClose}
    onkeydown={e => { if (e.key === 'Escape' || e.key === 'Enter') onClose(); }}
>
    <div
        class="float-panel float-panel--dialog pt-help" role="dialog" aria-modal="true" tabindex="-1"
        onclick={e => e.stopPropagation()}
        onkeydown={e => e.stopPropagation()}
    >
        <button class="close" type="button" onclick={onClose}>✕</button>
        <h2>PacketTrace — keys &amp; query language</h2>

        <h3>Navigation</h3>
        <table>
            <tbody>
                <tr><td class="k"><kbd>space</kbd></td><td class="v">pause / resume live capture</td></tr>
                <tr><td class="k"><kbd>←</kbd> / <kbd>→</kbd></td><td class="v">step prev / next packet (hold <kbd>shift</kbd> for ×10)</td></tr>
                <tr><td class="k"><kbd>j</kbd> / <kbd>k</kbd></td><td class="v">vim-style step (same as arrows)</td></tr>
                <tr><td class="k"><kbd>f</kbd></td><td class="v">jump to live tail</td></tr>
                <tr><td class="k"><kbd>B</kbd></td><td class="v">bookmark playhead</td></tr>
                <tr><td class="k"><kbd>C</kbd></td><td class="v">collapse / expand runs of same class</td></tr>
                <tr><td class="k"><kbd>/</kbd></td><td class="v">focus search</td></tr>
                <tr><td class="k"><kbd>esc</kbd></td><td class="v">deselect / close inspector</td></tr>
                <tr><td class="k">shift-click row</td><td class="v">add to multi-select — pick two to diff</td></tr>
                <tr><td class="k">right-click row</td><td class="v">toggle class filter (include → exclude → off)</td></tr>
                <tr><td class="k">click strip marker</td><td class="v">jump to that bookmark / lifecycle event</td></tr>
                <tr><td class="k">drag strip</td><td class="v">scrub timeline</td></tr>
            </tbody>
        </table>

        <h3>Filter DSL</h3>
        <p style:color="var(--ink-3)" style:margin="0 0 10px">
            Combine free text with key:value tokens. Prefix any token with <code>!</code> to negate.
        </p>
        <table>
            <tbody>
                <tr><td class="k"><code>class:Position</code></td><td class="v">class name contains "Position"</td></tr>
                <tr><td class="k"><code>dir:cb</code> · <code>dir:sb</code></td><td class="v">direction</td></tr>
                <tr><td class="k"><code>state:PLAY</code></td><td class="v">protocol phase</td></tr>
                <tr><td class="k"><code>group:ent</code></td><td class="v">subject group (self · ent · world · hud · win · net · chat)</td></tr>
                <tr><td class="k"><code>subject:Steve</code></td><td class="v">subject label contains "Steve"</td></tr>
                <tr><td class="k"><code>size:&gt;200</code></td><td class="v">size in bytes (also <code>&lt;</code> <code>&gt;=</code> <code>&lt;=</code> <code>=</code>)</td></tr>
                <tr><td class="k"><code>seq:&gt;5000</code></td><td class="v">filter by sequence</td></tr>
                <tr><td class="k"><code>has:bookmark</code></td><td class="v">only bookmarked packets</td></tr>
                <tr><td class="k"><code>!class:KeepAlive</code></td><td class="v">negate any token</td></tr>
            </tbody>
        </table>

        <h3>Inspector</h3>
        <p style:color="var(--ink-3)" style:margin="0">
            <b>Decoded</b> · pretty JSON, mutated fields highlighted vs prev same-class.<br />
            <b>Bytes</b> · hex dump with ascii column.<br />
            <b>Mutates</b> · which player-state fields this packet changes, with from→to.<br />
            <b>Related</b> · packets correlated by subject, class, or causal chain.<br />
            <b>Diff</b> · field-by-field against prior same class — or against another packet if two rows are shift-selected.
        </p>
    </div>
</div>
