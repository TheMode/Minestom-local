export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };

export type PacketTopicMessage = JsonObject & {
  topic?: string;
  event?: string;
  uuid?: string;
  player?: PlayerSummary;
};

/// The `metrics` topic + `/metrics/latest` — mirrors `ControlPacket.Metrics` on the backend.
export type ControlMetrics = JsonObject & {
  ts?: number;
  mspt?: number;
  tps?: number;
  processCpu?: number;
  heapUsed?: number;
  heapMax?: number;
  threadCount?: number;
  uptimeMs?: number;
  playerCount?: number;
};

/// The `server:metrics` topic + `/server` history — mirrors `MetricsSampler.Sample` on the backend.
export type ServerMetricsSample = JsonObject & {
  ts?: number;
  bytesIn?: number;
  bytesOut?: number;
  packetsIn?: number;
  packetsOut?: number;
  connections?: number;
};

export type ServerStatus = JsonObject & {
  history?: ServerMetricsSample[];
};

export type PlayerSummary = JsonObject & {
  uuid: string;
  connectionId?: string;
  journeyId?: string;
  username?: string;
  name?: string;
  /// `host:port` of the upstream this player is currently bridged to.
  backendAddress?: string;
  traffic: {
    compressionThreshold?: number;
    pingMs?: number;
    bytesIn?: number;
    bytesOut?: number;
    packetsIn?: number;
    packetsOut?: number;
    pingHistory?: number[];
  };
  connectedAt?: number;
  /// 0 (or undefined) while connected; ms timestamp once the player has disconnected.
  /// The backend retains disconnected sessions for a TTL window so profiles remain viewable.
  disconnectedAt?: number;
};

export type ToastKind = 'ok' | 'error' | 'warn' | 'info';

export type ToastItem = {
  id: number;
  message: string;
  kind: ToastKind;
  ttlMs: number;
};

export type EntityLike = JsonObject & {
  id?: string | number;
  type?: string;
  uuid?: string;
  name?: string;
};
