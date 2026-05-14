type RenderItem<T> = (item: T, index: number, selected: boolean) => string;
type Accept = (index: number) => void;

export class ComboboxPopover<T> {
    readonly el: HTMLUListElement;

    items: T[] = [];
    selected = 0;
    open = false;

    constructor(
        className: string,
        private readonly renderItem: RenderItem<T>,
        private readonly accept: Accept,
    ) {
        this.el = document.createElement('ul');
        this.el.className = `combobox-pop ${className}`;
        this.el.setAttribute('role', 'listbox');
        this.el.setAttribute('popover', 'manual');
        this.el.style.position = 'fixed';
        this.el.style.margin = '0';
    }

    mount(parent: Node = document.body): void {
        parent.appendChild(this.el);
    }

    destroy(): void {
        this.el.remove();
    }

    contains(target: EventTarget | null): boolean {
        return !!target && this.el.contains(target as Node);
    }

    setItems(items: T[], selected = 0): void {
        this.items = items;
        this.selected = selected;
        this.render();
    }

    show(): void {
        this.open = true;
        try { this.el.showPopover(); } catch {}
    }

    hide(): void {
        this.open = false;
        try { this.el.hidePopover(); } catch {}
    }

    setSelected(index: number): void {
        this.selected = Math.max(0, Math.min(this.items.length - 1, index));
        this.reflectSelection();
    }

    move(delta: number): void {
        this.setSelected(this.selected + delta);
        this.scrollSelectedIntoView();
    }

    ensureParent(parent: Node): void {
        if (this.el.parentNode !== parent) {
            this.hide();
            parent.appendChild(this.el);
        }
    }

    position(left: number, top: number, minWidth?: number): void {
        this.el.style.left = `${left}px`;
        this.el.style.top = `${top}px`;
        if (minWidth != null) this.el.style.minWidth = `${minWidth}px`;
    }

    handleKey(e: KeyboardEvent): boolean {
        if (!this.open) return false;
        if (e.key === 'ArrowDown') this.move(1);
        else if (e.key === 'ArrowUp') this.move(-1);
        else if (e.key === 'Enter' || e.key === 'Tab') this.accept(this.selected);
        else if (e.key === 'Escape') this.hide();
        else return false;
        e.preventDefault();
        return true;
    }

    render(): void {
        this.el.innerHTML = this.items.map((item, i) => this.renderItem(item, i, i === this.selected)).join('');
        this.el.querySelectorAll('li').forEach(li => {
            li.onmousedown = e => {
                e.preventDefault();
                this.accept(Number((li as HTMLElement).dataset.i));
            };
            li.onmouseenter = () => this.setSelected(Number((li as HTMLElement).dataset.i));
        });
    }

    private reflectSelection(): void {
        this.el.querySelectorAll('li').forEach((li, i) => {
            li.setAttribute('aria-selected', String(i === this.selected));
        });
    }

    private scrollSelectedIntoView(): void {
        const li = this.el.querySelectorAll('li')[this.selected] as HTMLElement | undefined;
        li?.scrollIntoView({ block: 'nearest' });
    }
}
