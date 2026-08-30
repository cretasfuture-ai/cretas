#!/usr/bin/env python3
"""Self-contained loopback tray-box annotator for manifest-backed queues."""
from __future__ import annotations

import json
import mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


PAGE = r"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>托盘完整框复核</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#111820;color:#e8eef5;font:14px system-ui,-apple-system,"Microsoft YaHei",sans-serif}
header{position:sticky;top:0;z-index:2;display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:9px 14px;background:#17212c;border-bottom:1px solid #334454}
button,.badge{border:1px solid #456075;border-radius:6px;background:#223244;color:#eef6ff;padding:7px 11px}button{cursor:pointer}.primary{background:#0878c9}.danger{background:#96363b}.ok{background:#147b55}.warn{color:#54e8b1;font-weight:700}
main{max-width:1600px;margin:auto;padding:10px}.hint{padding:8px 12px;margin-bottom:8px;border:1px solid #31485d;border-radius:7px;background:#182431;color:#b9d7ed}
#stage{position:relative;max-height:calc(100vh - 128px);line-height:0;background:#090d12;border:1px solid #334454;border-radius:7px;overflow:auto}canvas{display:block;width:100%;height:auto;touch-action:none;transform-origin:0 0}
#status{margin-left:auto;color:#ffdd75}.count{font-weight:700}.small{color:#9fb2c2}
</style></head><body><header>
<span class="badge">队列 __QUEUE__</span><button onclick="go(-1)">上一张</button><button onclick="go(1)">下一张</button>
<button onclick="zoomOut()">缩小</button><button onclick="zoomIn()">放大</button><button onclick="resetZoom()">重置缩放</button><span class="badge" id="zoom-label">100%</span>
<button onclick="beginAdd()">新增框</button><button onclick="beginReplace()">重新拉框</button><button onclick="undo()">撤销</button><button class="danger" onclick="removeSelected()">删除选中框</button><button onclick="clearAll()">清空</button>
<button class="primary" onclick="save(false)">保存</button><button class="ok" onclick="save(true)">确认完整并下一张</button><button class="danger" onclick="excludeCurrent()">不属于工作台 · 排除并下一张</button>
<span class="warn">整张图每个可见托盘都要框完整；被画面截断的托盘也沿可见边界框</span><span id="status"></span></header>
<main><div class="hint">鼠标滚轮以光标位置缩放；放大后用滚动条移动视野。拖空白处新增框；拖框内部移动；拖四角调整边界；Delete 删除；Ctrl+Z 撤销。</div>
<div id="stage"><canvas id="cv"></canvas></div><p class="small" id="meta"></p></main>
<script>
let items=[],cur=0,boxes=[],sel=-1,img=new Image(),drag=null,history=[],drawMode=null,replaceIndex=-1,zoom=1;const cv=document.getElementById('cv'),ctx=cv.getContext('2d'),stage=document.getElementById('stage');
const clamp=v=>Math.max(0,Math.min(1,v)); const copy=()=>boxes.map(b=>b.slice());
function point(e){const r=cv.getBoundingClientRect();return [(e.clientX-r.left)/r.width,(e.clientY-r.top)/r.height]}
function cornerHit(b,x,y){const r=cv.getBoundingClientRect(),t=12/Math.max(r.width,1),c=[[b[0],b[1]],[b[2],b[1]],[b[2],b[3]],[b[0],b[3]]];for(let i=0;i<4;i++)if(Math.hypot(x-c[i][0],y-c[i][1])<=t)return i;return -1}
function hit(x,y){for(let i=boxes.length-1;i>=0;i--){const c=cornerHit(boxes[i],x,y);if(c>=0)return [i,'resize',c];const b=boxes[i];if(x>=b[0]&&x<=b[2]&&y>=b[1]&&y<=b[3])return [i,'move',-1]}return [-1,'new',-1]}
function draw(){ctx.clearRect(0,0,cv.width,cv.height);ctx.drawImage(img,0,0,cv.width,cv.height);boxes.forEach((b,i)=>{const x=b[0]*cv.width,y=b[1]*cv.height,w=(b[2]-b[0])*cv.width,h=(b[3]-b[1])*cv.height;ctx.setLineDash(i===sel?[10,6]:[]);ctx.lineWidth=Math.max(1,cv.width/cv.getBoundingClientRect().width)*(i===sel?1.25:1);ctx.strokeStyle=i===sel?'#ffd23f':'#00a7c4';ctx.fillStyle=i===sel?'rgba(255,210,63,.30)':'rgba(0,167,196,.26)';ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);ctx.setLineDash([])})}
async function boot(){items=await (await fetch('/api/items')).json();const pending=items.findIndex(x=>!x.confirmed);cur=pending<0?0:pending;await load()}
async function load(){const it=items[cur],ann=await (await fetch('/api/ann/'+encodeURIComponent(it.id))).json();boxes=(ann.boxes||[]).map(b=>b.slice());sel=-1;history=[];resetZoom();img.onload=()=>{cv.width=img.naturalWidth;cv.height=img.naturalHeight;draw()};img.src='/image/'+encodeURIComponent(it.id)+'?v='+Date.now();document.getElementById('meta').textContent=`${cur+1}/${items.length}  ${it.id}  task=${it.task_id||'-'}  sku=${it.sku_code||'-'}`;status()}
function status(msg=''){const done=items.filter(x=>x.confirmed).length;document.getElementById('status').textContent=`${msg} 完成 ${done}/${items.length} · 当前 ${boxes.length} 框`}
async function save(next){const it=items[cur],r=await fetch('/api/ann/'+encodeURIComponent(it.id),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({boxes})});if(!r.ok){alert('保存失败：'+await r.text());return}it.confirmed=true;it.excluded=false;status('已保存');if(next)go(1)}
async function excludeCurrent(){const it=items[cur];if(!confirm('确认此图不属于有效工作台场景？\n它会被明确排除，不会作为空框背景进入训练。'))return;const r=await fetch('/api/exclude/'+encodeURIComponent(it.id),{method:'POST'});if(!r.ok){alert('排除失败：'+await r.text());return}it.confirmed=true;it.excluded=true;go(1)}
function go(d){cur=(cur+d+items.length)%items.length;load()}
function snapshot(){history.push(copy());if(history.length>50)history.shift()}
function setZoom(next,clientX,clientY){next=Math.max(1,Math.min(6,next));const sr=stage.getBoundingClientRect(),localX=(clientX??(sr.left+stage.clientWidth/2))-sr.left,localY=(clientY??(sr.top+stage.clientHeight/2))-sr.top,ratioX=(stage.scrollLeft+localX)/Math.max(cv.offsetWidth,1),ratioY=(stage.scrollTop+localY)/Math.max(cv.offsetHeight,1);zoom=next;cv.style.width=(zoom*100)+'%';document.getElementById('zoom-label').textContent=Math.round(zoom*100)+'%';requestAnimationFrame(()=>{stage.scrollLeft=ratioX*cv.offsetWidth-localX;stage.scrollTop=ratioY*cv.offsetHeight-localY})}
function zoomIn(){setZoom(zoom*1.25)} function zoomOut(){setZoom(zoom/1.25)} function resetZoom(){zoom=1;cv.style.width='100%';stage.scrollLeft=0;stage.scrollTop=0;document.getElementById('zoom-label').textContent='100%'}
function beginAdd(){drawMode='add';replaceIndex=-1;status('请在图片上拖出新框 ·')}
function beginReplace(){if(sel<0){alert('请先点击选中需要重拉的托盘框');return}drawMode='replace';replaceIndex=sel;status('旧框会保留，画完新框后才替换 ·')}
function undo(){if(history.length){boxes=history.pop();sel=-1;draw();status()}}
function removeSelected(){if(sel>=0){snapshot();boxes.splice(sel,1);sel=-1;draw();status()}}
function clearAll(){if(boxes.length&&confirm('确认清空本张全部托盘框？')){snapshot();boxes=[];sel=-1;draw();status()}}
cv.addEventListener('pointerdown',e=>{cv.setPointerCapture(e.pointerId);const [x,y]=point(e);if(drawMode){snapshot();const action=drawMode,target=replaceIndex;drawMode=null;replaceIndex=-1;boxes.push([x,y,x,y]);sel=boxes.length-1;drag={mode:'new',corner:-1,start:[x,y],before:copy(),action,target};draw();return}const [i,mode,corner]=hit(x,y);snapshot();sel=i;drag={mode,corner,start:[x,y],before:copy(),action:mode,target:i};if(mode==='new'){boxes.push([x,y,x,y]);sel=boxes.length-1}draw()});
cv.addEventListener('pointermove',e=>{if(!drag)return;const [x,y]=point(e),b=boxes[sel],o=drag.before[sel],dx=x-drag.start[0],dy=y-drag.start[1];if(drag.mode==='new'){b[0]=Math.min(drag.start[0],x);b[1]=Math.min(drag.start[1],y);b[2]=Math.max(drag.start[0],x);b[3]=Math.max(drag.start[1],y)}else if(drag.mode==='move'){const w=o[2]-o[0],h=o[3]-o[1],nx=clamp(o[0]+dx),ny=clamp(o[1]+dy);b[0]=Math.min(nx,1-w);b[1]=Math.min(ny,1-h);b[2]=b[0]+w;b[3]=b[1]+h}else{const c=drag.corner;if(c===0||c===3)b[0]=clamp(x);else b[2]=clamp(x);if(c===0||c===1)b[1]=clamp(y);else b[3]=clamp(y)}draw()});
cv.addEventListener('pointerup',()=>{if(!drag)return;const action=drag.action,target=drag.target,b=boxes[sel];if(b&&(Math.abs(b[2]-b[0])<.003||Math.abs(b[3]-b[1])<.003))boxes.splice(sel,1);else if(b){const x0=Math.min(b[0],b[2]),y0=Math.min(b[1],b[3]),x1=Math.max(b[0],b[2]),y1=Math.max(b[1],b[3]);boxes[sel]=[clamp(x0),clamp(y0),clamp(x1),clamp(y1)];if(action==='replace'&&target>=0&&target<boxes.length-1){const replacement=boxes[sel];boxes[target]=replacement;boxes.splice(sel,1);sel=target}}drag=null;draw();status(action==='replace'?'已重拉，确认无误后保存 ·':'')});
cv.addEventListener('wheel',e=>{e.preventDefault();setZoom(e.deltaY<0?zoom*1.18:zoom/1.18,e.clientX,e.clientY)},{passive:false});
addEventListener('keydown',e=>{if(e.key==='Delete'||e.key==='Backspace'){e.preventDefault();removeSelected()}else if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();undo()}else if(e.key==='ArrowLeft')go(-1);else if(e.key==='ArrowRight')go(1)});boot();
</script></body></html>"""


def _valid_boxes(value: object) -> list[list[float]]:
    if not isinstance(value, list):
        raise ValueError("boxes must be a list")
    boxes: list[list[float]] = []
    for raw in value:
        if not isinstance(raw, list) or len(raw) != 4:
            raise ValueError("each box must contain four numbers")
        box = [round(float(part), 8) for part in raw]
        if not all(0 <= part <= 1 for part in box) or box[0] >= box[2] or box[1] >= box[3]:
            raise ValueError("invalid normalised_xyxy box")
        boxes.append(box)
    return boxes


def _human_annotation(item_id: str, boxes: list[list[float]]) -> dict:
    return {
        "photo_id": item_id, "format": "normalised_xyxy", "reviewed": True,
        "source": "human", "preannotations_are_not_ground_truth": True,
        "training_eligible": True, "training_exclusion_reason": None, "boxes": boxes,
    }


def _excluded_annotation(item_id: str) -> dict:
    return {
        "photo_id": item_id, "format": "normalised_xyxy", "reviewed": True,
        "source": "human", "preannotations_are_not_ground_truth": True,
        "training_eligible": False,
        "training_exclusion_reason": "not_workbench_scene",
        "boxes": [],
    }


def serve(queue: Path, port: int) -> None:
    manifest = json.loads((queue / "manifest.json").read_text(encoding="utf-8"))
    rows = manifest["rows"]
    annotations = queue / "annotations-human"
    annotations.mkdir(exist_ok=True)
    by_id: dict[str, dict] = {}
    items: list[dict] = []
    for row in rows:
        item_id = str(row.get("packed_stem") or Path(row["packed_image"]).stem)
        by_id[item_id] = row
        destination = annotations / f"{item_id}.json"
        if not destination.exists():
            boxes = [entry["box"] for entry in row.get("preannotations", []) if isinstance(entry, dict) and entry.get("box")]
            destination.write_text(json.dumps({
                "photo_id": item_id, "format": "normalised_xyxy", "reviewed": False,
                "source": "tray_detector_preannotation_requires_full_human_review",
                "preannotations_are_not_ground_truth": True, "boxes": boxes,
            }, ensure_ascii=False, indent=2), encoding="utf-8")
        saved = json.loads(destination.read_text(encoding="utf-8"))
        reviewed = bool(saved.get("reviewed"))
        items.append({
            "id": item_id, "task_id": row.get("task_id"), "sku_code": row.get("sku_code"),
            "confirmed": reviewed, "excluded": saved.get("training_eligible") is False,
        })

    class Handler(BaseHTTPRequestHandler):
        def send_bytes(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status); self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body))); self.send_header("Cache-Control", "no-store")
            self.end_headers(); self.wfile.write(body)

        def do_GET(self) -> None:
            path = unquote(urlparse(self.path).path)
            if path == "/":
                page = PAGE.replace("__QUEUE__", queue.name)
                return self.send_bytes(200, page.encode("utf-8"), "text/html; charset=utf-8")
            if path == "/api/items":
                return self.send_bytes(200, json.dumps(items).encode(), "application/json")
            if path == "/api/stats":
                saved = [json.loads((annotations / f"{item['id']}.json").read_text(encoding="utf-8")) for item in items]
                reviewed = sum(row.get("reviewed") is True for row in saved)
                excluded = sum(row.get("training_eligible") is False for row in saved)
                return self.send_bytes(200, json.dumps({"reviewed": reviewed, "excluded": excluded, "total": len(items)}).encode(), "application/json")
            if path.startswith("/api/ann/"):
                item_id = path.removeprefix("/api/ann/")
                target = annotations / f"{item_id}.json"
                if item_id in by_id and target.is_file():
                    return self.send_bytes(200, target.read_bytes(), "application/json")
            if path.startswith("/image/"):
                item_id = path.removeprefix("/image/")
                if item_id in by_id:
                    target = (queue / by_id[item_id]["packed_image"]).resolve()
                    if target.is_file() and queue.resolve() in target.parents:
                        return self.send_bytes(200, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "image/jpeg")
            self.send_bytes(404, b"not found", "text/plain")

        def do_POST(self) -> None:
            path = unquote(urlparse(self.path).path)
            annotation_request = path.startswith("/api/ann/")
            exclusion_request = path.startswith("/api/exclude/")
            if not annotation_request and not exclusion_request:
                return self.send_bytes(404, b"not found", "text/plain")
            item_id = path.removeprefix("/api/ann/") if annotation_request else path.removeprefix("/api/exclude/")
            if item_id not in by_id:
                return self.send_bytes(404, b"unknown item", "text/plain")
            try:
                if exclusion_request:
                    result = _excluded_annotation(item_id)
                else:
                    size = int(self.headers.get("Content-Length", "0"))
                    payload = json.loads(self.rfile.read(size))
                    result = _human_annotation(item_id, _valid_boxes(payload.get("boxes")))
                target = annotations / f"{item_id}.json"
                temporary = target.with_suffix(".json.tmp")
                temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
                temporary.replace(target)
                for item in items:
                    if item["id"] == item_id:
                        item["confirmed"] = True
                        item["excluded"] = exclusion_request
                self.send_bytes(200, b'{"ok":true}', "application/json")
            except (ValueError, TypeError, json.JSONDecodeError) as exc:
                self.send_bytes(400, str(exc).encode("utf-8"), "text/plain; charset=utf-8")

        def log_message(self, *_args) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"tray annotator: http://127.0.0.1:{port}")
    print(f"queue: {queue}")
    print(f"pending: {sum(not item['confirmed'] for item in items)}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
