import {readFileSync,writeFileSync} from 'node:fs';
import YAML from 'yaml';

function generate(spec){
 const schemas=spec.components.schemas,known=new Set(Object.keys(schemas)),quote=JSON.stringify;
 function type(s,where){
  if(!s||typeof s!=="object")throw Error("Missing schema at "+where);
  const supported=new Set(["$ref","type","enum","const","oneOf","properties","required","additionalProperties","items","description","format","pattern","minimum","maximum","minLength","maxLength","minItems","maxItems","default","example","title"]);
  for(const k of Object.keys(s))if(!supported.has(k))throw Error("Unsupported schema keyword "+k+" at "+where);
  if(s.$ref){const m=/^#\/components\/schemas\/(\w+)$/.exec(s.$ref);if(!m||!known.has(m[1]))throw Error("Invalid schema ref at "+where);return m[1]}
  if(Object.hasOwn(s,"const"))return quote(s.const);
  if(s.enum)return s.enum.map(v=>quote(v)).join(" | ");
  if(s.oneOf)return s.oneOf.map((v,i)=>type(v,where+"."+i)).join(" | ");
  if(Array.isArray(s.type))return s.type.map(t=>type({type:t},where)).join(" | ");
  switch(s.type){
   case "integer":case "number":return "number";
   case "string":case "boolean":case "null":return s.type;
   case "array":return "Array<"+type(s.items,where+".items")+">";
   case "object":{
    if(s.additionalProperties!==false||!s.properties)throw Error("Unsupported open object at "+where);
    const required=new Set(s.required??[]);
    for(const k of required)if(!Object.hasOwn(s.properties,k))throw Error("Unknown required property at "+where+"."+k);
    return "{ "+Object.entries(s.properties).map(([k,v])=>quote(k)+(required.has(k)?"":"?")+": "+type(v,where+"."+k)+";").join(" ")+" }";
   }
   default:throw Error("Unsupported schema type at "+where);
  }
 }
 const result=["// Generated from contract/openapi.yaml (v"+spec.info.version+"). Do not edit.","// Run: cd contract && npm run generate:types","// Runtime constraints (patterns, numeric bounds, etc.) are enforced by contract tests.",""];
 for(const [name,s] of Object.entries(schemas))result.push("export type "+name+" = "+type(s,name)+";");
 result.push("","export interface PublicReadOperations {");const routes=[];
 for(const [route,item] of Object.entries(spec.paths)){
  if(Object.keys(item).some(k=>k!=="get"))throw Error("Unsupported operation at "+route);
  const op=item.get,id=op.operationId,response=type(op.responses["200"].content["application/json"].schema,id+".response"),grouped={query:[],path:[]};
  for(const param of op.parameters??[]){
   let v=param;if(v.$ref){const m=/^#\/components\/parameters\/(\w+)$/.exec(v.$ref);if(!m||!spec.components.parameters[m[1]])throw Error("Unsupported parameter ref");v=spec.components.parameters[m[1]]}
   if(v.in==="header")continue;if(!Object.hasOwn(grouped,v.in))throw Error("Unsupported parameter location "+v.in);
   grouped[v.in].push(quote(v.name)+(v.required?"":"?")+": "+type(v.schema,id+"."+v.name)+";");
  }
  result.push("  "+id+": { response: "+response+"; query: { "+grouped.query.join(" ")+" }; path: { "+grouped.path.join(" ")+" }; };");routes.push("  "+id+": "+quote(route)+",");
 }
 result.push("}","","export const publicReadPaths = {",...routes,"} as const;","");return result.join("\n");
}

const source=new URL('../openapi.yaml',import.meta.url);
const target=new URL('../../src/api/generated/public-api.ts',import.meta.url);
const generated=generate(YAML.parse(readFileSync(source,'utf8')));
if(process.argv.includes('--check')){
 if(readFileSync(target,'utf8')!==generated)throw Error('Generated API types are stale. Run npm run generate:types in contract/.');
 console.log('Generated API types match OpenAPI');
}else{writeFileSync(target,generated);console.log('Generated API types updated');}
