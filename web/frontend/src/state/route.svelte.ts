import { NAV_EVENT_NAME } from '../lib/nav.ts';

function parse() {
    const segs = location.pathname.split('/').filter(Boolean);
    const query = Object.fromEntries(new URLSearchParams(location.search));
    return { root: segs[0] || '', segs, query };
}

class Route {
    current = $state(parse());

    constructor() {
        const update = () => { this.current = parse(); };
        window.addEventListener(NAV_EVENT_NAME, update);
        window.addEventListener('popstate', update);
    }
}

export const route = new Route();
