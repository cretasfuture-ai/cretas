(()=>{'use strict';const key='blacklake-v24-local-print-templates';
const blank=()=>({id:'local-'+Date.now()+'-'+Math.random().toString(36).slice(2,8),name:'未命名模板',version:'1.0',paper:{preset:'A4',width:210,height:297,orientation:'portrait',margin:{top:10,right:10,bottom:10,left:10},backgroundColor:'#ffffff',printMode:'default'},components:[]});
function initial(){const a=blank(),b=blank();a.id='local-system';a.name='销售合同（A4）';a.system=true;b.id='local-sales';b.name='销售订单';b.components=[{id:'local-text',type:'text',name:'文本',position:{x:0,y:0},size:{width:45,height:10},props:{staticContent:'销售订单（演示）',textStyle:{fontSize:12,color:'#000000',textAlign:'left',fontFamily:'Microsoft YaHei',lineHeight:1.5}},locked:false,zIndex:1}];return[a,b];}
function read(){try{const v=JSON.parse(localStorage.getItem(key));return Array.isArray(v)?v:initial();}catch{return initial();}}
function write(rows){localStorage.setItem(key,JSON.stringify(rows));}
window.REVIEWED_V24_STORE={key,blank,read,write,clone:v=>JSON.parse(JSON.stringify(v))};})();
