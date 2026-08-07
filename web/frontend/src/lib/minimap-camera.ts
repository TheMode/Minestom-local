// Shared world <-> screen transforms for terrain (WebGPU) and DOM markers.

const TILE_SIZE = 16;
const TILE_BYTES = TILE_SIZE * TILE_SIZE * 4;
const INSTANCE_FLOATS = 4;
const MAX_RENDERED_TILES = 4096;
const GPU_TEXTURE_COPY_DST = 0x02;
const GPU_TEXTURE_BINDING = 0x04;
const GPU_BUFFER_COPY_DST = 0x08;
const GPU_BUFFER_VERTEX = 0x20;
const GPU_BUFFER_UNIFORM = 0x40;

const SHADER = `
struct Camera {
  center: vec2f,
  viewport: vec2f,
  zoom: f32,
  cosRot: f32,
  sinRot: f32,
};

struct VertexOut {
  @builtin(position) position: vec4f,
  @location(0) @interpolate(flat) chunk: vec2i,
  @location(1) @interpolate(flat) layer: i32,
  @location(2) world: vec2f,
};

@group(0) @binding(0) var<uniform> camera: Camera;
@group(0) @binding(1) var tileTexture: texture_2d_array<f32>;

@vertex
fn vs_main(
  @location(0) corner: vec2f,
  @location(1) instance: vec4f,
) -> VertexOut {
  let world = vec2f(instance.x * 16.0 + corner.x * 16.0, instance.y * 16.0 + corner.y * 16.0);
  let delta = world - camera.center;
  let rotated = vec2f(
    camera.cosRot * delta.x - camera.sinRot * delta.y,
    camera.sinRot * delta.x + camera.cosRot * delta.y,
  );
  let screen = camera.viewport * 0.5 + rotated / camera.zoom;

  var out: VertexOut;
  out.position = vec4f(screen.x / camera.viewport.x * 2.0 - 1.0,
                       1.0 - screen.y / camera.viewport.y * 2.0,
                       0.0,
                       1.0);
  out.chunk = vec2i(i32(instance.x), i32(instance.y));
  out.layer = i32(instance.z);
  out.world = world;
  return out;
}

@fragment
fn fs_main(in: VertexOut) -> @location(0) vec4f {
  let block = vec2i(floor(in.world));
  let local = block - in.chunk * 16;
  if (any(local < vec2i(0, 0)) || any(local >= vec2i(16, 16))) {
    discard;
  }
  return textureLoad(tileTexture, local, in.layer, 0);
}
`;

type TileEntry = { rgba: Uint8Array; layer: number };

/// View transform: world blocks -> CSS pixels. One instance per frame.
/// cos/sin of `rotation` are cached so per-projection calls don't re-trig.
export class MinimapCamera {
    readonly cosRot: number;
    readonly sinRot: number;
    readonly invZoom: number;
    /// Reusable scratch for `projectOffset` so per-entity hot loops stay allocation-free.
    private readonly _scratch: [number, number] = [0, 0];

    constructor(
        public width: number,
        public height: number,
        public centerX: number,
        public centerZ: number,
        public zoom: number,
        public rotation: number,
    ) {
        this.cosRot = Math.cos(rotation);
        this.sinRot = Math.sin(rotation);
        this.invZoom = 1 / zoom;
    }

    /// Offset from viewport center in CSS pixels, written into a per-camera scratch tuple.
    /// The same array is returned every call — copy out before the next call. Used by the
    /// entity draw/hit-test loops where allocating a tuple per entity would churn the GC.
    projectOffset(wx: number, wz: number): [number, number] {
        const dx = wx - this.centerX, dz = wz - this.centerZ;
        this._scratch[0] = (this.cosRot * dx - this.sinRot * dz) * this.invZoom;
        this._scratch[1] = (this.sinRot * dx + this.cosRot * dz) * this.invZoom;
        return this._scratch;
    }

    /** Offset from viewport center in CSS pixels (for `translate` on centered elements). */
    worldToOffset(wx: number, wz: number): [number, number] {
        const o = this.projectOffset(wx, wz);
        return [o[0], o[1]];
    }

    /** Absolute position in viewport CSS pixels (top-left origin). */
    worldToScreen(wx: number, wz: number): [number, number] {
        const dx = wx - this.centerX, dz = wz - this.centerZ;
        const inv = 1 / this.zoom;
        return [
            this.width / 2 + (this.cosRot * dx - this.sinRot * dz) * inv,
            this.height / 2 + (this.sinRot * dx + this.cosRot * dz) * inv,
        ];
    }

    screenToWorld(px: number, py: number): [number, number] {
        const sx = (px - this.width / 2) * this.zoom;
        const sy = (py - this.height / 2) * this.zoom;
        return [
            this.centerX + this.cosRot * sx + this.sinRot * sy,
            this.centerZ - this.sinRot * sx + this.cosRot * sy,
        ];
    }

    /** Screen drag (CSS px) -> world-space pan delta. */
    screenPanDelta(dx: number, dy: number): [number, number] {
        // cos(-r)=cos(r), sin(-r)=-sin(r)
        const c = this.cosRot, s = -this.sinRot;
        const wx = -dx * this.zoom;
        const wz = -dy * this.zoom;
        return [c * wx - s * wz, s * wx + c * wz];
    }
}

