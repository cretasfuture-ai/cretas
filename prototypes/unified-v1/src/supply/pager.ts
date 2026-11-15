// 列表分页与筛选偏好（按页面键保存在浏览器，只存筛选，不存业务数据）。
import {computed,ref,watch,type Ref} from 'vue'
export function usePager<T>(list:Ref<T[]>,size=20){
  const page=ref(1),per=ref(size)
  const pages=computed(()=>Math.max(1,Math.ceil(list.value.length/per.value)))
  const rows=computed(()=>list.value.slice((page.value-1)*per.value,page.value*per.value))
  watch(pages,p=>{if(page.value>p)page.value=p})
  return {page,per,pages,rows}
}
export function usePrefs<T extends Record<string,unknown>>(key:string,defaults:T){
  const state=ref({...defaults}) as Ref<T>
  try{const raw=JSON.parse(localStorage.getItem(key)||'null');if(raw&&typeof raw==='object')for(const k of Object.keys(defaults))if(typeof raw[k]===typeof defaults[k])(state.value as Record<string,unknown>)[k]=raw[k]}catch{/* 偏好读取失败用默认值 */}
  watch(state,v=>{try{localStorage.setItem(key,JSON.stringify(v))}catch{/* 偏好未保存不影响来源记录 */}},{deep:true})
  return state
}
