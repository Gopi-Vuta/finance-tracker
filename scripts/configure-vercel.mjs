// Usage: node scripts/configure-vercel.mjs https://your-service.onrender.com
import {writeFileSync} from 'node:fs';
const url=new URL(process.argv[2]||'');
if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.pathname!=='/')throw new Error('Supply only the HTTPS backend origin, without credentials or a path.');
const routes=['api','oauth2','login'].map(prefix=>({source:`/${prefix}/:path*`,destination:`${url.origin}/${prefix}/:path*`}));
writeFileSync(new URL('../frontend/vercel.json',import.meta.url),JSON.stringify({$schema:'https://openapi.vercel.sh/vercel.json',framework:'vite',buildCommand:'npm run build',outputDirectory:'dist',rewrites:routes,headers:[{source:'/(api|oauth2|login)/:path*',headers:[{key:'Cache-Control',value:'private, no-store'}]},{source:'/(.*)',headers:[{key:'X-Content-Type-Options',value:'nosniff'},{key:'X-Frame-Options',value:'DENY'},{key:'Referrer-Policy',value:'strict-origin-when-cross-origin'}]}]},null,2)+'\n');
console.log('Created frontend/vercel.json for '+url.origin);
