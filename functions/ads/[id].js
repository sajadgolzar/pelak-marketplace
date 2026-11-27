import {detail} from '../../pelak-public-ads.mjs';
export const onRequestGet=({request,params})=>detail(request,params.id);
