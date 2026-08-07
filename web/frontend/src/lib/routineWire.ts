/** Routine editor wire shape — must match `RoutineCodecs` on the backend. */

import { humanDuration, shortClass } from './util.ts';

export const TriggerType = {
    onMatch: 'onMatch',
    onUnmatch: 'onUnmatch',
    onPacket: 'onPacket',
    interval: 'interval',
} as const;

export const ActionType = {
    inject: 'inject',
    chat: 'chat',
    setCustom: 'setCustom',
    move: 'move',
    sequence: 'sequence',
    ref: 'ref',
} as const;

export type ActionRefWire = { type: typeof ActionType.ref; id: string };

export function isActionRef(action: { type?: string } | null | undefined): boolean {
    return action?.type === ActionType.ref;
}

export function actionRefId(action: { id?: string } | null | undefined): string {
    return action?.id ?? '';
}

const TRIGGER_ICON: Record<string, string> = {
    [TriggerType.onMatch]: '⊕',
    [TriggerType.onUnmatch]: '⊖',
    [TriggerType.onPacket]: '◇',
    [TriggerType.interval]: '◴',
};

export function triggerIcon(trigger: { type?: string } | null | undefined): string {
    return TRIGGER_ICON[trigger?.type ?? ''] ?? '·';
}

export function triggerLabel(trigger: { type?: string; packet?: string; millis?: number } | null | undefined): string {
    if (!trigger?.type) return TriggerType.onMatch;
    if (trigger.type === TriggerType.interval) return `every ${humanDuration(trigger.millis ?? 0)}`;
    if (trigger.type === TriggerType.onPacket) return `on ${shortClass(trigger.packet) || '(unset)'}`;
    return trigger.type;
}

const ACTION_ICON: Record<string, string> = {
    [ActionType.inject]: '◇',
    [ActionType.chat]: '#',
    [ActionType.setCustom]: '⚙',
    [ActionType.move]: '→',
    [ActionType.sequence]: '↳',
};

export function actionIcon(action: { type?: string } | null | undefined): string {
    return ACTION_ICON[action?.type ?? ''] ?? '·';
}
