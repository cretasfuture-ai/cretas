import fs from 'node:fs';
import path from 'node:path';
const normalize=target=>{const u=new URL(target,'http://local');u.searchParams.sort();return u.pathname+u.search};
export function additionalRead(root,target){const file=path.join(root,'private/additional-readonly-responses.json');if(!fs.existsSync(file))return null;const wanted='GET '+normalize(target),entry=Object.entries(JSON.parse(fs.readFileSync(file))).find(([key])=>'GET '+normalize(key.slice(4))===wanted)?.[1];return entry?.status===200?entry:null}
