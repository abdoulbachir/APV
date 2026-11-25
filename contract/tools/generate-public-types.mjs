import {readFileSync,writeFileSync} from 'node:fs';
import YAML from 'yaml';

// Two generated files, split by the x-audience of each schema:
//   public-api.ts    public and shared schemas, and the public read operations (unchanged shape)
//   identity-api.ts  account, request and credential schemas, and the identity operations
const PUBLIC_TAGS=new Set(["Site","Cases","Case history"]);
const PUBLIC_AUDIENCES=new Set(["public","shared"]);

function generate(spec){
 const schemas=spec.components.schemas,known=new Set(Object.keys(schemas)),quote=JSON.stringify;
 const isPublicSchema=name=>PUBLIC_AUDIENCES.has(schemas[name]["x-audience"]);
 const isPublicOperation=op=>(op.tags??[]).every(tag=>PUBLIC_TAGS.has(tag));
 const imported=new Set();
 function type(s,where){
  if(!s||typeof s!=="object")throw Error("Missing schema at "+where);
  const supported=new Set(["x-audience","$ref","type","enum","const","oneOf","properties","required","additionalProperties","items","description","format","pattern","minimum","maximum","minLength","maxLength","minItems","maxItems","default","example","title"]);
  for(const k of Object.keys(s))if(!supported.has(k))throw Error("Unsupported schema keyword "+k+" at "+where);
  if(s.$ref){const m=/^#\/components\/schemas\/(\w+)$/.exec(s.$ref);if(!m||!known.has(m[1]))throw Error("Invalid schema ref at "+where);if(isPublicSchema(m[1]))imported.add(m[1]);return m[1]}
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
 for(const [name,s] of Object.entries(schemas))if(isPublicSchema(name))result.push("export type "+name+" = "+type(s,name)+";");
 result.push("","export interface PublicReadOperations {");const routes=[];
 for(const [route,item] of Object.entries(spec.paths)){
  for(const [method,op] of Object.entries(item)){
   if(!isPublicOperation(op))continue;
   if(method!=="get")throw Error("Unsupported public operation at "+route);
   const id=op.operationId,response=type(op.responses["200"].content["application/json"].schema,id+".response"),grouped={query:[],path:[]};
   for(const param of op.parameters??[]){
    let v=param;if(v.$ref){const m=/^#\/components\/parameters\/(\w+)$/.exec(v.$ref);if(!m||!spec.components.parameters[m[1]])throw Error("Unsupported parameter ref");v=spec.components.parameters[m[1]]}
    if(v.in==="header")continue;if(!Object.hasOwn(grouped,v.in))throw Error("Unsupported parameter location "+v.in);
    grouped[v.in].push(quote(v.name)+(v.required?"":"?")+": "+type(v.schema,id+"."+v.name)+";");
   }
   result.push("  "+id+": { response: "+response+"; query: { "+grouped.query.join(" ")+" }; path: { "+grouped.path.join(" ")+" }; };");routes.push("  "+id+": "+quote(route)+",");
  }
 }
 result.push("}","","export const publicReadPaths = {",...routes,"} as const;","");

 // Identity: private schemas and operations. Shared types are imported from the public file.
 imported.clear();
 const body=[],operations=[],paths=[];
 for(const [name,s] of Object.entries(schemas))if(!isPublicSchema(name))body.push("export type "+name+" = "+type(s,name)+";");
 for(const [route,item] of Object.entries(spec.paths)){
  for(const [method,op] of Object.entries(item)){
   if(isPublicOperation(op))continue;
   if(method!=="get"&&method!=="post")throw Error("Unsupported identity method at "+route);
   const id=op.operationId;
   for(const param of op.parameters??[]){
    let v=param;if(v.$ref)v=spec.components.parameters[v.$ref.split("/").pop()];
    if(!v||v.in!=="header")throw Error("Identity operations take header parameters only: "+id);
   }
   const media=op.requestBody?.content?.["application/json"];
   const request=media?type(media.schema,id+".request"):"null";
   const success=Object.entries(op.responses).filter(([status])=>status.startsWith("2"));
   if(success.length!==1)throw Error("Exactly one success response expected: "+id);
   const [status,ok]=success[0],content=ok.content?.["application/json"];
   const response=content?type(content.schema,id+".response"):"null";
   const needsSession=(op.security??spec.security).some(requirement=>"sessionCookie" in requirement);
   operations.push("  "+id+": { method: "+quote(method.toUpperCase())+"; request: "+request+"; response: "+response+"; status: "+status+"; needsSession: "+needsSession+"; };");
   paths.push("  "+id+": "+quote(route)+",");
  }
 }
 const csrf=spec.components.parameters.CsrfHeader.name;
 const identity=["// Generated from contract/openapi.yaml (v"+spec.info.version+"). Do not edit.","// Run: cd contract && npm run generate:types","// Private to the signed-in account. Never use these types in a public page's data.","",
  "import type { "+[...imported].sort().join(", ")+" } from './public-api';","",...body,"",
  "/** Send this header, with the token from getCsrfToken, on every request that is not GET. */","export const csrfHeaderName = "+quote(csrf)+" as const;","",
  "export interface IdentityOperations {",...operations,"}","","export const identityPaths = {",...paths,"} as const;",""];
 return {publicApi:result.join("\n"),identityApi:identity.join("\n")};
}

const source=new URL('../openapi.yaml',import.meta.url);
const targets={
 publicApi:new URL('../../src/api/generated/public-api.ts',import.meta.url),
 identityApi:new URL('../../src/api/generated/identity-api.ts',import.meta.url),
};
const generated=generate(YAML.parse(readFileSync(source,'utf8')));
if(process.argv.includes('--check')){
 for(const [name,target] of Object.entries(targets)){
  let current=null;try{current=readFileSync(target,'utf8')}catch{}
  if(current!==generated[name])throw Error('Generated API types are stale ('+target.pathname.split('/').pop()+'). Run npm run generate:types in contract/.');
 }
 console.log('Generated API types match OpenAPI');
}else{for(const [name,target] of Object.entries(targets))writeFileSync(target,generated[name]);console.log('Generated API types updated');}
