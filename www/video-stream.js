import {VideoRTC} from './video-rtc.js?v=5';

class VideoStream extends VideoRTC {
    set loading(value) {
        const indicator = this.querySelector('.info');
        if (indicator) indicator.hidden = !value;
    }

    oninit() {
        super.oninit();
        this.video.controls = false;
        this.video.muted = true;
        this.video.disablePictureInPicture = true;

        this.innerHTML = `
        <style>
        video-stream { position: relative; }
        video::-webkit-media-controls,
        video::-webkit-media-controls-start-playback-button { display: none !important; }
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

        this.video.addEventListener('playing', () => { this.loading = false; });
        for (const event of ['waiting', 'emptied', 'error', 'pause']) {
            this.video.addEventListener(event, () => { this.loading = true; });
        }
        this.video.addEventListener('timeupdate', () => {
            if (!this.video.paused && this.video.readyState >= 3) this.loading = false;
        });
    }

    onconnect() {
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
                // Preserve VideoRTC's reconnect delay instead of leaving an idle socket.
                if (this.ws) this.ws.close();
            }
        };
        return result;
    }

    onclose() {
        this.loading = true;
        return super.onclose();
    }
}

customElements.define('video-stream', VideoStream);
