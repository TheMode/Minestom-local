/// Shared context-menu state. Any component can call `openContextMenu(event, items)` to
/// surface a floating menu at the cursor; the singleton `<ContextMenuHost />` mounted at
/// the app root reads this state and does the rendering. Items are evaluated *once* at
/// open time — reopen the menu if the underlying state has shifted.

export type ContextMenuItem =
    | {
        kind: 'item';
        label: string;
        /// Short character/glyph shown in the leading rail (e.g. `⊕`, `⊖`, `⌕`).
        icon?: string;
        /// Optional secondary text shown right-aligned in muted ink — keyboard hint, count, etc.
        hint?: string;
        /// Reserve the leading rail for an accent stripe (e.g. include = green, exclude = red).
        tone?: 'accent' | 'danger' | 'warn';
        /// Marks the item as the currently-applied option (renders a check / fill stripe).
        active?: boolean;
        disabled?: boolean;
        onSelect: () => void;
      }
    | { kind: 'separator' }
    | { kind: 'heading'; label: string };

type MenuState = {
    x: number;
    y: number;
    items: ContextMenuItem[];
};

class ContextMenu {
    state = $state<MenuState | null>(null);

    open(event: { clientX: number; clientY: number; preventDefault?: () => void }, items: ContextMenuItem[]): void {
        event.preventDefault?.();
        if (!items.length) return;
        this.state = { x: event.clientX, y: event.clientY, items };
    }

    close(): void {
        this.state = null;
    }
}

export const contextMenu = new ContextMenu();

export const openContextMenu = (
    event: { clientX: number; clientY: number; preventDefault?: () => void },
    items: ContextMenuItem[],
) => contextMenu.open(event, items);

export const closeContextMenu = () => contextMenu.close();
