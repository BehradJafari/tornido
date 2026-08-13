import { Minus, Plus } from "lucide-react";
import { useEffect, useState } from "react";

type Props={value:number;onChange:(value:number)=>void;min?:number;max?:number;step?:number;ariaLabel?:string;className?:string};

// Interaction and visual treatment adapted for Tornido from Beautiful UI's
// MIT-licensed crafted primitive approach: source-owned, compact and explicit.
export function NumberField({value,onChange,min,max,step=1,ariaLabel,className}:Props){
  const [draft,setDraft]=useState(String(value));
  useEffect(()=>setDraft(String(value)),[value]);
  const clamp=(n:number)=>Math.min(max??Infinity,Math.max(min??-Infinity,n));
  const commit=(raw=draft)=>{if(raw.trim()===""){setDraft(String(value));return}const parsed=Number(raw);if(!Number.isFinite(parsed)){setDraft(String(value));return}const next=clamp(parsed);setDraft(String(next));onChange(next)};
  const nudge=(direction:number)=>{const base=draft.trim()===""?value:Number(draft);const next=clamp((Number.isFinite(base)?base:value)+direction*step);setDraft(String(next));onChange(next)};
  return <div className={`bui-number-field ${className??""}`}><button type="button" aria-label={`Decrease ${ariaLabel??"value"}`} onClick={()=>nudge(-1)} disabled={min!=null&&value<=min}><Minus /></button><input type="number" inputMode="decimal" aria-label={ariaLabel} min={min} max={max} step={step} value={draft} onChange={e=>{const raw=e.target.value;setDraft(raw);if(raw.trim()!==""){const parsed=Number(raw);if(Number.isFinite(parsed)&&parsed==clamp(parsed))onChange(parsed)}}} onBlur={()=>commit()} onKeyDown={e=>{if(e.key==="Enter"){commit();e.currentTarget.blur()}else if(e.key==="Escape"){setDraft(String(value));e.currentTarget.blur()}}}/><button type="button" aria-label={`Increase ${ariaLabel??"value"}`} onClick={()=>nudge(1)} disabled={max!=null&&value>=max}><Plus /></button></div>;
}
