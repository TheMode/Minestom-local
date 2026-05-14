<script module lang="ts">
    const DEFAULT_SKIN_URL = '/assets/textures/entity/player/wide/steve.png';

    const PARTS = {
        headBase:    [8, 8, 8, 8],
        headOverlay: [40, 8, 8, 8],
        body:        [20, 20, 8, 12],
        bodyOverlay: [20, 36, 8, 12],
        rArm:        [44, 20, 4, 12],
        rArmOverlay: [44, 36, 4, 12],
        lArm:        [36, 52, 4, 12],
        lArmOverlay: [52, 52, 4, 12],
        rLeg:        [4, 20, 4, 12],
        rLegOverlay: [4, 36, 4, 12],
        lLeg:        [20, 52, 4, 12],
        lLegOverlay: [4, 52, 4, 12],
    };

    function loadImage(url) {
        return new Promise((resolve, reject) => {
            if (!url) return reject(new Error('no url'));
            const img = new Image();
            img.crossOrigin = 'anonymous';
            img.onload = () => resolve(img);
            img.onerror = () => reject(new Error('load failed: ' + url));
            img.src = url;
        });
    }

    function upgradeLegacySheet(img) {
        const c = document.createElement('canvas');
        c.width = 64; c.height = 64;
        const ctx = c.getContext('2d');
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(img, 0, 0);
        const mirror = (sx, sy, sw, sh, dx, dy) => {
            ctx.save();
            ctx.translate(dx + sw, dy);
            ctx.scale(-1, 1);
            ctx.drawImage(c, sx, sy, sw, sh, 0, 0, sw, sh);
            ctx.restore();
        };
        mirror(44, 20, 4, 12, 36, 52);
        mirror(4, 20, 4, 12, 20, 52);
        return c;
    }

    function extractSkinUrl(props) {
        if (!props) return null;
        try {
            const candidate = props.textures ?? props['textures'];
            let value = candidate;
            if (candidate && typeof candidate === 'object') value = candidate.value ?? candidate.Value;
            if (!value || typeof value !== 'string') return null;
            const decoded = JSON.parse(atob(value));
            return decoded?.textures?.SKIN?.url || null;
        } catch { return null; }
    }

    export async function paintSkin(canvas, profileProperties) {
        if (!canvas) return;
        const url = extractSkinUrl(profileProperties) || DEFAULT_SKIN_URL;
        const img = await loadImage(url).catch(() => loadImage(DEFAULT_SKIN_URL).catch(() => null));
        if (!img) return;
        const sheet = img.naturalWidth === 64 && img.naturalHeight === 32 ? upgradeLegacySheet(img) : img;
        const native = document.createElement('canvas');
        native.width = 16; native.height = 32;
        const nctx = native.getContext('2d');
        nctx.imageSmoothingEnabled = false;
        const blit = ([sx, sy, sw, sh], dx, dy) => nctx.drawImage(sheet, sx, sy, sw, sh, dx, dy, sw, sh);
        blit(PARTS.headBase, 4, 0);  blit(PARTS.headOverlay, 4, 0);
        blit(PARTS.rArm, 0, 8);      blit(PARTS.rArmOverlay, 0, 8);
        blit(PARTS.body, 4, 8);      blit(PARTS.bodyOverlay, 4, 8);
        blit(PARTS.lArm, 12, 8);     blit(PARTS.lArmOverlay, 12, 8);
        blit(PARTS.rLeg, 4, 20);     blit(PARTS.rLegOverlay, 4, 20);
        blit(PARTS.lLeg, 8, 20);     blit(PARTS.lLegOverlay, 8, 20);

        const dpr = window.devicePixelRatio || 1;
        const cssW = canvas.clientWidth || 120;
        const cssH = canvas.clientHeight || 180;
        canvas.width = Math.round(cssW * dpr);
        canvas.height = Math.round(cssH * dpr);
        const ctx = canvas.getContext('2d');
        ctx.imageSmoothingEnabled = false;
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        const scale = Math.min(canvas.width / native.width, canvas.height / native.height);
        const drawW = native.width * scale, drawH = native.height * scale;
        ctx.drawImage(native, (canvas.width - drawW) / 2, (canvas.height - drawH) / 2, drawW, drawH);
    }
</script>

<script lang="ts">
    let { profileProperties, className = '', style = '' } = $props();
    let canvas;

    $effect(() => {
        paintSkin(canvas, profileProperties).catch(() => {});
    });
</script>

<canvas bind:this={canvas} class={className} {style}></canvas>
