import type { PacketRow } from '../../lib/packetAgg.ts';

export type FacetMode = 'include' | 'exclude' | null;

export type StreamEntry =
    | { kind: 'row'; p: PacketRow; delta: number | null; bookmark?: { seq: number; label: string } }
    | { kind: 'group'; first: PacketRow; last: PacketRow; count: number; seqStart: number; seqEnd: number }
    | { kind: 'lifecycle'; seq: number; label: string };

export type Related = { row: PacketRow; dt: number; reason: 'Same subject' | 'Same class' };

export type Bookmark = { seq: number; label: string };

export type Breakpoint = {
    id: string;
    match: string;
    label: string;
    enabled: boolean;
    matchedSeqs?: number[];
    hitCount?: number;
};

export type Saved = { name: string; q: string };

export type SideTab = 'filters' | 'bookmarks' | 'breaks' | 'saved';
