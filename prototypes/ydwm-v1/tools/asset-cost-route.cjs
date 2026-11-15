'use strict';
exports.patch=(_file,text)=>{
 if(require('path').basename(_file)!=='index-B9g8pWqs.js')return text;
 if(!text.includes('name:"AnalyticsOverview",component:()=>'))return text;
 const source=/name:"AnalyticsOverview",component:\(\)=>((\w+)\(\(\)=>import\("\.\/index-DuRa6uo-\.js"\),__vite__mapDeps\(\[[\d,]+\]\)\))/;
 if(text.includes('CretasCostRoutePlaceholder'))return text;
 if(!source.test(text))throw Error('Analytics overview route changed; review cost integration');
 return text.replace(source,(_all,original)=>'name:"AnalyticsOverview",component:()=>new URLSearchParams(location.search).get("view")==="product-cost"?Promise.resolve({default:{name:"CretasCostRoutePlaceholder",render:()=>null}}):'+original);
};
