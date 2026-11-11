import asyncio, sys
from playwright.async_api import async_playwright
async def main(secs, shot):
    async with async_playwright() as p:
        b = await p.chromium.launch(channel="chrome", headless=True, args=["--autoplay-policy=no-user-gesture-required"])
        pg = await b.new_page(viewport={"width":1600,"height":900})
        await pg.goto("http://127.0.0.1:8090/grid.html")
        for t in range(20, secs+1, 20):
            await pg.wait_for_timeout(20000)
            info=[]
            for f in pg.frames[1:]:
                try: info.append(await f.evaluate("(()=>{const v=document.querySelector('video');return v?[v.readyState,v.videoWidth,+v.currentTime.toFixed(1)]:'-'})()"))
                except Exception: info.append('err')
            print(t, info, flush=True)
        await pg.screenshot(path=shot); await b.close()
asyncio.run(main(int(sys.argv[1]), sys.argv[2]))
