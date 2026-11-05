import { execFile } from 'node:child_process';
import type { IncomingMessage } from 'node:http';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';

export function allowedLocalPreview(req: Pick<IncomingMessage,'method'|'url'|'headers'> & {socket:{remoteAddress?:string}}): boolean {
  const address=req.socket.remoteAddress;
  return req.method==='POST' && req.url==='/__blacklake/local-session'
    && ['127.0.0.1','::1','::ffff:127.0.0.1'].includes(address||'')
    && req.headers.host==='127.0.0.1:5178'
    && req.headers.origin==='http://127.0.0.1:5178'
    && req.headers['x-cretas-local-preview']==='1'
    && (!req.headers['sec-fetch-site'] || req.headers['sec-fetch-site']==='same-origin');
}

export function blacklakeLocalPreview(): Plugin {
  return {name:'cretas-blacklake-local-preview',apply:'serve',configureServer(server){
    server.middlewares.use((req,res,next)=>{
      if(req.url?.split('?')[0]!=='/__blacklake/local-session')return next();
      res.setHeader('Cache-Control','no-store');res.setHeader('Content-Type','application/json');
      if(server.config.server.host!=='127.0.0.1' || server.config.server.port!==5178 || !allowedLocalPreview(req)){
        res.statusCode=403;res.end(JSON.stringify({success:false,message:'仅限本机测试页面'}));return;
      }
      execFile('python',['-m','blacklake.preview_session'],{
        cwd:resolve(server.config.root,'../backend/python'),windowsHide:true,encoding:'utf8',timeout:25000,maxBuffer:65536,
      },(error,stdout)=>{
        if(error){res.statusCode=503;res.end(JSON.stringify({success:false,message:'测试会话暂不可用，请确认本机授权服务运行'}));return;}
        try{const result=JSON.parse(stdout);if(result.success!==true||!result.data?.accessToken)throw Error();
          res.end(JSON.stringify(result));
        }catch{res.statusCode=503;res.end(JSON.stringify({success:false,message:'测试会话未能确认'}));}
      });
    });
  }};
}
