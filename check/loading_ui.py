"""Live browser check: missing source shows loading and preserves reconnects."""
import asyncio
from playwright.async_api import async_playwright

async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(channel='chrome',headless=True)
  page=await browser.new_page(viewport={'width':640,'height':360})
  await page.goto('http://127.0.0.1:8090/player.html?src=missing-camera-for-ui-check')
  await page.wait_for_selector('video')
  await page.wait_for_timeout(1500)
  assert await page.locator('video').evaluate('(v)=>v.controls') is False,'Native controls must be hidden'
  assert await page.locator('.info').is_visible(),'Loading indicator must be visible on source failure'
  assert await page.locator('.info').inner_text() == 'Подключение…','Technical errors must not appear in the camera cell'
  assert await page.locator('.loading-spinner').count() == 1,'Spinner must be shown'
  await page.locator('video').evaluate('(v)=>v.dispatchEvent(new Event("playing"))')
  assert not await page.locator('.info').is_visible(),'Indicator must disappear when playback begins'
  await page.locator('video').evaluate('(v)=>v.dispatchEvent(new Event("waiting"))')
  assert await page.locator('.info').is_visible(),'Indicator must return during buffering'
  await page.screenshot(path='/Users/andrejmakarenko/Documents/Codex/2026-11-09/new-chat-3/work/loading-ui.png')
  await browser.close()
  print('PASS: source error → loading; playing → hidden; waiting → loading; native controls disabled')

if __name__=='__main__':asyncio.run(main())
