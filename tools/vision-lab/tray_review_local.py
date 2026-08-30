#!/usr/bin/env python3
"""Serve a manifest-backed multi-tray boundary review queue on loopback."""
from __future__ import annotations

import argparse
import json
import mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from PIL import Image


PAGE = r'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>托盘边界历史复核</title><style>
*{box-sizing:border-box}body{margin:0;background:#11151a;color:#edf1f5;font:14px system-ui}header{position:sticky;top:0;z-index:5;background:#191e25;border-bottom:1px solid #303844;padding:9px;display:flex;gap:7px;align-items:center;flex-wrap:wrap}button,.badge{border:1px solid #3c4653;border-radius:7px;background:#27303a;color:#edf1f5;padding:7px 10px;font-weight:600}.on{outline:3px solid #8fbfff}.save{background:#1769c2}.danger{background:#7c342d}main{padding:10px}.note{max-width:1400px;margin:0 auto 8px;padding:8px;background:#1b222a;border:1px solid #303844;border-radius:7px}.wrap{max-width:1400px;margin:auto;background:#0c0f13;border:1px solid #303844;border-radius:8px;padding:6px;overflow:auto}canvas{display:block;max-width:100%;height:auto;touch-action:none;cursor:crosshair}.ok{color:#67db97}</style></head><body><header>
<button id="prev">◀</button><span class="badge"><b id="idx">0</b>/<b id="total">0</b></span><button id="next">▶</button><button id="select" class="on">选择/移动</button><button id="draw">框完整托盘 B</button><button id="del" class="danger">删除选中</button><button id="clear" class="danger">清空</button><button id="save" class="save">保存并下一张 S</button><span id="status"></span><span class="badge">完成 <b id="done">0</b>/<b id="all">0</b></span>
</header><main><div class="note" id="note"></div><div class="wrap"><canvas id="cv"></canvas></div></main><script>
let items=[],cur=0,boxes=[],sel=-1,tool='select',dirty=false,img=new Image(),mode=null,start=null,orig=null;const cv=document.getElementById('cv'),ctx=cv.getContext('2d');const clamp=v=>Math.max(0,Math.min(1,v));
function p(e){const r=cv.getBoundingClientRect();return[clamp((e.clientX-r.left)/r.width),clamp((e.clientY-r.top)/r.height)]}function hit(x,y){for(let i=boxes.length-1;i>=0;i--){const b=boxes[i];if(x>=b[0]&&x<=b[2]&&y>=b[1]&&y<=b[3])return i}return-1}function setTool(t){tool=t;document.getElementById('select').className=t==='select'?'on':'';document.getElementById('draw').className=t==='draw'?'on':'';cv.style.cursor=t==='draw'?'crosshair':'default'}
function render(){ctx.clearRect(0,0,cv.width,cv.height);if(img.complete)ctx.drawImage(img,0,0,cv.width,cv.height);const s=cv.width/Math.max(cv.getBoundingClientRect().width,1);boxes.forEach((b,i)=>{const x=b[0]*cv.width,y=b[1]*cv.height,w=(b[2]-b[0])*cv.width,h=(b[3]-b[1])*cv.height;ctx.fillStyle=i===sel?'rgba(255,210,63,.28)':'rgba(0,167,196,.22)';ctx.fillRect(x,y,w,h);ctx.setLineDash(i===sel?[9*s,6*s]:[]);ctx.lineWidth=(i===sel?1.25:1)*s;ctx.strokeStyle=i===sel?'#ffd23f':'#00a7c4';ctx.strokeRect(x,y,w,h);ctx.setLineDash([])})}
async function load(i){cur=(i+items.length)%items.length;const it=items[cur],j=await(await fetch('/api/ann/'+encodeURIComponent(it.id),{cache:'no-store'})).json();boxes=j.boxes||[];sel=-1;dirty=false;document.getElementById('idx').textContent=cur+1;document.getElementById('note').textContent='历史旧框仅是建议，必须逐个增删调整：只框外边界完整且可判断的物理托盘。任务 '+it.task_id+' · R6低IoU框 '+it.low_iou_count+'/'+it.preannotation_count;img=new Image();img.onload=()=>{cv.width=it.w;cv.height=it.h;render()};img.src='/img/'+encodeURIComponent(it.id);document.getElementById('status').textContent=j.reviewed?'已完成':'待复核';stats()}
async function stats(){const j=await(await fetch('/api/stats')).json();document.getElementById('done').textContent=j.reviewed}async function save(){const it=items[cur],r=await fetch('/api/ann/'+encodeURIComponent(it.id),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({boxes})});if(!r.ok){document.getElementById('status').textContent='保存失败';return}it.confirmed=true;dirty=false;await stats();const n=items.findIndex((x,k)=>k>cur&&!x.confirmed);if(n>=0)load(n);else{const first=items.findIndex(x=>!x.confirmed);if(first>=0)load(first);else document.getElementById('status').textContent='全部完成'}}
cv.onpointerdown=e=>{e.preventDefault();const q=p(e);start=q;if(tool==='draw'){mode='draw';boxes.push([q[0],q[1],q[0],q[1]]);sel=boxes.length-1}else{sel=hit(...q);if(sel>=0){mode='move';orig=boxes[sel].slice()}else mode=null}cv.setPointerCapture(e.pointerId);render()};cv.onpointermove=e=>{if(!mode)return;const q=p(e);if(mode==='draw')boxes[sel]=[Math.min(start[0],q[0]),Math.min(start[1],q[1]),Math.max(start[0],q[0]),Math.max(start[1],q[1])];else{const dx=q[0]-start[0],dy=q[1]-start[1],w=orig[2]-orig[0],h=orig[3]-orig[1],x=clamp(Math.min(orig[0]+dx,1-w)),y=clamp(Math.min(orig[1]+dy,1-h));boxes[sel]=[x,y,x+w,y+h]}dirty=true;render()};cv.onpointerup=()=>{if(mode==='draw'&&((boxes[sel][2]-boxes[sel][0])*cv.width<8||(boxes[sel][3]-boxes[sel][1])*cv.height<8)){boxes.splice(sel,1);sel=-1}mode=null;setTool('select');dirty=true;render()};
function nav(d){if(dirty&&!confirm('本图未保存，确定放弃？'))return;load(cur+d)}document.getElementById('prev').onclick=()=>nav(-1);document.getElementById('next').onclick=()=>nav(1);document.getElementById('select').onclick=()=>setTool('select');document.getElementById('draw').onclick=()=>setTool('draw');document.getElementById('del').onclick=()=>{if(sel>=0){boxes.splice(sel,1);sel=-1;dirty=true;render()}};document.getElementById('clear').onclick=()=>{if(confirm('清空本图全部托盘框？')){boxes=[];sel=-1;dirty=true;render()}};document.getElementById('save').onclick=save;document.onkeydown=e=>{if(e.key==='b'||e.key==='B')setTool('draw');else if(e.key==='s'||e.key==='S'||e.key==='Enter')save();else if(e.key==='Delete'&&sel>=0)document.getElementById('del').click()};window.onbeforeunload=e=>{if(dirty){e.preventDefault();e.returnValue=''}};
fetch('/api/items').then(r=>r.json()).then(j=>{items=j;document.getElementById('total').textContent=j.length;document.getElementById('all').textContent=j.length;load(Math.max(0,j.findIndex(x=>!x.confirmed)))})
</script></body></html>'''


def yolo_boxes(path: Path) -> list[list[float]]:
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        _, cx, cy, width, height = map(float, line.split())
        boxes.append([cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2])
    return boxes


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queue", required=True, type=Path)
    parser.add_argument("--port", type=int, default=8795)
    args = parser.parse_args()
    root = args.queue.resolve()
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if not manifest.get("preannotations_are_not_ground_truth") or not manifest.get("every_image_requires_full_human_review"):
        raise RuntimeError("queue does not enforce full human review")
    annotation_dir = root / "annotations-human"
    annotation_dir.mkdir(exist_ok=True)
    items = []
    for row in manifest["rows"]:
        image_path = (root / row["packed_image"]).resolve()
        label_path = (root / row["packed_label"]).resolve()
        if image_path.parent != (root / "images").resolve() or label_path.parent != (root / "labels").resolve():
            raise RuntimeError("queue path escapes its bound directories")
        with Image.open(image_path) as opened:
            width, height = opened.size
        annotation_path = annotation_dir / f"{row['packed_stem']}.json"
        if not annotation_path.exists():
            annotation_path.write_text(json.dumps({
                "photo_id": row["packed_stem"], "format": "normalised_xyxy", "reviewed": False,
                "source": "historical_preannotation_requires_full_human_review",
                "preannotations_are_not_ground_truth": True, "boxes": yolo_boxes(label_path),
            }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        reviewed = bool(json.loads(annotation_path.read_text(encoding="utf-8")).get("reviewed"))
        items.append({"id": row["packed_stem"], "w": width, "h": height, "task_id": row["task_id"],
                      "preannotation_count": row["preannotation_count"], "low_iou_count": row["ground_truth_boxes_below_iou_050"],
                      "confirmed": reviewed, "image": image_path, "annotation": annotation_path})

    class Handler(BaseHTTPRequestHandler):
        def send_data(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status); self.send_header("Content-Type", content_type); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def send_json(self, value, status=200): self.send_data(status, json.dumps(value, ensure_ascii=False).encode(), "application/json; charset=utf-8")
        def do_GET(self):
            path = unquote(urlparse(self.path).path)
            if path == "/": return self.send_data(200, PAGE.encode(), "text/html; charset=utf-8")
            if path == "/api/items": return self.send_json([{k: v for k, v in item.items() if k not in {"image", "annotation"}} for item in items])
            if path == "/api/stats": return self.send_json({"reviewed": sum(bool(json.loads(i["annotation"].read_text(encoding="utf-8")).get("reviewed")) for i in items), "total": len(items)})
            for item in items:
                if path == "/img/" + item["id"]: return self.send_data(200, item["image"].read_bytes(), mimetypes.guess_type(item["image"].name)[0] or "image/jpeg")
                if path == "/api/ann/" + item["id"]: return self.send_json(json.loads(item["annotation"].read_text(encoding="utf-8")))
            return self.send_data(404, b"not found", "text/plain")
        def do_POST(self):
            path = unquote(urlparse(self.path).path)
            item = next((i for i in items if path == "/api/ann/" + i["id"]), None)
            if item is None: return self.send_json({"error": "not found"}, 404)
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 256 * 1024: return self.send_json({"error": "invalid body"}, 400)
            payload = json.loads(self.rfile.read(size))
            boxes = payload.get("boxes")
            if not isinstance(boxes, list): return self.send_json({"error": "boxes required"}, 400)
            clean = []
            for box in boxes:
                if not isinstance(box, list) or len(box) != 4 or not all(isinstance(v, (int, float)) for v in box): return self.send_json({"error": "invalid box"}, 400)
                x0, y0, x1, y1 = map(float, box)
                if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1): return self.send_json({"error": "box outside image"}, 400)
                clean.append([round(x0, 6), round(y0, 6), round(x1, 6), round(y1, 6)])
            item["annotation"].write_text(json.dumps({"photo_id": item["id"], "format": "normalised_xyxy", "reviewed": True,
                "source": "human", "preannotations_are_not_ground_truth": True, "boxes": clean}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
            item["confirmed"] = True
            return self.send_json({"ok": True})
        def log_message(self, fmt, *args): pass

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"tray historical review: http://127.0.0.1:{args.port}")
    print(f"queue: {root}")
    print(f"pending: {sum(not i['confirmed'] for i in items)}", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == "__main__":
    main()
