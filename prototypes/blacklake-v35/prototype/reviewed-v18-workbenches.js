(()=>{'use strict';const id=window.DEMO_PAGE?.id,C=window.DEMO_CONTEXT;if(!['BL-02-07','BL-03-08'].includes(id)||!C)return;
// Source cards describe the whole workbench, independently of keyword filters.
const records=C.getFilteredRecords?.()||[],cards=[...document.querySelectorAll('.indicatorsCard__CBCML')];
for(const [i,card]of cards.entries()){
 const title=card.querySelector('.cardTitleBox__b7wBH')?.textContent.trim();
 const value=i===0?records.length:records.reduce((sum,r)=>sum+(Number(String(r.fields[title]??'0').replaceAll(',',''))||0),0);
 const walker=document.createTreeWalker(card,NodeFilter.SHOW_TEXT);let node;while((node=walker.nextNode())){if(node.textContent.trim()&&node.parentElement.closest('.cardTitleBox__b7wBH')===null&&/\d/.test(node.textContent)){node.textContent=String(Math.round(value*100)/100);break;}}
}
})();
