import { APP_CONFIG } from '../config';
import hope from '../assets/hope-window.webp';
import home from '../assets/new-home.webp';
const mockImages:Record<string,string>={MockHopeWindowArtwork0001:hope,MockNewHomeArtwork000001:home};
export function imageSource(path:string|null|undefined):string|undefined{
 if(!path||!/^\/media\/public\/[A-Za-z0-9_-]{22,64}\/[a-z0-9-]{1,32}\.(webp|jpg|png|avif)$/.test(path))return undefined;
 return APP_CONFIG.apiMode==='mock'?mockImages[path.split('/')[3]]:path;
}
