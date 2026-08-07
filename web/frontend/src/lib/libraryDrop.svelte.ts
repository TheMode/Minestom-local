/// Reactive drag-drop helper for library targets. Call once in a component, spread
/// `handlers` on the drop-target element, read `over` (a boolean) to render the
/// drop-target highlight. The bucket may be passed as a literal or as a getter (for
/// list slots whose accepted bucket depends on the current element kind).
///
/// Per the HTML5 DnD spec, `DataTransfer#getData` returns `''` during dragenter/dragover,
/// so accept-time gating relies on `DataTransfer#types` (which exposes the MIME) and the
/// payload is only read in `drop`. See [packetLibrary] for the per-bucket MIME scheme.

import { hasLibraryDrag, readLibraryDrag, type LibraryBucket } from './packetLibrary.svelte.ts';

type BucketSource = LibraryBucket | null | (() => LibraryBucket | null);

export function libraryDrop(bucket: BucketSource, onAccept: (value: unknown) => void) {
    const state = $state({ over: false });
    let depth = 0;
    const resolve = (): LibraryBucket | null => typeof bucket === 'function' ? bucket() : bucket;

    function gate(e: DragEvent): boolean {
        const b = resolve();
        if (!b || !hasLibraryDrag(e.dataTransfer, b)) return false;
        e.preventDefault();
        if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
        return true;
    }

    return {
        get over() { return state.over; },
        handlers: {
            ondragenter: (e: DragEvent) => {
                if (!gate(e)) return;
                depth += 1;
                state.over = true;
            },
            ondragover: (e: DragEvent) => { gate(e); },
            ondragleave: () => {
                depth = Math.max(0, depth - 1);
                if (depth === 0) state.over = false;
            },
            ondrop: (e: DragEvent) => {
                const b = resolve();
                if (!b) return;
                const val = readLibraryDrag(e.dataTransfer, b);
                if (val == null) return;
                e.preventDefault();
                e.stopPropagation();
                depth = 0;
                state.over = false;
                onAccept(val);
            },
        },
    };
}