/** Draws pre-rasterized 16x16 chunk tiles with WebGPU. */
export class MinimapTerrain {
    private device: GPUDevice;
    private context: GPUCanvasContext;
    private format: GPUTextureFormat;
    private pipeline: GPURenderPipeline;
    private bindGroup: GPUBindGroup;
    private tileTexture: GPUTexture;
    private vertexBuffer: GPUBuffer;
    private cameraBuffer: GPUBuffer;
    private instanceBuffer: GPUBuffer;
    private instanceCapacity = 0;
    private layerCapacity: number;
    private freeLayers: number[] = [];
    private tiles = new Map<string, TileEntry>();
    private configuredWidth = 0;
    private configuredHeight = 0;

    private constructor(
        private canvas: HTMLCanvasElement,
        device: GPUDevice,
        layerCapacity: number,
    ) {
        const context = canvas.getContext('webgpu') as GPUCanvasContext | null;
        if (!context) throw new Error('WebGPU canvas context unavailable');

        this.device = device;
        this.context = context;
        this.format = navigator.gpu.getPreferredCanvasFormat();
        this.layerCapacity = layerCapacity;
        this.freeLayers = Array.from({ length: this.layerCapacity }, (_, i) => this.layerCapacity - 1 - i);

        const module = device.createShaderModule({ code: SHADER });
        this.pipeline = device.createRenderPipeline({
            layout: 'auto',
            vertex: {
                module,
                entryPoint: 'vs_main',
                buffers: [
                    {
                        arrayStride: 8,
                        stepMode: 'vertex',
                        attributes: [{ shaderLocation: 0, offset: 0, format: 'float32x2' }],
                    },
                    {
                        arrayStride: INSTANCE_FLOATS * 4,
                        stepMode: 'instance',
                        attributes: [{ shaderLocation: 1, offset: 0, format: 'float32x4' }],
                    },
                ],
            },
            fragment: {
                module,
                entryPoint: 'fs_main',
                targets: [{ format: this.format }],
            },
            primitive: { topology: 'triangle-list' },
        });

        this.tileTexture = device.createTexture({
            size: [TILE_SIZE, TILE_SIZE, this.layerCapacity],
            format: 'rgba8unorm',
            usage: GPU_TEXTURE_BINDING | GPU_TEXTURE_COPY_DST,
        });
        this.cameraBuffer = device.createBuffer({
            size: 32,
            usage: GPU_BUFFER_UNIFORM | GPU_BUFFER_COPY_DST,
        });
        this.vertexBuffer = device.createBuffer({
            size: 6 * 2 * 4,
            usage: GPU_BUFFER_VERTEX | GPU_BUFFER_COPY_DST,
        });
        device.queue.writeBuffer(this.vertexBuffer, 0, new Float32Array([
            0, 0, 1, 0, 1, 1,
            0, 0, 1, 1, 0, 1,
        ]));
        this.instanceBuffer = device.createBuffer({
            size: INSTANCE_FLOATS * 4,
            usage: GPU_BUFFER_VERTEX | GPU_BUFFER_COPY_DST,
        });
        this.bindGroup = device.createBindGroup({
            layout: this.pipeline.getBindGroupLayout(0),
            entries: [
                { binding: 0, resource: { buffer: this.cameraBuffer } },
                { binding: 1, resource: this.tileTexture.createView({ dimension: '2d-array' }) },
            ],
        });
    }

    static async create(canvas: HTMLCanvasElement): Promise<MinimapTerrain> {
        const gpu = navigator.gpu;
        if (!gpu) throw new Error('WebGPU unavailable');
        const adapter = await gpu.requestAdapter({ powerPreference: 'low-power' });
        if (!adapter) throw new Error('WebGPU adapter unavailable');
        const layerCapacity = Math.min(MAX_RENDERED_TILES, adapter.limits.maxTextureArrayLayers);
        const device = await adapter.requestDevice({
            requiredLimits: { maxTextureArrayLayers: layerCapacity },
        });
        return new MinimapTerrain(canvas, device, layerCapacity);
    }

    private static tileKey(cx: number, cz: number) {
        return cx + ',' + cz;
    }

    setTile(cx: number, cz: number, rgba: Uint8Array) {
        const k = MinimapTerrain.tileKey(cx, cz);
        let entry = this.tiles.get(k);
        if (!entry) {
            entry = { rgba: new Uint8Array(TILE_BYTES), layer: -1 };
            this.tiles.set(k, entry);
        }
        entry.rgba.fill(0);
        entry.rgba.set(rgba.length >= TILE_BYTES ? rgba.subarray(0, TILE_BYTES) : rgba);
        if (entry.layer >= 0) this.uploadLayer(entry.layer, entry.rgba);
    }

    removeTile(cx: number, cz: number) {
        const entry = this.tiles.get(MinimapTerrain.tileKey(cx, cz));
        if (!entry) return;
        this.releaseLayer(entry);
        this.tiles.delete(MinimapTerrain.tileKey(cx, cz));
    }

