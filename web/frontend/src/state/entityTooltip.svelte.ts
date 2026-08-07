import type { EntityLike } from '../lib/types.ts';

type EntityTooltip = {
    x: number;
    y: number;
    entity: EntityLike;
};

/// Imperative entity tooltip — usable from non-Svelte rendering paths (e.g. the imperative
/// Minimap markers). The `EntityTooltipHost` component reads `state` and renders the floating
/// element.
class EntityTooltipState {
    state = $state<EntityTooltip | null>(null);

    show(entity: EntityLike, event: Pick<PointerEvent, 'clientX' | 'clientY'>): void {
        this.state = { x: event.clientX, y: event.clientY, entity };
    }
    move(event: Pick<PointerEvent, 'clientX' | 'clientY'>): void {
        const cur = this.state;
        if (cur) this.state = { ...cur, x: event.clientX, y: event.clientY };
    }
    hide(): void { this.state = null; }
}

export const entityTooltip = new EntityTooltipState();

export const showEntityTooltip = (entity: EntityLike, event: PointerEvent) => entityTooltip.show(entity, event);
export const moveEntityTooltip = (event: PointerEvent) => entityTooltip.move(event);
export const hideEntityTooltip = () => entityTooltip.hide();
