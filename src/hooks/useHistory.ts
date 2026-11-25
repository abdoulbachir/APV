import { useEffect, useRef, useState } from 'react';
import type { Locale, Page, PageQuery } from '../api/public-types';
export function useHistory<T extends {id:string}>(preview:T[]|undefined,total:number|undefined,code:string,locale:Locale,fetchPage:(code:string,q:PageQuery,locale:Locale)=>Promise<Page<T>>){
 const [state,setState]=useState<{preview:T[]|undefined;items:T[]}>({preview,items:preview??[]});
 const items=state.preview===preview?state.items:preview??[];
 const [loading,setLoading]=useState(false);
 const [error,setError]=useState<'changed'|'failed'|null>(null);
 const generation=useRef(0);
 const inFlight=useRef(false);
 useEffect(()=>{generation.current++;inFlight.current=false;setState({preview,items:preview??[]});setLoading(false);setError(null);return()=>{generation.current++;};},[preview,total,code,locale]);
 const hasMore=items.length<(total??0);
 async function load(){
  if(inFlight.current||!hasMore||error==='changed')return;
  const token=generation.current;inFlight.current=true;setLoading(true);setError(null);
  try{
   const next=await fetchPage(code,{page:Math.floor(items.length/10)+1,pageSize:10},locale);
   if(token!==generation.current)return;
   const ids=new Set(items.map(x=>x.id));
   if(next.totalItems!==total||next.items.some(x=>ids.has(x.id))||!next.items.length){setError('changed');return;}
   setState({preview,items:[...items,...next.items]});
  }catch{if(token===generation.current)setError('failed');}
  finally{if(token===generation.current){inFlight.current=false;setLoading(false);}}
 }
 return {items,loading,error,hasMore,load};
}
