<script lang="ts">
    let { dependency, children } = $props();
    let el;

    $effect(() => {
        dependency;
        if (el) el.scrollTop = el.scrollHeight;
    });
</script>

<div class="chat-list" bind:this={el}>
    {@render children?.()}
</div>
