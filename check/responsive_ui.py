"""Layout matrix; stub video frames to avoid repeated camera connections."""
import asyncio,json,os,sys
from pathlib import Path
from playwright.async_api import async_playwright

SIZES=[(320,568),(360,640),(390,844),(820,1180),(1180,820),(1366,768),(1920,1080),(2560,1440),(3840,2160),(844,390),(320,300)]
CAMERAS=[{'id':f'camera_{i}','title':f'Магазин · Камера {i+1} · Дальний зал','default':'sub','streams':{'sub':f'camera_{i}','mobile':f'camera_{i}_mobile','main':f'camera_{i}_main'}} for i in range(8)]

async def run(browser_type,name):
 browser=await browser_type.launch(timeout=15000, **({'channel':'chrome'} if name=='chromium' else {}))
 page=await browser.new_page()
 await page.route('**/grid.json?*',lambda route:route.fulfill(json={'go2rtc':1984,'cams':CAMERAS}))
 players=[]
 async def player(route):
  players.append(route.request.url)
  await route.fulfill(content_type='text/html',body='<html style="background:black"></html>')
 await page.route('**/player.html?*',player)
 await page.goto('http://127.0.0.1:8090/grid.html')
 await page.wait_for_selector('.cell')
 await page.wait_for_load_state('networkidle')
 initial_players=len(players)
 assert initial_players==8
 await page.locator('iframe').evaluate_all('(frames)=>frames.forEach((frame,i)=>frame.dataset.testIdentity=String(i))')
 reports=[]
 for width,height in SIZES:
  await page.set_viewport_size({'width':width,'height':height})
  await page.wait_for_timeout(100)
  result=await page.evaluate('''()=>{
   const grid=document.querySelector('#grid'),cells=[...document.querySelectorAll('.cell')];
   const rect=e=>{const r=e.getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}};
   return {grid:rect(grid),scrollHeight:grid.scrollHeight,scrollWidth:document.documentElement.scrollWidth,
    toolbar:rect(document.querySelector('#buffer').closest('label')),
    cells:cells.map(c=>({cell:rect(c),title:rect(c.querySelector('.t')),select:rect(c.querySelector('select'))}))};
  }''')
  assert result['scrollWidth']<=width+1,(name,width,height,'horizontal overflow')
  for item in result['cells']:
   box=item['cell'];select=item['select'];title=item['title']
   assert box['width']>=min(width-24,270),(name,width,height,'camera too narrow',box)
   assert box['height']>=140,(name,width,height,'camera too short',box)
   for child in [title,select]:
    assert child['left']>=box['left']-1 and child['right']<=box['right']+1,(name,width,height,'control outside camera',item)
    assert child['top']>=box['top']-1 and child['bottom']<=box['bottom']+1,(name,width,height,'control outside camera',item)
   assert select['bottom']+4<=title['top'],(name,width,height,'selector overlaps title')
  assert result['toolbar']['bottom']<=result['cells'][0]['cell']['top']+1,(name,width,height,'toolbar covers video')
  await page.locator('.cell').last.scroll_into_view_if_needed()
  last=await page.locator('.cell').last.bounding_box()
  assert last['y']+last['height']<=height+1,(name,width,height,'last camera unreachable')
  reports.append({'size':f'{width}x{height}','cell':result['cells'][0]['cell'],'scrolling':result['scrollHeight']>result['grid']['height']+1})
  # Rotation/resize keeps the current player DOM, rather than reconnecting streams.
  assert await page.locator('iframe').evaluate_all('(frames)=>frames.every((f,i)=>f.dataset.testIdentity===String(i))')
  assert len(players)==initial_players,(name,'resizing reloaded a stream')
  await page.evaluate('document.querySelector("#grid").scrollTo(0,0)')
 print(name, 'PASS', len(SIZES),'sizes',flush=True)
 # Old TV browser fallback, blocked storage and variable camera counts.
 for count in [0,1,3,10]:
  case=await browser.new_page(viewport={'width':390,'height':844})
  await case.add_init_script("window.ResizeObserver=undefined; Storage.prototype.getItem=()=>{throw Error('blocked')}; Storage.prototype.setItem=()=>{throw Error('blocked')}")
  cameras=[{**CAMERAS[i%8],'id':f'case_{i}'} for i in range(count)]
  await case.route('**/grid.json?*',lambda route:route.fulfill(json={'go2rtc':1984,'cams':cameras}))
  await case.route('**/player.html?*',lambda route:route.fulfill(content_type='text/html',body='<html style="background:black"></html>'))
  await case.goto('http://127.0.0.1:8090/grid.html')
  await case.wait_for_load_state('networkidle')
  assert await case.locator('.cell').count()==count
  assert await case.locator('#buffer').is_visible()
  await case.locator('#buffer').select_option('6')
  await case.wait_for_timeout(300)
  assert await case.locator('#buffer').input_value()=='6',(name,'buffer must work without localStorage')
  if count:
   await case.locator('.stream-select').first.select_option('main')
   await case.set_viewport_size({'width':844,'height':390})
   await case.wait_for_timeout(100)
   assert await case.evaluate('document.documentElement.scrollWidth<=innerWidth')
  await case.close()
 print(name,'PASS storage blocked, ResizeObserver fallback, 0/1/3/10 cameras',flush=True)
 await browser.close()
 return reports

async def main():
 names=sys.argv[1:] or ['chromium']
 async with async_playwright() as p:
  for name in names:await run(getattr(p,name),name)

if __name__=='__main__':asyncio.run(main())
