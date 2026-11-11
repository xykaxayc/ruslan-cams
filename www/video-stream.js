import {VideoRTC} from './video-rtc.js?v=5';

class VideoStream extends VideoRTC {
    set loading(value) {
        const indicator = this.querySelector('.info');
        if (indicator) indicator.hidden = !value;
    }

    oninit() {
        super.oninit();
        this.video.autoplay = true;
        this.video.defaultMuted = true;
        this.video.muted = true;
        this.video.playsInline = true;
        this.video.setAttribute('autoplay', '');
        this.video.setAttribute('muted', '');
        this.video.setAttribute('playsinline', '');
        this.video.setAttribute('webkit-playsinline', '');
        this.video.controls = false;
        this.video.disablePictureInPicture = true;

        this.innerHTML = `
        <style>
        video-stream { position: relative; width: 100%; height: 100%; display: block; overflow: hidden; background: #000; }
        video { width: 100%; height: 100%; object-fit: cover; }
        img.mjpeg-live { width: 100%; height: 100%; object-fit: cover; position: absolute; inset: 0; display: block; }
        video::-webkit-media-controls,
        video::-webkit-media-controls-enclosure,
        video::-webkit-media-controls-panel,
        video::-webkit-media-controls-overlay-play-button,
        video::-webkit-media-controls-start-playback-button,
        video::-webkit-media-controls-play-button { display: none !important; -webkit-appearance: none !important; opacity: 0 !important; visibility: hidden !important; }
        .info {
            position: absolute;
            inset: 0;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 12px;
            background: rgba(0, 0, 0, .18);
            color: #ccc;
            font: 13px/1.4 -apple-system, system-ui, sans-serif;
            pointer-events: none;
        }
        .info[hidden] { display: none; }
        .loading-spinner {
            width: 28px;
            height: 28px;
            border: 2px solid rgba(255, 255, 255, .18);
            border-top-color: #ddd;
            border-radius: 50%;
            animation: camera-spin 1s linear infinite;
        }
        @keyframes camera-spin { to { transform: rotate(360deg); } }
        @media (prefers-reduced-motion: reduce) {
            .loading-spinner { animation: none; }
        }
        </style>
        <div class="info" role="status" aria-live="polite">
            <span class="loading-spinner" aria-hidden="true"></span>
            <span>Подключение…</span>
        </div>`;
        this.insertBefore(this.video, this.querySelector('.info'));

        const hide = () => {
            if (!this.video.paused && (this.video.currentTime > 0 || this.video.readyState >= 2)) {
                this.loading = false;
            }
        };
        this.video.addEventListener('playing', () => { this.loading = false; });
        this.video.addEventListener('play', () => { this.loading = false; });
        this.video.addEventListener('loadeddata', () => { this.loading = false; });
        this.video.addEventListener('load', () => { this.loading = false; });
        this.video.addEventListener('timeupdate', hide);

        let waitTimer = null;
        this.video.addEventListener('waiting', () => {
            if (!waitTimer) {
                waitTimer = setTimeout(() => {
                    waitTimer = null;
                    if (this.video.paused || this.video.readyState < 2) {
                        this.loading = true;
                    }
                }, 600);
            }
        });
        this.video.addEventListener('pause', () => {
            if (this.video.readyState < 2) {
                this.loading = true;
            }
        });
        this.video.addEventListener('error', () => { this.loading = true; });
        this.video.addEventListener('emptied', () => { this.loading = true; });
    }

    onconnect() {
        if (this._frameTimer) {
            clearInterval(this._frameTimer);
            this._frameTimer = null;
        }

        if (this.mode === 'mjpeg') {
            try {
                const rawUrl = this.wsURL || this.getAttribute('src') || '';
                const safeSrc = rawUrl.replace(/^wss?:/, 'http:');
                const url = new URL(safeSrc, location.href);
                const srcName = url.searchParams.get('src') || '';
                const token = url.searchParams.get('token') || 'ruslan2026';
                const proto = location.protocol;
                const host = location.host;

                let img = this.querySelector('img.mjpeg-live');
                if (!img) {
                    img = document.createElement('img');
                    img.className = 'mjpeg-live';
                    img.onload = () => { this.loading = false; };
                    this.insertBefore(img, this.querySelector('.info'));
                }
                img.style.display = 'block';
                if (this.video) this.video.style.display = 'none';

                const updateFrame = () => {
                    if (this.mode !== 'mjpeg' || !this.isConnected) return;
                    img.src = `${proto}//${host}/api/frame.jpeg?src=${encodeURIComponent(srcName)}&token=${encodeURIComponent(token)}&t=${Date.now()}`;
                };

                updateFrame();
                this._frameTimer = setInterval(updateFrame, 1000);
                return false;
            } catch (err) {
                console.warn('Snapshot fallback error:', err);
            }
        }

        // WebRTC / MSE mode for Fullscreen
        if (this.video) this.video.style.display = 'block';
        const img = this.querySelector('img.mjpeg-live');
        if (img) img.style.display = 'none';

        const result = super.onconnect();
        if (result) this.loading = true;
        return result;
    }

    onopen() {
        const result = super.onopen();
        this.onmessage['stream'] = msg => {
            if (msg.type === 'error') {
                this.loading = true;
                console.debug('Camera stream unavailable; retrying connection');
                if (this.ws) this.ws.close();
            }
        };
        return result;
    }

    onclose() {
        if (this._frameTimer) {
            clearInterval(this._frameTimer);
            this._frameTimer = null;
        }
        this.loading = true;
        return super.onclose();
    }

    disconnectedCallback() {
        if (this._frameTimer) {
            clearInterval(this._frameTimer);
            this._frameTimer = null;
        }
        if (super.disconnectedCallback) super.disconnectedCallback();
    }
}

customElements.define('video-stream', VideoStream);
