<script lang="ts">
    let {
        on = false,
        onchange,
    }: {
        on?: boolean;
        onchange?: (next: boolean) => void;
    } = $props();

    function toggle(): void {
        onchange?.(!on);
    }

</script>

<button
    type="button"
    role="switch"
    aria-label="Toggle"
    aria-checked={on}
    class="toggle"
    class:on
    onclick={toggle}
></button>
