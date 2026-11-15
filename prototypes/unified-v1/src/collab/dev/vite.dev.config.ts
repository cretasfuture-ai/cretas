// E 线开发预览：端口 19065；/__unified/e/ 由本目录 e-api.cjs 处理（写入练习会话），其余转发统一入口 19063。
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {createRequire} from 'node:module'
import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'
const here=path.dirname(fileURLToPath(import.meta.url)),unified=path.resolve(here,'../../..')
const require=createRequire(import.meta.url)
const T={target:'http://127.0.0.1:19063',changeOrigin:true}
export default defineConfig({
  root:here,publicDir:false,cacheDir:path.resolve(unified,'../../.cache/vite-e-dev'),
  plugins:[vue(),ui({colorMode:false,router:false,dts:false,ui:{colors:{primary:'blue',neutral:'slate'}}}),{name:'e-api',configureServer(s){s.middlewares.use(async(req,res,next)=>{if(!req.url||!req.url.startsWith('/__unified/e/'))return next();const api=require(path.join(here,'..','e-api.cjs'));const u=new URL(req.url,'http://x');const send=(r:any,st:number,d:unknown)=>{r.writeHead(st,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});r.end(JSON.stringify(d))};const readBody=(rq:any)=>new Promise<Buffer>(ok=>{const c:Buffer[]=[];rq.on('data',(x:Buffer)=>c.push(x));rq.on('end',()=>ok(Buffer.concat(c)))});await api.handle({req,res,u,p:u.pathname,send,readBody,root:unified})});
    // 只在设置 E_DEV_A_ROOT 时挂 A 线采集接口，写入指向临时目录，不写 A 的真实保存区
    if(process.env.E_DEV_A_ROOT)s.middlewares.use(async(req,res,next)=>{if(!req.url||!req.url.startsWith('/__unified/a/'))return next();const api=require(path.join(here,'..','..','production','a-api.cjs'));const u=new URL(req.url,'http://x');const send=(r:any,st:number,d:unknown)=>{r.writeHead(st,{'Content-Type':'application/json; charset=utf-8'});r.end(JSON.stringify(d))};const readBody=(rq:any)=>new Promise<Buffer>(ok=>{const c:Buffer[]=[];rq.on('data',(x:Buffer)=>c.push(x));rq.on('end',()=>ok(Buffer.concat(c)))});await api.handle({req,res,u,p:u.pathname,send,readBody,root:process.env.E_DEV_A_ROOT})})}}],
  resolve:{dedupe:['vue']},
  server:{port:19065,strictPort:true,host:'127.0.0.1',fs:{allow:[path.resolve(unified,'../..')]},proxy:{'/design-tokens.css':T,'/design-system.js':T,'/api':T,'/__lsm':T}}
})
