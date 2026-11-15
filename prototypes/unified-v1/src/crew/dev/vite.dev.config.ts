// F 线开发预览：端口 19066；/__unified/f/ 由本目录 f-api.cjs 处理，/__unified/e/ 由 E 线 e-api.cjs 处理（都只写验收会话），其余转发统一入口 19063。
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {createRequire} from 'node:module'
import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'
const here=path.dirname(fileURLToPath(import.meta.url)),unified=path.resolve(here,'../../..')
const require=createRequire(import.meta.url)
const T={target:'http://127.0.0.1:19063',changeOrigin:true}
const mount=(prefix:string,file:string)=>({name:'mount'+prefix,configureServer(s:any){s.middlewares.use(async(req:any,res:any,next:any)=>{if(!req.url||!req.url.startsWith(prefix))return next();const api=require(file);const u=new URL(req.url,'http://x');const send=(r:any,st:number,d:unknown)=>{r.writeHead(st,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});r.end(JSON.stringify(d))};const readBody=(rq:any)=>new Promise<Buffer>(ok=>{const c:Buffer[]=[];rq.on('data',(x:Buffer)=>c.push(x));rq.on('end',()=>ok(Buffer.concat(c)))});await api.handle({req,res,u,p:u.pathname,send,readBody,root:unified})})}})
export default defineConfig({
  root:here,publicDir:false,cacheDir:path.resolve(unified,'../../.cache/vite-f-dev'),
  plugins:[vue(),ui({colorMode:false,router:false,dts:false,ui:{colors:{primary:'blue',neutral:'slate'}}}),mount('/__unified/f/',path.join(here,'..','f-api.cjs')),mount('/__unified/e/',path.join(here,'..','..','collab','e-api.cjs'))],
  resolve:{dedupe:['vue']},
  server:{port:19066,strictPort:true,host:'127.0.0.1',fs:{allow:[path.resolve(unified,'../..')]},proxy:{'/design-tokens.css':T,'/design-system.js':T,'/api':T,'/__lsm':T,'/__unified/menu.json':T,'/system-kit':T}}
})
