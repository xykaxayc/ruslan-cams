import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
class Events {
    callbacks = {};
    addEventListener(name, fn) { (this.callbacks[name] ??= []).push(fn); }
    emit(name) { for (const fn of this.callbacks[name] ?? []) fn(); }
}
class FakeSourceBuffer extends Events {
    updating = false;
    ranges = [];
    get buffered() {
        const ranges = this.ranges;
        return {length: ranges.length, start: i => ranges[i][0], end: i => ranges[i][1]};
    }
    appendBuffer() {}
    remove() {}
}
class FakeMediaSource extends Events {
    static isTypeSupported() { return true; }
    sb = new FakeSourceBuffer();
    addSourceBuffer() { return this.sb; }
    setLiveSeekableRange() {}
}
globalThis.HTMLElement = class {};
globalThis.WebSocket = {CLOSED: 3};
globalThis.window = {};
globalThis.MediaSource = FakeMediaSource;
globalThis.URL.createObjectURL = () => 'blob:test';
globalThis.URL.revokeObjectURL = () => {};
const source = await readFile(new URL('../www/video-rtc.js', import.meta.url), 'utf8');
const {VideoRTC} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

function fixture(target) {
    const stream = new VideoRTC();
    const video = new Events();
    Object.assign(video, {currentTime: 0, paused: true, playbackRate: 1, muted: true,
        pause() { this.paused = true; }, play() { this.paused = false; return Promise.resolve(); }});
    stream.video = video;
    stream.bufferSeconds = target;
    stream.send = () => {};
    stream.onmessage = {};
    let ms;
    globalThis.MediaSource = class extends FakeMediaSource { constructor() { super(); ms = this; } };
    stream.onmse();
    stream.onmessage.mse({type: 'mse', value: 'video/mp4; codecs="avc1.640029"'});
    function update(start, end) { ms.sb.ranges = [[start, end]]; ms.sb.emit('updateend'); }
    return {stream, video, update};
}
for (const target of [2, 4, 10]) {
    const {video, update} = fixture(target);
    update(0, target - 0.5);
    assert.equal(video.paused, true, `${target}s: must accumulate buffer before playing`);
    update(0, target + 0.1);
    assert.equal(video.paused, false, `${target}s: must play once enough data arrives`);
    assert.equal(video.currentTime, 0, 'must not seek to live edge while accumulating');
    assert.ok(video.playbackRate >= .95 && video.playbackRate <= 1.05, 'bounded playback correction');
    video.currentTime = target;
    video.emit('waiting');
    update(0, target + 0.2);
    assert.equal(video.paused, true, 'must rebuffer after underrun');
    update(0, target * 2 + .2);
    assert.equal(video.paused, false, 'must resume when reserve is restored');
    assert.equal(video.currentTime, target, 'must resume without skipping video');
}
console.log('PASS: 2/4/10s startup reserve, bounded speed, underrun and resume');
let VideoStream;
globalThis.customElements = {define(name, cls) { VideoStream = cls; }};
const wrapper = (await readFile(new URL('../www/video-stream.js', import.meta.url), 'utf8'))
    .replace(/^import .*;$/m, `import {VideoRTC} from 'data:text/javascript;base64,${Buffer.from(source).toString('base64')}';`);
await import('data:text/javascript;base64,' + Buffer.from(wrapper).toString('base64'));
const stream = new VideoStream();
stream.mode = '';
let closed = false;
stream.ws = new Events();
stream.ws.close = () => { closed = true; };
const mode = {innerText: 'loading'}, status = {innerText: ''};
stream.querySelector = selector => selector === '.mode' ? mode : status;
stream.onopen();
stream.onmessage.stream({type: 'error', value: 'mse: temporary RTSP timeout'});
assert.equal(closed, true, 'temporary source failure must trigger reconnect, not leave a permanently open idle socket');
console.log('PASS: source error triggers reconnect');
