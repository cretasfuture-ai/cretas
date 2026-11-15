'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),dns=require('node:dns').promises,net=require('node:net'),https=require('node:https');
const ROOT=path.dirname(path.dirname(__dirname)),file=path.join(ROOT,'private','local-access.json');
const access=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{ticket:crypto.randomBytes(32).toString('hex')};
fs.writeFileSync(file,JSON.stringify(access));
const cookieName='cretas_prototype_access';
function equal(a,b){const aa=Buffer.from(String(a||'')),bb=Buffer.from(String(b||''));return aa.length===bb.length&&crypto.timingSafeEqual(aa,bb)}
function authorize(req,res,port){
 const deny=()=>{res.writeHead(403,{'Content-Type':'text/plain; charset=utf-8','Cache-Control':'no-store'});res.end('请从本机的“打开V3测试原型.html”进入。');return false};
 if(!['127.0.0.1:'+port,'localhost:'+port].includes(req.headers.host))return deny();
 const origin=req.headers.origin;
 if(origin&&!/^http:\/\/(?:127\.0\.0\.1|localhost):1884[012]$/.test(origin))return deny();
 const u=new URL(req.url,'http://127.0.0.1:'+port);
 if(req.method==='GET'&&equal(u.searchParams.get('access'),access.ticket)){
  u.searchParams.delete('access');res.writeHead(200,{'Set-Cookie':cookieName+'='+access.ticket+'; Path=/; HttpOnly; SameSite=Strict','Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; script-src 'unsafe-inline'"});res.end('<!doctype html><meta charset="utf-8"><title>正在打开本地原型</title><script>location.replace('+JSON.stringify(u.pathname+u.search)+')</script>');return false;
 }
 if(req.headers['sec-fetch-site']==='cross-site')return deny();
 const cookie=(req.headers.cookie||'').split(';').map(s=>s.trim()).find(s=>s.startsWith(cookieName+'='))?.slice(cookieName.length+1);
 if(!equal(cookie,access.ticket))return deny();return true;
}
function decodeApiPath(raw){
 let p=raw;for(let i=0;i<3;i++){const decoded=decodeURIComponent(p);if(decoded===p)break;p=decoded}
 if(p.includes('%')||p.includes('\\')||p.split('/').some(s=>s==='..'||s==='.')||p.includes('//'))throw Error('Invalid local API path');return p;
}
function factoryScope(pathname,query,body,method='GET'){
 const match=pathname.match(/^\/api\/mobile\/([^/]+)(?:\/|$)/);
 if(pathname.startsWith('/api/mobile')&&(!match||match[1]!=='FOOD_0000_001')&&!(method==='GET'&&pathname==='/api/mobile/workflow/node-schemas')&&!/^\/api\/mobile\/smartbi-config\/(?:thresholds|chart-templates|data-sources)(?:\/|$)/.test(pathname)&&!/^\/api\/mobile\/scheduled-tasks(?:\/|$)/.test(pathname))return false;
 const scopeKey=/(?:factory_?ids?|tenant_?ids?)$/i,allowed=x=>x==null||x===''||x==='FOOD_0000_001'||Array.isArray(x)&&x.every(allowed);
 for(const [key,value]of query)if(scopeKey.test(key)&&!allowed(value))return false;
 function check(v){if(Array.isArray(v))return v.every(check);if(v&&typeof v==='object')return Object.entries(v).every(([k,x])=>scopeKey.test(k)?allowed(x):check(x));return true}return check(body);
}
function assertIdentity(role,s){const d=s.data;if(d.username!==s.username)throw Error('Account identity mismatch');if(role==='factory'&&(d.factoryId!=='FOOD_0000_001'||d.role!=='factory_super_admin'))throw Error('Factory identity mismatch');if(role==='platform'&&(d.factoryId!=null||!['super_admin','platform_admin'].includes(d.role)))throw Error('Platform identity mismatch')}
function publicIP(ip){if(net.isIP(ip)!==4)return false;const [a,b]=ip.split('.').map(Number);return a!==0&&a!==10&&a!==127&&a!==169&&a<224&&!(a===172&&b>=16&&b<=31)&&!(a===192&&(b===0||b===168))&&!(a===100&&b>=64&&b<=127)}
async function safeMediaURL(raw){const u=new URL(raw);if(u.protocol!=='https:'||u.username||u.password||u.port&&u.port!=='443')throw Error('Only public HTTPS media is allowed');const addresses=await dns.lookup(u.hostname,{all:true,family:4});if(!addresses.length||addresses.some(a=>!publicIP(a.address)))throw Error('Private media destination is blocked');return {url:u,address:addresses[0].address}}
function raster(bytes,type){const mime=type.split(';')[0].trim();return mime==='image/png'&&bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))||mime==='image/jpeg'&&bytes[0]===255&&bytes[1]===216&&bytes[2]===255||mime==='image/webp'&&bytes.subarray(0,4).toString()==='RIFF'&&bytes.subarray(8,12).toString()==='WEBP'||mime==='image/gif'&&/^GIF8[79]a$/.test(bytes.subarray(0,6).toString())}
async function fetchMedia(raw){const checked=await safeMediaURL(raw);return new Promise((resolve,reject)=>{const r=https.get(checked.url,{lookup:(_host,opts,cb)=>cb(null,opts?.all?[{address:checked.address,family:4}]:checked.address,4),timeout:20000},res=>{const type=res.headers['content-type']||'';if(res.statusCode!==200||!/^image\/(?:png|jpeg|webp|gif)(?:;|$)/.test(type)){res.resume();reject(Error('Media response rejected: '+res.statusCode));return}const chunks=[];let length=0;res.on('data',c=>{length+=c.length;if(length>12*1024*1024){r.destroy(Error('Media exceeds 12MB'));return}chunks.push(c)});res.on('end',()=>{const bytes=Buffer.concat(chunks);if(!raster(bytes,type))return reject(Error('Media signature rejected'));resolve({bytes,type})});res.on('error',reject)});r.on('timeout',()=>r.destroy(Error('Media timeout')));r.on('error',reject)})}
function writeLauncher(){const target='http://127.0.0.1:18840/?access='+access.ticket;fs.writeFileSync(path.join(ROOT,'打开完整双角色原型.html'),'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>打开 Cretas 双角色原型</title><meta name="referrer" content="no-referrer"><meta http-equiv="refresh" content="0;url='+target+'"><a href="'+target+'">打开双角色交互原型</a></html>')}
module.exports={authorize,decodeApiPath,factoryScope,assertIdentity,safeMediaURL,fetchMedia,writeLauncher,cookieName,access,publicIP,raster};