    clear() {
        this.tiles.clear();
        this.freeLayers = Array.from({ length: this.layerCapacity }, (_, i) => this.layerCapacity - 1 - i);
    }

    render(camera: MinimapCamera) {
        const instances = this.collectVisibleInstances(camera);
        this.configureCanvas(camera.width, camera.height);
        this.writeCamera(camera);

        const encoder = this.device.createCommandEncoder();
        const pass = encoder.beginRenderPass({
            colorAttachments: [{
                view: this.context.getCurrentTexture().createView(),
                clearValue: { r: 16 / 255, g: 20 / 255, b: 24 / 255, a: 1 },
                loadOp: 'clear',
                storeOp: 'store',
            }],
        });
        if (instances.length > 0) {
            this.ensureInstanceCapacity(instances.length / INSTANCE_FLOATS);
            this.device.queue.writeBuffer(this.instanceBuffer, 0, instances);
            pass.setPipeline(this.pipeline);
            pass.setBindGroup(0, this.bindGroup);
            pass.setVertexBuffer(0, this.vertexBuffer);
            pass.setVertexBuffer(1, this.instanceBuffer);
            pass.draw(6, instances.length / INSTANCE_FLOATS);
        }
        pass.end();
        this.device.queue.submit([encoder.finish()]);
    }

    dispose() {
        this.tiles.clear();
        this.tileTexture.destroy();
        this.vertexBuffer.destroy();
        this.cameraBuffer.destroy();
        this.instanceBuffer.destroy();
        this.device.destroy();
    }

    private collectVisibleInstances(camera: MinimapCamera): Float32Array {
        const step = TILE_SIZE;
        const halfDiag = Math.SQRT2 * Math.max(camera.width, camera.height) * camera.zoom / 2 + step;
        const minX = Math.floor((camera.centerX - halfDiag) / step);
        const maxX = Math.ceil((camera.centerX + halfDiag) / step);
        const minZ = Math.floor((camera.centerZ - halfDiag) / step);
        const maxZ = Math.ceil((camera.centerZ + halfDiag) / step);
        const visible = new Set<string>();
        const rows: number[] = [];

        for (let cz = minZ; cz <= maxZ && rows.length < this.layerCapacity * INSTANCE_FLOATS; cz++) {
            for (let cx = minX; cx <= maxX && rows.length < this.layerCapacity * INSTANCE_FLOATS; cx++) {
                const key = MinimapTerrain.tileKey(cx, cz);
                const entry = this.tiles.get(key);
                if (!entry) continue;
                visible.add(key);
                if (!this.ensureLayer(entry)) continue;
                rows.push(cx, cz, entry.layer, 0);
            }
        }
        for (const [key, entry] of this.tiles) {
            if (entry.layer >= 0 && !visible.has(key)) this.releaseLayer(entry);
        }
        return new Float32Array(rows);
    }

    private ensureLayer(entry: TileEntry): boolean {
        if (entry.layer >= 0) return true;
        const layer = this.freeLayers.pop();
        if (layer === undefined) return false;
        entry.layer = layer;
        this.uploadLayer(layer, entry.rgba);
        return true;
    }

    private releaseLayer(entry: TileEntry) {
        if (entry.layer < 0) return;
        this.freeLayers.push(entry.layer);
        entry.layer = -1;
    }

    private uploadLayer(layer: number, rgba: Uint8Array) {
        this.device.queue.writeTexture(
            { texture: this.tileTexture, origin: [0, 0, layer] },
            rgba,
            { bytesPerRow: TILE_SIZE * 4, rowsPerImage: TILE_SIZE },
            [TILE_SIZE, TILE_SIZE, 1],
        );
    }

    private configureCanvas(cssW: number, cssH: number) {
        const dpr = window.devicePixelRatio || 1;
        const width = Math.max(1, Math.floor(cssW * dpr));
        const height = Math.max(1, Math.floor(cssH * dpr));
        if (this.configuredWidth === width && this.configuredHeight === height) return;
        this.canvas.width = width;
        this.canvas.height = height;
        this.context.configure({
            device: this.device,
            format: this.format,
            alphaMode: 'opaque',
        });
        this.configuredWidth = width;
        this.configuredHeight = height;
    }

    private writeCamera(camera: MinimapCamera) {
        const rot = camera.rotation;
        this.device.queue.writeBuffer(this.cameraBuffer, 0, new Float32Array([
            camera.centerX, camera.centerZ,
            camera.width, camera.height,
            camera.zoom, Math.cos(rot), Math.sin(rot),
        ]));
    }

    private ensureInstanceCapacity(count: number) {
        if (count <= this.instanceCapacity) return;
        this.instanceBuffer.destroy();
        this.instanceCapacity = Math.max(64, 1 << Math.ceil(Math.log2(count)));
        this.instanceBuffer = this.device.createBuffer({
            size: this.instanceCapacity * INSTANCE_FLOATS * 4,
            usage: GPU_BUFFER_VERTEX | GPU_BUFFER_COPY_DST,
        });
    }
}

export function decodeTileBase64(b64: string): Uint8Array {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}
