
(() => {
window.addEventListener('error',e=>{try{window.__TAURI__.core.invoke('js_log',{msg:`${e.message} @${e.lineno}:${e.colno}`});}catch(_){}});window.addEventListener('unhandledrejection',e=>{try{window.__TAURI__.core.invoke('js_log',{msg:'rejection '+(e.reason&&e.reason.stack||e.reason)});}catch(_){}});
let TS={};const $t=(k,d)=>TS[k]||d||k;
const READY=window.__TAURI__?window.__TAURI__.core.invoke('get_strings').then(m=>{TS=m;}).catch(()=>{}):Promise.resolve();window.__READY=READY;
/* ================= icons: the repo's Android vectors ================= */
const I={
  battery:'<path d="M4.5,7h12a2,2 0,0 1,2 2v6a2,2 0,0 1,-2 2h-12a2,2 0,0 1,-2 -2v-6a2,2 0,0 1,2 -2zM21.5,10.5v3" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M5.5,10h5.5v4h-5.5z" fill="#fff"/>',
  palette:'<path d="M12,3a9,9 0,1 0,0,18c1.4,0 2,-0.9 2,-2c0,-1.2 -1,-1.6 -1,-2.8c0,-1.1 0.9,-1.7 2,-1.7h2.5c1.9,0 3.5,-1.4 3.5,-3.5C21,6.6 17,3 12,3z" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M6.1,11a1.4,1.4 0,1 0,2.8,0a1.4,1.4 0,1 0,-2.8,0zM8.6,7a1.4,1.4 0,1 0,2.8,0a1.4,1.4 0,1 0,-2.8,0zM13.6,7.5a1.4,1.4 0,1 0,2.8,0a1.4,1.4 0,1 0,-2.8,0z" fill="#fff"/>',
  off:'<path d="M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 M6 6 L18 18" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
  anc:'<path d="M12 9 A3 3 0 1 1 11.99 9 M12 5 A7 7 0 1 1 11.99 5" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"/><path d="M12 1.5 A10.5 10.5 0 1 1 11.99 1.5" fill="none" stroke="#fff" stroke-width="2"/>',
  adaptive:'<path d="M12 3.5 L13.9 10.1 L20.5 12 L13.9 13.9 L12 20.5 L10.1 13.9 L3.5 12 L10.1 10.1 Z" fill="none" stroke="#fff" stroke-width="2" stroke-linejoin="round"/>',
  transp:'<path d="M3 12 H5 M7.5 8 V16 M12 4.5 V19.5 M16.5 8 V16 M21 12 H19" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round"/>',
  low:'<path d="M9.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z" fill="#fff"/><path d="M6.8,12a5.2,5.2 0 1,0 10.4,0a5.2,5.2 0 1,0 -10.4,0z" fill="none" stroke="#fff" stroke-width="1.9"/>',
  high:'<path d="M9.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z" fill="#fff"/><path d="M6.8,12a5.2,5.2 0 1,0 10.4,0a5.2,5.2 0 1,0 -10.4,0zM3.8,12a8.2,8.2 0 1,0 16.4,0a8.2,8.2 0 1,0 -16.4,0zM1,12a11,11 0 1,0 22,0a11,11 0 1,0 -22,0z" fill="none" stroke="#fff" stroke-width="1.9"/>',
  smart:'<path d="M9.8,12a2.2,2.2 0 1,0 4.4,0a2.2,2.2 0 1,0 -4.4,0z" fill="#fff"/><path d="M6.8,12a5.2,5.2 0 1,0 10.4,0a5.2,5.2 0 1,0 -10.4,0z" fill="none" stroke="#fff" stroke-width="1.9"/><path d="M20.08,10.58A8.2,8.2 0 1,1 13.42,3.92" fill="none" stroke="#fff" stroke-width="1.9" stroke-linecap="round"/><path d="M19,1.2Q19.6,4.4 22.8,5Q19.6,5.6 19,8.8Q18.4,5.6 15.2,5Q18.4,4.4 19,1.2z" fill="#fff"/>',
  bolt:'<path d="M13 2L4 14h7l-1 8 9-12h-7z" fill="#fff"/>',
  hires:'<path d="M3 17.6h5.4v1.8H3zM3 12.1h10.2v1.8H3zM3 6.6h15v1.8H3zM19.6 5.2h2v14.2h-2z" fill="#fff"/>',
  spatial:'<path d="M12 10m-2.2 0a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0M12 5.6C8.4 5.6 5.4 8.2 4.6 11.6h1.9C7.2 9.3 9.4 7.5 12 7.5s4.8 1.8 5.5 4.1h1.9C18.6 8.2 15.6 5.6 12 5.6zM12 1.4C6.6 1.4 2.1 5.2 1.1 10.2H3C4 6.3 7.7 3.3 12 3.3s8 3 9 6.9h1.9C21.9 5.2 17.4 1.4 12 1.4zM7.4 14.2h9.2v6.2h-2.7v-4.3h-3.8v4.3H7.4z" fill="#fff"/>',
  hearing:'<path d="M17,20c-0.29,0 -0.56,-0.06 -0.76,-0.15 -0.71,-0.37 -1.21,-0.88 -1.71,-2.38 -0.51,-1.56 -1.47,-2.29 -2.39,-3 -0.79,-0.61 -1.61,-1.24 -2.32,-2.53C9.29,10.98 9,9.930 9,9c0,-2.8 2.2,-5 5,-5s5,2.2 5,5h2c0,-3.93 -3.07,-7 -7,-7S7,5.07 7,9c0,1.26 0.38,2.65 1.07,3.9 0.91,1.65 1.98,2.48 2.85,3.15 0.81,0.62 1.39,1.07 1.71,2.05 0.6,1.82 1.37,2.84 2.73,3.55 0.51,0.23 1.07,0.35 1.64,0.35 2.21,0 4,-1.79 4,-4h-2c0,1.1 -0.9,2 -2,2zM7.64,2.64L6.22,1.22C4.23,3.21 3,5.96 3,9s1.23,5.79 3.22,7.78l1.41,-1.41C6.01,13.74 5,11.49 5,9s1.01,-4.74 2.64,-6.36zM11.5,9c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5 -1.12,-2.5 -2.5,-2.5 -2.5,1.12 -2.5,2.5z" fill="#fff"/>',
  eq:'<path d="M1.143 11.429h3.428v11.428H1.143zM5.714 4.571h3.429v18.286H5.714zM10.286 8h3.428v14.857h-3.428zM14.857 2.286h3.429v20.571h-3.429zM19.429 13.714h3.428v9.143h-3.428z" fill="#fff"/>',
  devices:'<path d="M4,6h18V4H4C2.9,4 2,4.9 2,6v11H0v3h14v-3H4V6zM23,8h-6c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1zM22,17h-4v-7h4v7z" fill="#fff"/>',
  gesture:'<path d="M9 11.24V7.5C9 6.12 10.12 5 11.5 5S14 6.12 14 7.5v3.74c1.21-0.81 2-2.18 2-3.74C16 5.01 13.99 3 11.5 3S7 5.01 7 7.5c0 1.56 0.79 2.93 2 3.74zM18.84 15.87l-4.54-2.26c-0.17-0.07-0.35-0.11-0.54-0.11H13v-6c0-0.83-0.67-1.5-1.5-1.5S10 6.67 10 7.5v10.74l-3.43-0.72c-0.08-0.01-0.15-0.03-0.24-0.03-0.31 0-0.59 0.13-0.79 0.33l-0.79 0.8 4.94 4.94c0.27 0.27 0.65 0.44 1.06 0.44h6.79c0.75 0 1.33-0.55 1.44-1.28l0.75-5.27c0.01-0.07 0.02-0.14 0.02-0.2 0-0.62-0.38-1.16-0.91-1.38z" fill="#fff"/>',
  earbud:'<path d="M17,8V19a1.5,1.5 0,0 1,-3,0V12.58A5,5 0,1 1,17,8z" fill="#fff" stroke="#fff" stroke-width="2.2" stroke-linejoin="round"/>',
  find:'<path d="M12 1.8C7.4 1.8 3.7 5.5 3.7 10.1 3.7 16.3 12 22.4 12 22.4s8.3-6.1 8.3-12.3c0-4.6-3.7-8.3-8.3-8.3zm0 10.6c-1.3 0-2.3-1-2.3-2.3s1-2.3 2.3-2.3 2.3 1 2.3 2.3-1 2.3-2.3 2.3z" fill="#fff"/>',
  power:'<path d="M12,3v8 M6.5,6.5a8,8 0,1 0,11 0" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  dev:'<path d="M6.4,6.6L12.2,12L6.4,17.4" fill="none" stroke="#fff" stroke-width="2.1" stroke-linecap="square"/><path d="M13.6,15.9h6v2.2h-6z" fill="#fff"/>',
  info:'<path d="M3,12a9,9 0,1 0,18,0a9,9 0,1 0,-18,0zM12,11v6M12,7.5h0.01" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  chev:'<path d="M9,6l6,6 -6,6" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  back:'<path d="M15,6l-6,6 6,6" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  plus:'<path d="M12,5v14M5,12h14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/>',
  check:'<path d="M5.5,12.5l4,4L18.5,7.5" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
  pencil:'<path d="M3,21 L4.6,15.2 L15.6,4.2 L19.8,8.4 L8.8,19.4 Z" fill="#fff"/>',
  del:'<path d="M9,3h6v2h-6z M3,6h18v3h-18z M5,10.5h14l-1.2,10.5h-11.6z" fill="#fff"/>',
  close:'<path d="M6,6L18,18 M18,6L6,18" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round"/>',
  cog:'<path d="M12,5.5a6.5,6.5 0 1,0 0.01,0 Z M12,8.6a3.4,3.4 0 1,1 -0.01,0 Z" fill="#fff" fill-rule="evenodd"/><path d="M10.2,1.5h3.6v5h-3.6z M10.2,17.5h3.6v5h-3.6z" fill="#fff"/><path d="M10.2,1.5h3.6v5h-3.6z M10.2,17.5h3.6v5h-3.6z" fill="#fff" transform="rotate(60 12 12)"/><path d="M10.2,1.5h3.6v5h-3.6z M10.2,17.5h3.6v5h-3.6z" fill="#fff" transform="rotate(120 12 12)"/>',
  volume:'<path d="M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z" fill="#fff"/>',
  tap1:'<path d="M9.5 23.5V14a2.5 2.5 0 0 1 5 0v9.5zM6.11 10.25a6.5 6.5 0 0 1 11.78 0l-1.45.68a4.9 4.9 0 0 0-8.88 0z" fill="#fff"/>',
  tap2:'<path d="M9.5 23.5V14a2.5 2.5 0 0 1 5 0v9.5zM6.56 10.46a6 6 0 0 1 10.88 0l-1.36.64a4.5 4.5 0 0 0-8.16 0zM2.94 8.77a10 10 0 0 1 18.12 0l-1.36.64a8.5 8.5 0 0 0-15.4 0z" fill="#fff"/>',
  tap3:'<path d="M9.5 23.5V14a2.5 2.5 0 0 1 5 0v9.5zM7.47 10.89a5 5 0 0 1 9.06 0l-1.27.59a3.6 3.6 0 0 0-6.52 0zM4.75 9.62a8 8 0 0 1 14.5 0l-1.27.59a6.6 6.6 0 0 0-11.96 0zM2.03 8.35a11 11 0 0 1 19.94 0l-1.27.59a9.6 9.6 0 0 0-17.4 0z" fill="#fff"/>',
  slide:'<path d="M5.5 23.5V14a2.5 2.5 0 0 1 5 0v9.5zM2.11 10.25a6.5 6.5 0 0 1 11.78 0l-1.45.68a4.9 4.9 0 0 0-8.88 0zM19.4 3.5l3 4.5h-6zM19.4 20.5l3-4.5h-6zM18.6 7.5h1.6v9h-1.6z" fill="#fff"/>',
  hold:'<path d="M9.5 23.5V14a2.5 2.5 0 0 1 5 0v9.5z" fill="#fff"/><path d="M3.5 13a8.5 8.5 0 1 0 17 0 8.5 8.5 0 1 0-17 0zm1.6 0a6.9 6.9 0 1 0 13.8 0 6.9 6.9 0 1 0-13.8 0z" fill="#fff" fill-rule="evenodd"/>',
  warn:'<path d="M12,3l10,18H2z M12,10v5 M12,18h0.01" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  update:'<path d="M12,4v11M7,10l5,5 5,-5M4,20h16" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  language:'<path d="M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0zM3.6,9h16.8M3.6,15h16.8M12,3a14,14 0 0,1 0,18M12,3a14,14 0 0,0 0,18" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
  github:'<path d="M12,0.297c-6.63,0 -12,5.373 -12,12 0,5.303 3.438,9.8 8.205,11.385 0.6,0.113 0.82,-0.258 0.82,-0.577 0,-0.285 -0.01,-1.04 -0.015,-2.04 -3.338,0.724 -4.042,-1.61 -4.042,-1.61C4.422,18.07 3.633,17.7 3.633,17.7c-1.087,-0.744 0.084,-0.729 0.084,-0.729 1.205,0.084 1.838,1.236 1.838,1.236 1.07,1.835 2.809,1.305 3.495,0.998 0.108,-0.776 0.417,-1.305 0.76,-1.605 -2.665,-0.3 -5.466,-1.332 -5.466,-5.93 0,-1.31 0.465,-2.38 1.235,-3.22 -0.135,-0.303 -0.54,-1.523 0.105,-3.176 0,0 1.005,-0.322 3.3,1.23 0.96,-0.267 1.98,-0.399 3,-0.405 1.02,0.006 2.04,0.138 3,0.405 2.28,-1.552 3.285,-1.23 3.285,-1.23 0.645,1.653 0.24,2.873 0.12,3.176 0.765,0.84 1.23,1.91 1.23,3.22 0,4.61 -2.805,5.625 -5.475,5.92 0.42,0.36 0.81,1.096 0.81,2.22 0,1.606 -0.015,2.896 -0.015,3.286 0,0.315 0.21,0.69 0.825,0.57C20.565,22.092 24,17.592 24,12.297c0,-6.627 -5.373,-12 -12,-12" fill="#fff"/>',
  kofi:'<path d="M4,7h13v6.5a5.5,5.5 0,0 1,-5.5,5.5h-2a5.5,5.5 0,0 1,-5.5,-5.5z M17,9h1.5a2.75,2.75 0,0 1,0,5.5H17" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="M10.5,15.6l-2.4,-2.3c-0.9,-0.9 -0.3,-2.4 0.9,-2.4 0.6,0 1.1,0.3 1.5,0.8 0.4,-0.5 0.9,-0.8 1.5,-0.8 1.2,0 1.8,1.5 0.9,2.4z" fill="#fff"/>',
  discord:'<path fill="#fff" d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028 14.09 14.09 0 0 0 1.226-1.994.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/>',
  chat:'<path d="M5,5h14a2,2 0,0 1,2,2v8a2,2 0,0 1,-2,2H11l-5,4v-4H5a2,2 0,0 1,-2,-2V7a2,2 0,0 1,2,-2z" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
  min:'<path d="M6 12h12" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>',
  max:'<rect x="6.5" y="6.5" width="11" height="11" rx="2.5" fill="none" stroke="#fff" stroke-width="1.6"/>',
  x:'<path d="M7 7l10 10M17 7 7 17" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>'
};
const BIG={
  budL:['0 0 176 272','<path d="M165.1 131.2a66 66 0 0 0 9-31 56 56 0 0 0-3.2-19.9 46 46 0 0 0-10.4-15.8 40 40 0 0 0-35-10.8 50 50 0 0 0-19 6.8 59 59 0 0 0-16.3 14 67 67 0 0 0-15.3 39.8 55 55 0 0 0 3.4 19.9A45 45 0 0 0 88.7 150a40 40 0 0 0 34.6 10.6 50 50 0 0 0 19-6.7 61 61 0 0 0 16.4-13.8 67 67 0 0 0 6.4-9M48.4 133c1.2.5 2.6.5 3.9.8q.8.7 1.1 1.7v101.1q.5 5.3 2 10.4 1 3 2.8 5.8a33 33 0 0 0 26.6 16 33 33 0 0 0 33.8-25.7q.8-3.6.8-7.2v-66.7l-1.8-2-11.8-1.4a48 48 0 0 1-19.3-9.7 47 47 0 0 1-12.9-17.5 55 55 0 0 1-4.7-22.9 68 68 0 0 1 9.5-34.9 73 73 0 0 1 24.4-25 59 59 0 0 1 21.7-8l12.4-.4 1.3-.9.6-1.6q-3-5-6.3-9.8a84 84 0 0 0-34-25.4 93 93 0 0 0-30.3-6.5Q61.7 3 55.3 4A63 63 0 0 0 32 12.2a62 62 0 0 0-19.3 18.5 67 67 0 0 0-10 23.5 59 59 0 0 0 4.7 36 79 79 0 0 0 16.1 23.4q5 5 10.5 9.4z" fill="#fff" fill-rule="evenodd"/>'],
  budR:['0 0 176 272','<path d="M10.9 131.2a66 66 0 0 1-9-31A56 56 0 0 1 5 80.4a46 46 0 0 1 10.4-15.8 40 40 0 0 1 35-10.8 50 50 0 0 1 19 6.8 59 59 0 0 1 16.3 14 67 67 0 0 1 15.3 39.8 55 55 0 0 1-3.4 19.9A45 45 0 0 1 87.3 150a40 40 0 0 1-34.6 10.6 50 50 0 0 1-19-6.7 61 61 0 0 1-16.5-13.8 67 67 0 0 1-6.3-9m116.7 1.8c-1.2.5-2.6.5-3.9.8q-.8.7-1.1 1.7v101.1q-.5 5.3-2 10.4-1 3-2.7 5.8a33 33 0 0 1-26.7 16A33 33 0 0 1 57.4 243q-.8-3.6-.8-7.2v-66.7l1.8-2 11.8-1.4a48 48 0 0 0 19.3-9.7 47 47 0 0 0 12.9-17.5 55 55 0 0 0 4.7-22.9 68 68 0 0 0-9.5-34.9 73 73 0 0 0-24.4-25 59 59 0 0 0-21.7-8l-12.4-.4-1.4-.9-.5-1.6q3-5 6.3-9.8a84 84 0 0 1 34-25.4 93 93 0 0 1 30.3-6.5q6.4-.2 12.9.7a63 63 0 0 1 23.3 8.3 62 62 0 0 1 19.3 18.5 67 67 0 0 1 10 23.5 59 59 0 0 1-4.7 36 79 79 0 0 1-16.1 23.4q-5 5-10.4 9.4z" fill="#fff" fill-rule="evenodd"/>'],
  case:['0 0 496 400','<path d="M241 3.6a363 363 0 0 0-59.8 6.6 313 313 0 0 0-55.6 16.7 252 252 0 0 0-46.4 25.5 198 198 0 0 0-35 32.2 178 178 0 0 0-25.8 41l-6 15h471.2q-3-8-6.4-15.8a180 180 0 0 0-64.7-75.4 222 222 0 0 0-32-18 265 265 0 0 0-36.7-14 309 309 0 0 0-57.6-11.7 408 408 0 0 0-45.1-2M8.7 152.6Q6 162.9 4.3 173a174 174 0 0 0 6.6 82.5 183 183 0 0 0 29.2 55.1 182 182 0 0 0 45 41.2 229 229 0 0 0 39.8 21 281 281 0 0 0 46 14.9 332 332 0 0 0 105.5 7.6 320 320 0 0 0 98.2-24 253 253 0 0 0 40-22 190 190 0 0 0 45.3-44 185 185 0 0 0 27.3-56.8 182 182 0 0 0 4.4-76.2c-1-6.6-2.8-13.1-4.2-19.6zm247.9 163.9a9 9 0 0 1-5.6 8.3 9 9 0 0 1-6.9 0 9 9 0 0 1-4.8-4.9 9 9 0 0 1 0-6.9 9 9 0 0 1 4.8-4.9 9 9 0 0 1 7 0 9 9 0 0 1 5.5 8.4" fill="#fff" fill-rule="evenodd"/>']
};
const tint=s=>s.replace(/#fff/g,'currentColor');
const ic=(k,cls='')=>{ const big=BIG[k]; return `<span class="ic ${cls}" data-ic="${k}"><svg viewBox="${big?big[0]:'0 0 24 24'}" aria-hidden="true">${tint(big?big[1]:I[k])}</svg></span>`; };

/* ================= colour maths (Palette.kt) ================= */
const rgbOf=c=>[(c>>16)&255,(c>>8)&255,c&255];
const intOf=(r,g,b)=>((Math.round(r)&255)<<16)|((Math.round(g)&255)<<8)|(Math.round(b)&255);
const hex=c=>'#'+(c>>>0).toString(16).padStart(6,'0').slice(-6);
const blend=(a,b,t)=>{const A=rgbOf(a),B=rgbOf(b);return intOf(A[0]+(B[0]-A[0])*t,A[1]+(B[1]-A[1])*t,A[2]+(B[2]-A[2])*t);};
const rgba=(c,a)=>{const[r,g,b]=rgbOf(c);return `rgba(${r},${g},${b},${a})`;};
const lum=c=>rgbOf(c).map(v=>{v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);}).reduce((s,v,i)=>s+v*[0.2126,0.7152,0.0722][i],0);
const contrast=(a,b)=>{const x=lum(a),y=lum(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05);};
const toLin=v=>v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4);
const toSrgb=v=>v<=0.0031308?12.92*v:1.055*Math.pow(v,1/2.4)-0.055;
function rgbToOklch(c){const[r,g,b]=rgbOf(c).map(v=>toLin(v/255));
  const l=Math.cbrt(0.4122214708*r+0.5363325363*g+0.0514459929*b),m=Math.cbrt(0.2119034982*r+0.6806995451*g+0.1073969566*b),s=Math.cbrt(0.0883024619*r+0.2817188376*g+0.6299787005*b);
  const L=0.2104542553*l+0.7936177850*m-0.0040720468*s,A=1.9779984951*l-2.4285922050*m+0.4505937099*s,B=0.0259040371*l+0.7827717662*m-0.8086757660*s;
  return [L,Math.hypot(A,B),(Math.atan2(B,A)*180/Math.PI+360)%360];}
function oklchToRgb(L,C,h){const a=C*Math.cos(h*Math.PI/180),b=C*Math.sin(h*Math.PI/180);
  const l=(L+0.3963377774*a+0.2158037573*b)**3,m=(L-0.1055613458*a-0.0638541728*b)**3,s=(L-0.0894841775*a-1.2914855480*b)**3;
  return [4.0767416621*l-3.3077115913*m+0.2309699292*s,-1.2684380046*l+2.6097574011*m-0.3413193965*s,-0.0041960863*l-0.7034186147*m+1.7076147010*s];}
function tone(T,h,c){const Y=T>8?((T+16)/116)**3:T/903.3,L=Math.cbrt(Y);let rgb;
  for(let k=c;k>=0;k-=0.004){rgb=oklchToRgb(L,k,h);if(rgb.every(v=>v>=-0.0005&&v<=1.0005))break;}
  return intOf(...rgb.map(v=>Math.min(1,Math.max(0,toSrgb(v)))*255));}
const SHADE={10:99,50:95,100:90,200:80,500:50,600:40,700:30,800:20,900:10};
function tonal(seed){const[,C,h]=rgbToOklch(seed);const a1=Math.min(0.13,Math.max(0.02,C)),n1=Math.min(0.012,C*0.12),n2=Math.min(0.024,C*0.24);
  const f=c=>s=>tone(SHADE[s],h,c);return {a1:f(a1),n1:f(n1),n2:f(n2)};}

/* ================= presets (res/values/themes.xml) ================= */
const BUILTIN={
  oled:{name:'OLED Black',bg:0x000000,card:0x000000,accent:0xD71920,text:0xFFFFFF,text2:0x9B9B9B,outline:0x2E2E30},
  dark:{name:'Classic Dark',bg:0x121214,card:0x232327,accent:0xD71920,text:0xFFFFFF,text2:0xA0A0A5,outline:0x34343A},
  white:{name:'White',bg:0xECECF0,card:0xFFFFFF,accent:0xD71920,text:0x17171A,text2:0x6A6A72,outline:0xDCDCE2}};
function wallpaperTokens(id,seed){const{a1,n1,n2}=tonal(seed);
  if(id==='white')return{bg:n2(50),card:n1(10),accent:a1(600),text:n1(900),text2:n2(700),outline:n2(200)};
  if(id==='dark')return{bg:n1(900),card:n1(800),accent:a1(200),text:n1(50),text2:n2(200),outline:n2(700)};
  return{bg:0,card:0,accent:a1(200),text:n1(50),text2:n2(200),outline:n2(800)};}
const GLASS_BGS=[{id:'midnight',name:'Midnight',blobs:[0x4A6DFF,0xC27BFF,0xFF9D6B,0x27D0C4]},{id:'aurora',name:'Aurora',blobs:[0x2BD99B,0x3B82F6,0x8B5CF6,0x22D3EE]},
  {id:'sunset',name:'Sunset',blobs:[0xFF6B6B,0xFFA94D,0xC2410C,0xEC4899]},{id:'ocean',name:'Ocean',blobs:[0x0EA5E9,0x1D4ED8,0x14B8A6,0x38BDF8]},
  {id:'accent',name:'Accent',blobs:null},{id:'black',name:'Black',blobs:null},{id:'none',name:'None',blobs:null}];
const QUICK=[0xD71920,0xFF9F0A,0x30D158,0x0A84FF,0xBF5AF2];
const WALLS=[
  {name:'Bloom',seed:0x4A5FD8,css:'radial-gradient(circle at 18% 22%,#3b4fd8 0,transparent 42%),radial-gradient(circle at 85% 30%,#c2410c 0,transparent 38%),radial-gradient(circle at 70% 85%,#7c3aed 0,transparent 45%),radial-gradient(circle at 15% 80%,#0f766e 0,transparent 40%),#0b0d14'},
  {name:'Fjord',seed:0x0E7490,css:'radial-gradient(circle at 80% 15%,#0e7490 0,transparent 45%),radial-gradient(circle at 15% 60%,#1d4ed8 0,transparent 45%),radial-gradient(circle at 60% 95%,#155e75 0,transparent 45%),#06121a'},
  {name:'Ember',seed:0xC2410C,css:'radial-gradient(circle at 25% 30%,#c2410c 0,transparent 45%),radial-gradient(circle at 80% 70%,#9f1239 0,transparent 45%),radial-gradient(circle at 60% 10%,#b45309 0,transparent 35%),#120806'},
  {name:'Grove',seed:0x3F8F3A,css:'radial-gradient(circle at 20% 70%,#15803d 0,transparent 45%),radial-gradient(circle at 80% 25%,#65a30d 0,transparent 40%),radial-gradient(circle at 55% 95%,#0f766e 0,transparent 40%),#07110a'},
  {name:'Stone',seed:0x6B6B73,css:'radial-gradient(circle at 30% 30%,#52525b 0,transparent 45%),radial-gradient(circle at 75% 75%,#3f3f46 0,transparent 45%),#0c0c0e'}];
const LANGS=['System default','English','简体中文','繁體中文','日本語','한국어','Bahasa Indonesia','Bahasa Melayu','Čeština','Deutsch','Español','Filipino','Français','Italiano','Magyar','Nederlands','Polski','Português','Română','Svenska','Tiếng Việt','Türkçe','Ελληνικά','Русский','Українська','हिन्दी','বাংলা','ไทย'];

/* ================= state ================= */
const DEF={fblog:true,view:'window',page:'overview',style:'glass',theme:'oled',auto:true,autoDark:'oled',sysLight:false,colorMode:'wallpaper',accent:null,presets:[],active:null,editTok:2,
  glassBg:'none',wall:0,uploadSeed:null,anc:1,level:1,ll:false,hq:true,sp:false,hp:true,dual:false,wear:false,smart:false,svc:true,hide:false,devtools:false,tray:true,count:true,upd:true,
  eqPreset:'GoodEQ',custom:['GoodEQ',null,null],cg:{GoodEQ:[-1,-1,1,3,2,6]},budState:'case',gains:[-1,-1,1,3,2,6],bass:true,bassLv:2,alert:85,showL:true,showR:true,lang:0,recent:[],model:'auto',size:'l',
  gest:{L:['none','play','none','vol','off','none','none'],R:['none','play','none','vol','off','none','none']}};
let S=JSON.parse(JSON.stringify(DEF)), uploadUrl=null;
try{const saved=JSON.parse(localStorage.getItem('qb-desktop')||'null');if(saved)S=Object.assign(S,saved,{view:'window'});}catch(e){}
try{S.sysLight=matchMedia('(prefers-color-scheme: light)').matches;}catch(e){}
if(S.wall==='upload')S.wall=0;
const save=()=>{try{localStorage.setItem('qb-desktop',JSON.stringify(S));}catch(e){}};
const seed=()=>S.wall==='upload'&&S.uploadSeed!=null?S.uploadSeed:WALLS[typeof S.wall==='number'?S.wall:0].seed;
const activeId=()=>S.auto?(S.sysLight?'white':S.autoDark):S.theme;
function palette(){let p,id=activeId();const pr=S.colorMode==='palette'?S.presets.find(x=>x.id===S.active):null;
  if(pr){p={...pr};id=lum(p.bg)>0.5?'white':'oled';}else if(S.colorMode==='wallpaper')p=wallpaperTokens(id,seed());else{p={...BUILTIN[id]};if(S.accent!=null)p.accent=S.accent;}
  p.id=id;p.isLight=lum(p.bg)>0.5;return p;}

/* ================= apply the palette (ThemeRes.palette + glassify) ================= */
let lastStyle=null;
// The knob of a slider: a ring of 9 x 9 dots in the accent colour, as an image (the phone's DotArt.knob).
function knobVar(color){const ring=['..#####..','.#.....#.','#.......#','#.......#','#.......#','#.......#','#.......#','.#.....#.','..#####..'],cells=[];
  ring.forEach((r,y)=>[...r].forEach((c,x)=>{if(c==='#')cells.push([x,y]);}));
  const svg=dotsSvg(cells,9,9,3).replace('<svg ','<svg xmlns="http://www.w3.org/2000/svg" ').replace('fill="currentColor"',`fill="${color}"`);
  document.documentElement.style.setProperty('--knob',`url("data:image/svg+xml;utf8,${encodeURIComponent(svg)}")`);}
function apply(){
  const p=palette(),root=document.documentElement,st=root.style,set=(k,v)=>st.setProperty(k,v),light=p.isLight,glass=S.style==='glass';
  const[lt,dk]=lum(p.text)>lum(p.bg)?[p.text,p.bg]:[p.bg,p.text];
  const onAccent=(contrast(lt,p.accent)>=3||contrast(lt,p.accent)>=contrast(dk,p.accent))?lt:dk;
  const track=light?blend(p.outline,p.text,0.14):blend(p.outline,p.text,0.06);
  const container=blend(blend(p.card,p.text,(!light&&lum(p.card)<0.005)?0.09:0),p.accent,light?0.05:0.08);
  knobVar(hex(p.accent));
  set('--bg',hex(p.bg));set('--card',hex(p.card));set('--accent',hex(p.accent));set('--track',hex(track));
  set('--thumb-off',light?hex(p.card):rgba(p.text,0.9));set('--container',hex(container));set('--tonal',hex(blend(container,p.accent,light?0.18:0.28)));
  if(glass){
    set('--text',light?'#14152b':'#ffffff');set('--ink',light?'#14152b':'#ffffff');set('--text2',light?'rgba(20,21,43,.7)':'rgba(255,255,255,.72)');set('--outline',light?'rgba(20,21,43,.16)':'rgba(255,255,255,.3)');
    const k=S.glassBg==='none'?0.45:0.7;
    set('--g-from',light?'rgba(255,255,255,.7)':`rgba(255,255,255,${(0.15*k/0.7).toFixed(3)})`);set('--g-to',light?'rgba(255,255,255,.45)':`rgba(255,255,255,${(0.04*k/0.7+0.01).toFixed(3)})`);
    set('--g-rim',light?'rgba(20,21,43,.1)':'rgba(255,255,255,.3)');set('--g-lit',light?'rgba(255,255,255,.8)':'rgba(255,255,255,.4)');
    set('--g-sel-from',rgba(p.accent,light?0.55:0.5));set('--g-sel-to',rgba(p.accent,light?0.38:0.3));set('--g-sel-rim',rgba(p.accent,0.85));set('--g-track',light?'rgba(20,21,43,.12)':'rgba(255,255,255,.18)');
    set('--on-accent',contrast(0xFFFFFF,p.accent)>=3?'#ffffff':'#14152b');
    const oled=p.id==='oled',base=light?0xF1F3FA:oled?0x050509:0x1B1D2B,a=light?[.34,.28,.24,.26]:oled?[.2,.14,.11,.12]:[.26,.19,.15,.17];
    // In the native window the colour base is partly see-through: the desktop (blurred by the system) shows behind it.
    const nat=root.classList.contains('native'),bs=nat?rgba(base,.5):hex(base);
    const ch=GLASS_BGS.find(g=>g.id===S.glassBg)||GLASS_BGS[0];let gb;
    if(ch.id==='none')gb=light?'rgba(241,243,250,.42)':oled?'rgba(5,5,9,.38)':'rgba(27,29,43,.45)';
    else if(ch.id==='black')gb=nat?rgba(light?0xF4F5FA:oled?0x000000:0x1B1D26,.72):hex(light?0xF4F5FA:oled?0x000000:0x1B1D26);
    else{const c=ch.blobs||[p.accent,blend(p.accent,0x4A6DFF,.5),blend(p.accent,0xFF9D6B,.5),blend(p.accent,0xFFFFFF,.35)],pos=['12% 18%','88% 12%','78% 92%','8% 95%'];
      gb=c.map((col,i)=>`radial-gradient(circle at ${pos[i]},${rgba(col,a[i])} 0,${rgba(col,0)} 55%)`).join(',')+','+bs;}
    set('--glass-bg',gb);
    // A dialog uses the app's background with a backing layer: less see-through, never solid.
    set('--dlg-bg',`linear-gradient(${rgba(base,.4)},${rgba(base,.4)}),${gb}`);
  }else{set('--text',hex(p.text));set('--ink',hex(p.text));set('--text2',hex(p.text2));set('--outline',hex(p.outline));set('--on-accent',hex(onAccent));}
  const d=root.dataset;d.style=S.style;d.glassbg=S.glassBg;d.light=light?'1':'0';
  // Only the pixel style has a larger window (a fixed size, the user cannot resize it): the other styles are made for 800 x 600.
  if(window.__TAURI__&&root.classList.contains('native')&&apply._px!==(S.style==='pixel')){apply._px=S.style==='pixel';window.__TAURI__.core.invoke('main_size',{width:apply._px?1060:800,height:apply._px?760:600});
    // The content dips while the window moves, so the change reads as one motion.
    const app=document.querySelector('.app');if(app&&!reduce()){app.classList.add('resizing');clearTimeout(apply._rt);apply._rt=setTimeout(()=>app.classList.remove('resizing'),330);}}
  document.getElementById('desk').style.background=S.wall==='upload'&&uploadUrl?`center/cover no-repeat url("${uploadUrl}")`:WALLS[typeof S.wall==='number'?S.wall:0].css;
  sync(p);
  if(S.style==='pixel'&&lastStyle!=='pixel')requestAnimationFrame(()=>pixelize(document));
  lastStyle=S.style;save();
}

/* ================= Pixel art: a port of DotArt.kt =================
   A drawing is rendered at one pixel per cell; every covered cell becomes one round dot (radius 0.42 pitch). */
const DPR=Math.max(1,window.devicePixelRatio||1);
const pitchPx=dp=>Math.max(2,Math.round(dp*DPR))/DPR;
// The phone's values (DotArt.kt): 2.2 dp for boxes, rings and switches, 1.2 dp for icons. A screen at 1x cannot draw less than 1 px.
// 1.2 dp for icons as on the phone. Boxes, rings and switches use 2.6 dp: at 1x a pitch below 3 px merges the dots into lines.
const PITCH=pitchPx(2.6),ICON_PITCH=pitchPx(1.2),SOLID_MIN=90,MIN_ALPHA=50;
document.documentElement.style.setProperty('--dp',PITCH+'px');document.documentElement.style.setProperty('--dr',(PITCH*0.42).toFixed(2)+'px');
const cv=document.createElement('canvas'),cx=cv.getContext('2d',{willReadFrequently:true});
function raster(cols,rows,draw){cv.width=cols;cv.height=rows;cx.setTransform(1,0,0,1,0,0);cx.clearRect(0,0,cols,rows);cx.fillStyle='#fff';cx.strokeStyle='#fff';draw(cx);return cx.getImageData(0,0,cols,rows).data;}
function dotsSvg(cells,cols,rows,pitch,cls=''){let s='';for(const[x,y,o]of cells)s+=`<circle cx="${((x+.5)*pitch).toFixed(2)}" cy="${((y+.5)*pitch).toFixed(2)}" r="${(pitch*.42).toFixed(2)}"${o!=null?` class="${o}"`:''}/>`;
  const w=(cols*pitch).toFixed(2),h=(rows*pitch).toFixed(2);return `<svg class="dz ${cls}" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" fill="currentColor" aria-hidden="true">${s}</svg>`;}
function lit(data,cols,rows,min){const out=[];for(let y=0;y<rows;y++)for(let x=0;x<cols;x++)if(data[(y*cols+x)*4+3]>=min)out.push([x,y]);return out;}
/* DotArt.Box: the outline one cell of dots */
function boxDots(w,h,r){const cols=Math.floor(w/PITCH),rows=Math.floor(h/PITCH);if(cols<2||rows<2)return '';const rr=Math.max(0,r/PITCH);
  const d=raster(cols,rows,c=>{c.beginPath();c.roundRect(0,0,cols,rows,Math.min(rr,cols/2,rows/2));c.fill();c.globalCompositeOperation='destination-out';c.beginPath();c.roundRect(1,1,cols-2,rows-2,Math.max(0,Math.min(rr-1,(cols-2)/2,(rows-2)/2)));c.fill();c.globalCompositeOperation='source-over';});
  return dotsSvg(lit(d,cols,rows,SOLID_MIN),cols,rows,PITCH,'');}
/* DotArt.Pattern: icons drawn from a rule, so every dot is the same and every shape symmetric */
const seg=(px,py,ax,ay,bx,by,t)=>{const dx=bx-ax,dy=by-ay,u=Math.min(1,Math.max(0,((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy)));if(t)t[0]=u;return Math.hypot(px-(ax+u*dx),py-(ay+u*dy));};
const strokes=(r,...ss)=>[15,15,(x,y)=>ss.some(s=>seg(x+.5,y+.5,...s)<=r)];
const PATTERNS={
  tap1:[5,5,(x,y)=>{const dx=x%6-2,dy=y-2;return x%6<5&&dx*dx+dy*dy<=6;}],
  tap2:[11,5,(x,y)=>{const dx=x%6-2,dy=y-2;return x%6<5&&dx*dx+dy*dy<=6;}],
  tap3:[17,5,(x,y)=>{const dx=x%6-2,dy=y-2;return x%6<5&&dx*dx+dy*dy<=6;}],
  hold:[15,15,(x,y)=>{const d=Math.hypot(x-7,y-7);return d<=2.3||(d>=5.6&&d<=7.2);}],
  close:strokes(1.0,[2.5,2.5,12.5,12.5],[12.5,2.5,2.5,12.5]),
  check:strokes(0.8,[2.5,8,6,11.5],[6,11.5,12.5,4]),
  pencil:[15,15,(x,y)=>{const t=[0];const d=seg(x+.5,y+.5,2.5,12.5,12,3,t);return d<=0.5+1.4*t[0];}],
  del:[15,15,(x,y)=>y===1?x>=5&&x<=9:y===2?x===5||x===9:(y===3||y===4)?x>=1&&x<=13:(y>=6&&y<=13)?x>=2&&x<=12:false],
  cog:[17,17,(x,y)=>{const dx=x-8,dy=y-8,d=Math.hypot(dx,dy),a=Math.atan2(dy,dx)+Math.PI/2,off=Math.abs(a-Math.round(a/(Math.PI/3))*(Math.PI/3));return (d>=3.6&&d<=5.9)||(d>=5.5&&d<=8.4&&off<=0.27);}],
  x:strokes(1.0,[2.5,2.5,12.5,12.5],[12.5,2.5,2.5,12.5]),
  min:[15,15,(x,y)=>y===7&&x>=3&&x<=11],
  max:[15,15,(x,y)=>(x>=3&&x<=11&&y>=3&&y<=11)&&(x===3||x===11||y===3||y===11)]
};
/* Hand-drawn pixel icons, 9 x 9 cells: a sampled vector has too few dots at 1x and turns into a blob. */
const bm=r=>[r[0].length,r.length,(x,y)=>r[y][x]==='#'];
const HAND={
  battery:bm(['.........','.........','########.','#......#.','#.####.##','#.####.##','#......#.','########.','.........']),
  palette:bm(['..#####..','.#.....#.','#..#....#','#......#.','#.#.....#','#.....#.#','.#.....#.','..#####..','.........']),
  off:bm(['..#####..','.#.....#.','#.#.....#','#..#....#','#...#...#','#....#..#','#.....#.#','.#.....#.','..#####..']),
  anc:bm(['..#####..','.#.....#.','#..###..#','#.#...#.#','#.#.#.#.#','#.#...#.#','#..###..#','.#.....#.','..#####..']),
  adaptive:bm(['....#....','....#....','...#.#...','..#...#..','###...###','..#...#..','...#.#...','....#....','....#....']),
  transp:bm(['....#....','....#....','..#.#.#..','..#.#.#..','#.#.#.#.#','..#.#.#..','..#.#.#..','....#....','....#....']),
  low:bm(['.........','.........','..#####..','..#...#..','..#.#.#..','..#...#..','..#####..','.........','.........']),
  high:bm(['..#####..','.#######.','##.....##','##.###.##','##.###.##','##.###.##','##.....##','.#######.','..#####..']),
  smart:bm(['..#####.#','.#.....#.','#..###..#','#.#...#.#','#.#.#.#.#','#.#...#.#','#..###..#','.#.....#.','..#####..']),
  bolt:bm(['....###..','...##....','..##.....','.#####...','...##....','..##.....','.##......','..#......','.........']),
  hires:bm(['.........','######..#','........#','####....#','........#','##......#','........#','........#','.........']),
  spatial:bm(['.#######.','#.......#','...###...','..#...#..','.........','....#....','...###...','...#.#...','...#.#...']),
  hearing:bm(['..####...','.#....#..','#..##..#.','#.#..#.#.','#.#..#.#.','#..#.#.#.','.....##..','...#.....','...##....']),
  eq:bm(['.........','.........','....#....','....#....','..#.#.#..','..#.#.#..','#.#.#.#.#','#.#.#.#.#','#.#.#.#.#']),
  devices:bm(['#######..','#.....#..','#.....###','#.....#.#','#######.#','..###...#','..###..##','..###....','.........']),
  gesture:bm(['...##....','...##....','...##....','...##.##.','...######','#..######','.########','.#######.','..#####..']),
  earbud:bm(['..####...','.######..','.######..','.######..','..####...','...###...','...###...','...###...','....##...']),
  find:bm(['..#####..','.#######.','##.###.##','##.###.##','.#######.','..#####..','...###...','....#....','.........']),
  power:bm(['....#....','..#.#.#..','.#..#..#.','#...#...#','#.......#','#.......#','.#.....#.','..#####..','.........']),
  dev:bm(['.........','#........','.#.......','..#......','.#.......','#........','.....####','.........','.........']),
  info:bm(['..#####..','.#.....#.','#...#...#','#.......#','#...#...#','#...#...#','#...#...#','.#.....#.','..#####..']),
  chev:bm(['.........','..#......','...#.....','....#....','.....#...','....#....','...#.....','..#......','.........']),
  back:bm(['.........','....#....','...#.....','..#......','.#######.','..#......','...#.....','....#....','.........']),
  plus:bm(['.........','....#....','....#....','....#....','.#######.','....#....','....#....','....#....','.........']),
  update:bm(['....#....','....#....','....#....','..#.#.#..','...###...','....#....','.........','#########','.........']),
  language:bm(['..#####..','.#..#..#.','#...#...#','#########','#...#...#','#...#...#','.#..#..#.','..#####..','.........']),
  chat:bm(['.........','#########','#.......#','#.......#','#.......#','#########','..#......','.#.......','.........']),
  warn:bm(['....#....','...#.#...','...#.#...','..#.#.#..','..#.#.#..','.#..#..#.','.#.....#.','#########','.........']),
  volume:bm(['.........','...#.....','..##..#..','.###...#.','####...#.','.###...#.','..##..#..','...#.....','.........'])
};
Object.assign(PATTERNS,HAND);
function patternDots(key,size){const[cols,rows,f]=PATTERNS[key];const cells=[];for(let y=0;y<rows;y++)for(let x=0;x<cols;x++)if(f(x,y))cells.push([x,y]);
  const p=HAND[key]?Math.max(2,Math.round(size/Math.max(cols,rows)*DPR))/DPR:Math.min(ICON_PITCH*Math.max(1,size/24),size/Math.max(cols,rows)*1.15);return dotsSvg(cells,cols,rows,p);}
/* DotArt.Icon: the vector sampled at the icon pitch (dots grow past 24px, so the count stays the same) */
const iconCache=new Map();
function iconDots(key,w,h){const id=key+'|'+w+'x'+h;if(iconCache.has(id))return iconCache.get(id);
  const pr=new Promise(res=>{const big=BIG[key],vb=big?big[0]:'0 0 24 24',body=big?big[1]:I[key];
    const scale=Math.max(1,h/24),pitch=ICON_PITCH*scale,cols=Math.max(1,Math.round(w/pitch)),rows=Math.max(1,Math.round(h/pitch));
    const img=new Image();img.onload=()=>{const d=raster(cols,rows,c=>c.drawImage(img,0,0,cols,rows));res(dotsSvg(lit(d,cols,rows,SOLID_MIN),cols,rows,pitch));};
    img.onerror=()=>res('');img.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}" width="${cols}" height="${rows}" preserveAspectRatio="xMidYMid meet">${body}</svg>`);});
  iconCache.set(id,pr);return pr;}
/* rings: track dots dim, the charged arc bright (BudsStatusView in the dot style) */
function ringDots(size,pct){const cols=Math.floor(size/PITCH),c0=cols/2,R=c0-1.6,lw=2.6;
  const tr=raster(cols,cols,c=>{c.lineWidth=lw;c.beginPath();c.arc(c0,c0,R,0,Math.PI*2);c.stroke();});
  const trl=lit(tr,cols,cols,MIN_ALPHA*2);
  const fl=raster(cols,cols,c=>{c.lineWidth=lw;c.lineCap='round';c.beginPath();c.arc(c0,c0,R,-Math.PI/2,-Math.PI/2+Math.PI*2*pct/100);c.stroke();});
  const on=new Set(lit(fl,cols,cols,MIN_ALPHA*2).map(([x,y])=>x+','+y));
  return dotsSvg(trl.map(([x,y])=>[x,y,on.has(x+','+y)?null:'tr']),cols,cols,PITCH);}
/* switch parts (DotArt.Part): the track a pill of dots, the thumb a disc */
function swDots(w,h){const cols=Math.floor(w/PITCH),rows=Math.floor(h/PITCH);
  const t=raster(cols,rows,c=>{c.beginPath();c.roundRect(0,0,cols,rows,rows/2);c.fill();c.globalCompositeOperation='destination-out';c.beginPath();c.roundRect(1,1,cols-2,rows-2,(rows-2)/2);c.fill();c.globalCompositeOperation='source-over';});
  const n=rows-2,m=(n-1)/2,disc=[];for(let y=0;y<n;y++)for(let x=0;x<n;x++)if((x-m)**2+(y-m)**2<=n*n/4)disc.push([x+1,y+1]);
  return {track:dotsSvg(lit(t,cols,rows,SOLID_MIN),cols,rows,PITCH,'trk'),knob:dotsSvg(disc,n+2,n+2,PITCH,'knb'),kx:(cols-n-2)*PITCH};}
const BOXSEL='.card,.group,.btn,.chip,.qt,.seg .ind,.dlg,.lg,.lang,.tok,.navind,.optind';
const ro=new ResizeObserver(es=>{if(S.style!=='pixel')return;for(const e of es)drawBox(e.target);});
function drawBox(el){const w=el.offsetWidth,h=el.offsetHeight;if(!w||!h)return;const key=w+'x'+h;if(el._dzk===key)return;el._dzk=key;
  const r=parseFloat(getComputedStyle(el).borderTopLeftRadius)||0;let o=el.querySelector(':scope>.dzb');
  if(!o){o=document.createElement('span');o.className='dzb';el.prepend(o);}o.innerHTML=boxDots(w,h,r);const sv=o.firstChild;if(sv){o.style.width=w+'px';o.style.height=h+'px';sv.classList.add('dzb-s');}}
function pixelize(root){if(S.style!=='pixel')return;
  root.querySelectorAll(BOXSEL).forEach(el=>{if(!el._ro){ro.observe(el);el._ro=1;}el._dzk=null;drawBox(el);});
  root.querySelectorAll('.ic[data-ic]').forEach(el=>{const k=el.dataset.ic,w=el.clientWidth||20,h=el.clientHeight||20;if(!w)return;const id=k+w+'x'+h;if(el._dz===id)return;el._dz=id;
    const put=svg=>{el.querySelector(':scope>.dz')?.remove();el.insertAdjacentHTML('beforeend',svg);el.classList.add('dotted');};
    // A sampled icon below 40 px has about 10 dots across at 1x: the dots merge into a blob. It stays a clean vector there.
    if(PATTERNS[k]&&(!HAND[k]||Math.min(w,h)>=24))put(patternDots(k,Math.min(w,h)));else if(!PATTERNS[k]&&Math.min(w,h)>=40)iconDots(k,w,h).then(put);});
  root.querySelectorAll('.ring').forEach(el=>{const s=el.clientWidth;if(!s||el._dz===s)return;el._dz=s;el.querySelector(':scope>.dz')?.remove();
    el.insertAdjacentHTML('beforeend',ringDots(s,+el.dataset.pct));const g=BIG[el.dataset.g],gw=s*(el.dataset.g==='case'?.48:.3),gh=s*(el.dataset.g==='case'?.39:.46);
    iconDots(el.dataset.g,Math.round(gw),Math.round(gh)).then(svg=>{const box=document.createElement('span');box.className='ic glyph '+(el.dataset.g==='case'?'case':'');box.style.cssText=`position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:${gw}px;height:${gh}px`;
      box.innerHTML=svg;const old=el.querySelector(':scope>.ic.glyph');old?.remove();el.appendChild(box);});});
  root.querySelectorAll('.sw').forEach(el=>{const w=el.offsetWidth,h=el.offsetHeight;if(!w||el._dz===w+'x'+h)return;el._dz=w+'x'+h;el.querySelectorAll(':scope>.dz').forEach(x=>x.remove());
    const d=swDots(w,h);el.insertAdjacentHTML('beforeend',d.track+d.knob);el.style.setProperty('--kx',d.kx+'px');});
}

/* ================= markup builders ================= */
function ring(g,pct,label,sub){const none=pct==null;if(none)pct=0;const len=213.6,off=(len*(1-pct/100)).toFixed(1),cs=g==='case';
  return `<div class="ringc"><div class="ring" data-pct="${pct}" data-g="${g}"><svg viewBox="0 0 76 76" role="img" aria-label="${label} ${pct} percent"><circle class="rt" cx="38" cy="38" r="34"/><circle class="rf" cx="38" cy="38" r="34" stroke-dasharray="${len} ${len}" stroke-dashoffset="${off}"/></svg><svg viewBox="${BIG[g][0]}" class="glyph ${cs?'case':''}" style="left:${cs?26:35}%;top:${cs?30.5:27}%;width:${cs?48:30}%;height:${cs?39:46}%" aria-hidden="true">${tint(BIG[g][1])}</svg></div><div class="rtx"><div class="pct">${none?'–':pct+'%'}</div>${sub?`<div class="s">${sub}</div>`:''}</div></div>`;}

function segm(key,items,extra=''){return `<div class="seg ${extra}" data-seg="${key}" role="radiogroup" style="--n:${items.length}"><span class="ind"></span>${items.map(([i,l],n)=>`<button class="cell press" role="radio" data-act="pick" data-k="${key}" data-v="${n}">${i?ic(i):''}${l}</button>`).join('')}</div>`;}
const ancCard=()=>`<div class="card">${segm('anc',[['off',$t('anc_seg_off')],['anc',$t('anc_seg_anc')],['adaptive',$t('anc_seg_adapt')],['transp',$t('anc_seg_trans')]],'big')}${segm('level',[['low',$t('anc_mode_low')],['anc',$t('anc_mode_medium')],['high',$t('anc_mode_high')],['smart',$t('anc_mode_smart')]],'lvls big')}</div>`;
const LBL={};
const lbl=k=>({ll:$t('row_game_title'),hq:$t('row_hires_title'),sp:$t('row_spatial_title'),hp:$t('row_golden_title'),auto:$t('theme_auto'),dual:$t('dual_title'),wear:$t('wear_firmware_title'),svc:$t('desktop_autostart_title','Start with your computer'),hide:$t('settings_clear_battery_title'),devtools:$t('settings_devtools_title'),count:$t('settings_ping_title'),upd:$t('update_auto_title'),bass:$t('eq_basswave'),wind:$t('row_wind_noise_title'),fblog:$t('problem_log_title')})[k]||k;
const sw=k=>`<button class="sw" role="switch" data-act="toggle" data-k="${k}" aria-label="${lbl(k)}"></button>`;
const swRow=(i,t,s,k)=>`<div class="row">${i?ic(i):''}<div class="txt"><div class="t">${t}</div>${s?`<div class="s">${s}</div>`:''}</div>${sw(k)}</div>`;
const navRow=(i,t,s,act)=>`<button class="row press" ${act}>${ic(i)}<div class="txt"><div class="t">${t}</div>${s?`<div class="s">${s}</div>`:''}</div>${ic('chev','ch')}</button>`;
const srow=(t,s,ctrl,id='')=>`<div class="srow"${id?` id="${id}"`:''}><div class="txt"><div class="t">${t}</div>${s?`<div class="s">${s}</div>`:''}</div><div class="ctrl">${ctrl}</div></div>`;
const back=(to,label)=>`<button class="btn icon press" data-act="page" data-v="${to}" aria-label="Back to ${label}">${ic('back')}</button>`;
const PAGES=[['overview','battery','desktop_nav_overview'],['eq','eq','eq_title'],['hearing','hearing','row_golden_title'],['gestures','gesture','row_gesture_title'],['earbud','earbud','earbuds_title']];
const APPPAGES=[['appearance','palette','settings_appearance'],['settings','cog','desktop_nav_app_settings'],['update','update','update_title'],['about','info','settings_about_title']];
const NAVOF={};
const ORDER=['overview','eq','hearing','gestures','earbud','appearance','settings','update','about','dev','feedback','test'];

/* equalizer geometry */
const FREQS=['62','250','1k','4k','8k','16k'];
function curvePath(g,w,h,closed){const pts=g.map((v,i)=>[(i+.5)*w/g.length,h/2-v*(h/2)/6.6]);let d=`M0 ${pts[0][1]} L${pts[0][0]} ${pts[0][1]}`;
  for(let i=0;i<pts.length-1;i++){const[x0,y0]=pts[i],[x1,y1]=pts[i+1],mx=(x0+x1)/2;d+=` C${mx} ${y0} ${mx} ${y1} ${x1} ${y1}`;}d+=` L${w} ${pts[pts.length-1][1]}`;return closed?d+` L${w} ${h} L0 ${h} Z`:d;}
const PRESETS={'Balanced':[0,0,0,0,0,0],'Clear Vocals':[-2,-1,2,3,2,0],'Bass':[5,3,0,-1,0,1]};
const EQH=150;

function buildWindow(){
  const nav=([id,i,k])=>{const l=$t(k);return `<button class="nav" data-act="page" data-v="${id}" title="${l}">${ic(i)}<span class="nl">${l}</span></button>`;};
  document.getElementById('win').innerHTML=`
  <div class="caps"><button class="cap" aria-label="Minimize">${ic('min')}</button><button class="cap" aria-label="Maximize">${ic('max')}</button><button class="cap x" aria-label="Close">${ic('x')}</button></div>
  <nav class="side" aria-label="QuickBuds">
    <span class="navind" aria-hidden="true"></span>
    <div class="logo">${ic('budL')}<span class="nl">QuickBuds</span></div>
    ${PAGES.map(nav).join('')}
    <div class="navlbl">${$t('settings_app')}</div>
    ${APPPAGES.map(nav).join('')}
    <div style="flex:1"></div>
    ${nav(['dev','dev','action_dev_tools'])}
    ${nav(['feedback','chat','problem_title'])}
    ${nav(['test','earbud','test_title'])}
    <div class="connf"><span class="cdot"></span><div class="txt"><div class="t" data-cn>QuickBuds</div><div class="s" data-cs>Disconnected</div></div>
      <button class="btn icon press" data-act="conn" data-conn aria-label="Connect or disconnect">${ic('power')}</button></div>
  </nav>
  <main class="main" id="main">

  <section class="page" data-page="overview">
    <div class="card budsw" style="--i:0">
      <div class="bwh"><div style="flex:1;min-width:0"><button class="mname press" data-act="model" aria-label="${$t('model_title')}"><span data-model>OnePlus Buds 4</span>${ic('chev','dn')}</button></div></div>
      <div class="bwr" data-buds></div>
    </div>
    ${ancCard()}
    <div class="cols">
      <div class="col"><div class="lbl">${$t('earbuds_section_sounds')}</div><div class="group">${swRow('bolt',$t('row_game_title'),'','ll')}${swRow('hires',$t('row_hires_title'),'','hq')}${swRow('spatial',$t('row_spatial_title'),'','sp')}${swRow('anc',$t('row_wind_noise_title'),'','wind')}${swRow('hearing',$t('row_golden_title'),'','hp')}</div></div>
      <div class="col"><div class="lbl">${$t('earbuds_section_features')}</div><div class="group">${swRow('devices',$t('row_dual_title'),'','dual')}${swRow('earbud',$t('wear_firmware_title'),'','wear')}${swRow('eq',$t('eq_basswave'),'','bass')}
        <button class="row press" data-act="page" data-v="eq">${ic('eq')}<div class="txt"><div class="t">${$t('row_eq_title')}</div></div><span class="v" data-eqname></span>${ic('chev','ch')}</button></div></div>
    </div>
  </section>

  <section class="page" data-page="eq" hidden>
    <div class="ph"><h2>${$t('eq_title')}</h2><button class="btn icon press" aria-label="${$t('eq_rename')}">${ic('pencil')}</button><button class="btn icon press" data-act="eqdel" aria-label="${$t('eq_delete')}">${ic('del')}</button><button class="btn pri press" data-act="eqsave">${$t('eq_save')}</button></div>
    <div class="card pad">
      <div class="t" data-eqname style="font-size:14px"></div>
      <div class="eqed" id="eqed"><div class="axis"><span>+6 dB</span><span>0 dB</span><span>−6 dB</span></div><div class="zero"></div>
        <svg class="curve" viewBox="0 0 600 ${EQH}" preserveAspectRatio="none" aria-hidden="true"><path class="fillp"/><path class="ln"/></svg>
        <div class="bands">${FREQS.map((f,i)=>`<div class="band"><div class="rail"></div><div class="bar"></div><button class="knob" data-band="${i}" aria-label="${f} Hz band" role="slider" aria-valuemin="-6" aria-valuemax="6"></button><div class="val"></div><div class="hz">${f}</div></div>`).join('')}</div></div>
    </div>
    <div class="cols">
      <div class="col"><div class="lbl">${$t('eq_recommended')}</div><div class="group" id="eqRec"></div></div>
      <div class="col"><div class="lbl">${$t('eq_custom')}</div><div class="group" id="eqCus"></div></div>
    </div>
    <div class="group"><div class="row">${ic('eq')}<div class="txt"><div class="t">${$t('eq_basswave')}</div></div><input class="range short" id="bassR" type="range" min="0" max="3" step="1" aria-label="${$t('eq_basswave')}"><span class="v" id="bassV" style="width:20px;text-align:right">+2</span>${sw('bass')}</div></div>
  </section>

  <section class="page" data-page="hearing" hidden>
    <div class="ph"><h2>${$t('row_golden_title')}</h2></div>
    <div class="card pad hcard"><div class="hhead"><div class="txt"><div class="t">${$t('row_golden_title')}</div><div class="s">${$t('row_golden_sub')}</div></div>${sw('hp')}</div><div class="hearw">
      <div class="hcol"><svg class="radar" viewBox="-34 0 368 270" role="img" aria-label="${$t('golden_boost')}" id="radar"></svg>
        <div class="legend"><button class="lg press" data-act="ear" data-v="showL"><i></i>${$t('status_left')}</button><button class="lg r press" data-act="ear" data-v="showR"><i></i>${$t('status_right')}</button></div></div>
      <div class="hcol"><div class="t" style="margin-bottom:10px">${$t('golden_boost')}</div><div class="freq" id="freq"></div></div>
    </div></div>
    <div class="lbl">${$t('golden_profiles')}</div>
    <div class="tiles3" id="hprof"></div>
  </section>

  <section class="page" data-page="gestures" hidden>
    <div class="ph"><div class="grow"><h2>${$t('row_gesture_title')}</h2></div><span class="s">${$t('gesture_write_note')}</span></div>
    <div class="card gtc"><table class="gt" id="gt"></table></div>
  </section>

  <section class="page" data-page="earbud" hidden>
    <div class="ph"><h2>${$t('earbuds_title')}</h2><span class="s" style="margin:0">${$t('model_title')}</span><button class="sel press pick" data-act="model" style="width:250px"><span data-modelfull>Automatic</span></button></div>
    <div class="tiles" id="earTiles">
      <div class="card tile" id="tileFind">${ic('find')}<div class="txt"><div class="t">${$t('row_find_title')}</div><div class="s">${$t('row_find_sub')}</div></div><button class="btn pri press" data-act="find">${$t('find_play')}</button></div>
      <div class="card tile" id="tileFit">${ic('earbud')}<div class="txt"><div class="t">${$t('fit_title')}</div></div><button class="btn pri press" data-act="fit">${$t('test_start')}</button></div>
    </div>
    <div class="stack">
      <div id="earSnd"><div class="lbl">${$t('earbuds_section_sounds')}</div><div class="group">
        <div class="row" id="wearRow">${ic('earbud')}<div class="txt"><div class="t">${$t('wear_firmware_title')}</div><div class="s">${$t('wear_firmware_sub')}</div></div>${sw('wear')}</div>
        <div class="row" id="alertRow">${ic('volume')}<div class="txt"><div class="t">${$t('row_alert_title')}</div></div><input class="range short" id="alertR" type="range" min="0" max="100" aria-label="${$t('row_alert_title')}"></div></div></div>
      <div id="tapBox"><div class="lbl">${$t('tap_level_title')}</div><div class="group"><div class="row" style="flex-wrap:wrap"><div class="txt" style="flex-basis:100%"><div class="s wrapok">${$t('tap_level_hint')}</div></div><input class="range" id="tapR" type="range" min="1" max="5" step="1" style="flex:1" aria-label="${$t('tap_level_title')}"><span class="v" id="tapV" style="width:20px;text-align:right">3</span><div class="s wrapok" id="tapWarn" style="flex-basis:100%" hidden>${$t('tap_level_warning')}</div></div></div></div>
      <div id="earFeatBox"><div class="lbl">${$t('earbuds_section_features')}</div><div class="group" id="earFeat"></div></div>
      <div><div class="lbl">${$t('dual_title')}</div><div class="card dualw" id="dualW">
        <div class="row">${ic('devices')}<div class="txt"><div class="t">${$t('dual_title')}</div><div class="s">${$t('dual_switch_sub')}</div></div>${sw('dual')}</div>
        <div class="devs" id="devs"></div></div></div>
      <div id="fwBox"><div class="lbl">${$t('earbuds_section_about')}</div><div class="group"><button class="row press" data-act="fwinfo">${ic('info')}<div class="txt"><div class="t">${$t('row_firmware_title')}</div></div><span class="v mono">–</span>${ic('chev','ch')}</button></div></div>
    </div>
  </section>

  <section class="page" data-page="dev" hidden>
    <div class="ph"><h2>${$t('action_dev_tools')}</h2><button class="btn press" data-act="devreconnect">${$t('conn_action_connect')}</button><button class="btn press" data-act="devdisc">${$t('conn_action_disconnect')}</button><button class="btn press" data-act="devcopy">${$t('desktop_copy_log')}</button></div>
    <div class="card pad devlog" id="devlog" role="log" aria-label="Packet log"></div>
  </section>

  <section class="page" data-page="feedback" hidden>
    <div class="ph"><h2>${$t('problem_title')}</h2></div>
    <div class="cols st">
      <div class="card pad fcol"><div class="t">${$t('problem_title')}</div>
        <label class="s" for="fbModel" style="margin:0">${$t('problem_model')}</label><input id="fbModel" class="field" value="">
        <label class="s" for="fbDesc" style="margin:0">${$t('problem_description_hint')}</label><textarea id="fbDesc" class="field" style="flex:1"></textarea></div>
      <div class="card pad fcol"><div class="t">${$t('problem_category')}</div>
        <div class="qts">${['problem_cat_ui','problem_cat_lag','problem_cat_connection','problem_cat_feature','problem_cat_battery','problem_cat_other'].map((c,i)=>`<button class="qt press" role="switch" data-act="fbchip" aria-checked="false"><span>${$t(c)}</span></button>`).join('')}</div>
        <div class="s" style="margin:0">${$t('problem_capture_hint')}</div>
        <div class="row" style="padding:0">${sw('fblog')}<div class="txt"><div class="t">${$t('problem_log_title')}</div><div class="s">${$t('problem_log_sub')}</div></div></div></div>
    </div>
    <div class="ph"><span class="s" id="fbNote" style="flex:1;margin:0;white-space:pre-line">${$t('problem_intro')}</span><button class="btn press" data-act="fbprev">${$t('problem_preview')}</button><button class="btn pri press" id="fbSend" data-act="fbsend">${$t('problem_send')}</button></div>
  </section>

  <section class="page" data-page="test" hidden>
    <div class="ph"><h2>${$t('test_title')}</h2></div>
    <div id="testBox"></div>
    <div class="ph"><span class="s" id="testNote" style="flex:1;margin:0"></span><button class="btn press" id="testStart" data-act="tstart">${$t('test_start')}</button><button class="btn press" id="testPrev" data-act="tprev" hidden>${$t('problem_preview')}</button><button class="btn pri press" id="testSend" data-act="tsend" hidden>${$t('problem_send')}</button></div>
    <span id="testFoot" hidden></span>
  </section>

  <section class="page" data-page="appearance" hidden>
    <div class="ph"><h2>${$t('settings_appearance')}</h2></div>
    <div class="group">
      ${srow($t('widget_style_title'),'',segm('style',[[0,$t('widget_style_material')],[0,$t('widget_style_classic')],[0,$t('widget_style_nothing')],[0,$t('widget_style_glass')]],'txt'))}
      ${srow($t('preset_colors'),'',segm('colorMode',[[0,$t('theme_wallpaper')],[0,$t('theme_accent')],[0,$t('eq_custom')]],'txt'))}
      <div class="srow cpanel" id="colorPanel"></div>
      ${srow($t('theme_builtin'),'',segm('theme',[[0,$t('theme_oled_black')],[0,$t('theme_classic_dark')],[0,$t('theme_white')]],'txt'),'themeRow')}
      ${srow($t('theme_auto'),$t('theme_auto_sub').replace('%1$s',$t('theme_white')).replace('%2$s',$t('theme_oled_black')),sw('auto'),'autoRow')}
      ${srow($t('theme_glass_gradient'),$t('theme_glass_gradient_sub'),'<div class="gsws" id="gbSw"></div>','gbRow')}
    </div>
  </section>

  <section class="page" data-page="settings" hidden>
    <div class="ph"><h2>${$t('desktop_nav_app_settings')}</h2></div>
    <div class="group">
      <div class="row">${ic('language')}<div class="txt"><div class="t">${$t('settings_language_title')}</div></div><button class="sel press pick" data-act="langdlg" id="langBtn" style="width:200px">${$t('language_system')}</button></div>
      ${swRow('power',$t('desktop_autostart_title','Start with your computer'),$t('desktop_autostart_sub','Opens in the tray when you sign in'),'svc')}
      ${swRow('battery',$t('settings_clear_battery_title'),$t('settings_clear_battery_sub'),'hide')}
      ${swRow('dev',$t('settings_devtools_title'),$t('desktop_devtools_sub','Show Dev tools in the menu on the left'),'devtools')}
      ${swRow('info',$t('settings_ping_title'),$t('settings_ping_sub'),'count')}
      <button class="row press" data-act="known">${ic('warn')}<div class="txt"><div class="t">${$t('settings_issues_title')}</div><div class="s">${$t('settings_issues_sub')}</div></div>${ic('chev','ch')}</button>
    </div>
  </section>

  <section class="page narrow" data-page="update" hidden>
    <div class="ph"><h2>${$t('update_title')}</h2></div>
    <div class="card pad updc"><div class="updv"><div><div class="s">${$t('update_installed')}</div><div class="pct" id="updInst">–</div></div><div><div class="s">${$t('update_latest')}</div><div class="pct" id="updLatest">–</div></div></div>
      <div class="s" id="updStatus"></div><div class="updact"><button class="btn pri press" id="updBtn" data-act="updgo">${$t('update_check')}</button><div class="prog" id="updProg" role="progressbar" aria-valuemin="0" aria-valuemax="100"><i></i></div></div></div>
    <div class="group"><div class="row">${ic('update')}<div class="txt"><div class="t">${$t('update_auto_title')}</div><div class="s">${$t('update_auto_sub')}</div></div>${sw('upd')}</div></div>
    <div class="lbl">${$t('update_notes')}</div>
    <div class="card pad prose" id="updNotes">${$t('update_no_notes')}</div>
  </section>

  <section class="page" data-page="about" hidden>
    <div class="card hero"><div class="logoBig">${ic('budL')}${ic('budR')}</div>
      <div class="herot"><div class="t" style="font-size:17px;font-weight:400">${$t('app_name')} <span class="s" id="aboutVer" style="display:inline;font-size:12px;font-weight:400"></span></div><div class="s" style="font-size:12px">${$t('about_tagline')}</div></div></div>
    <div class="btnrow"><button class="btn press" data-act="url" data-v="https://github.com/spizganed/QuickBuds">${ic('github')}GitHub</button><button class="btn press" data-act="url" data-v="https://ko-fi.com/spizganed">${ic('kofi')}Ko-fi</button><button class="btn press" data-act="url" data-v="https://discord.gg/pvsnEf9TrZ">${ic('discord')}Discord</button></div>
    <div id="replyBox" hidden><div class="lbl">${$t('reply_title')}</div><div class="card pad prose"></div></div>
    <div class="cols st">
      <div class="col"><div class="lbl">${$t('about_license_title')}</div><div class="card pad prose" style="flex:1">${$t('about_license_body')}</div></div>
      <div class="col"><div class="lbl">${$t('about_privacy_title')}</div><div class="card pad prose" style="flex:1">${$t('about_privacy_body')}</div></div>
    </div>
    <div class="group"><div class="row">${ic('info')}<div class="txt"><div class="t">${$t('about_privacy_reset')}</div><div class="s">${$t('about_privacy_reply')}</div></div><button class="btn press" data-act="resetid">${$t('about_privacy_reset')}</button></div></div>
  </section>
  </main>
  <div class="toast" role="status" aria-live="polite"></div>`;
}
function buildTray(){
  document.getElementById('tray').innerHTML=`
    <div class="thead"><div class="nm"><button class="mname press" data-act="model" aria-label="${$t('model_title')}"><span data-model>OnePlus Buds 4</span>${ic('chev','dn')}</button></div>
      <button class="btn icon press" data-act="openapp" title="${$t('tray_open')}" aria-label="${$t('tray_open')}">${ic('max')}</button><button class="btn icon press" data-act="quitapp" title="${$t('tray_quit')}" aria-label="${$t('tray_quit')}">${ic('power')}</button></div>
    <div class="card budsw tb"><div class="bwr" data-buds></div></div>
    ${ancCard()}
    <div class="group">${swRow('bolt','Low latency mode','','ll')}</div>
    <div class="toast" role="status" aria-live="polite"></div>`;
document.getElementById('tbIcon').innerHTML=ic('earbud');
}

/* ================= page content that follows the state ================= */
const BUDS={case:{L:[100,'In case'],R:[100,'In case'],C:[60,'Charging case']},ears:{L:[82,'In ear'],R:[79,'In ear'],C:[60,'Charging case']},mixed:{L:[82,'In ear'],R:[100,'In case'],C:[60,'Charging case']}};
function renderBuds(){const d=window.QBLIVE?QBLIVE.buds():(BUDS[S.budState]||BUDS.case);
  // A bud's label is its wear state (In ear, Out of ear, In case); the case has no state to read, so it keeps its name.
  const lab=(g,st,name)=>g!=='case'&&st?st:name;
  const one=(g,[pct,st],name)=>`<div class="bud">${ring(g,pct,name,'')}<div class="bst"><span class="t">${esc(lab(g,st,name))}</span></div></div>`;
  document.querySelectorAll('[data-buds]').forEach(w=>{
    // A ring that exists is updated, so its arc moves to the new value; the dot style draws again.
    if(S.style!=='pixel'&&w.children.length===3&&w._built){
      [d.L,d.C,d.R].forEach(([pct,st],i)=>{const bud=w.children[i],ring=bud.querySelector('.ring'),none=pct==null,v=none?0:pct;
        ring.dataset.pct=v;ring.querySelector('.rf').style.strokeDashoffset=(213.6*(1-v/100)).toFixed(1);
        bud.querySelector('.pct').textContent=none?'–':v+'%';
        bud.querySelector('.bst .t').textContent=lab(['budL','case','budR'][i],st,[$t('status_left'),$t('status_case'),$t('status_right')][i]);});
      return;}
    w.innerHTML=one('budL',d.L,$t('status_left'))+one('case',d.C,$t('status_case'))+one('budR',d.R,$t('status_right'));w._built=true;});
  document.querySelectorAll('#bsSeg .pc').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.v===S.budState)));
  requestAnimationFrame(()=>pixelize(document));}

function bassUi(br){if(!br)return;const mn=+br.min,mx=+br.max;if(!br._drag)br.value=S.bassLv;br.style.setProperty('--p',(S.bassLv-mn)/(mx-mn)*100+'%');document.getElementById('bassV').textContent=(S.bassLv>0?'+':'')+S.bassLv;}
function drawEq(g){
  const ed=document.getElementById('eqed');if(!ed)return;const H=ed.clientHeight||(S.size==='s'?96:180);ed.querySelector('svg.curve').setAttribute('viewBox',`0 0 600 ${H}`);
  ed.querySelector('.fillp').setAttribute('d',curvePath(g,600,H,true));ed.querySelector('.ln').setAttribute('d',curvePath(g,600,H,false));
  ed.querySelectorAll('.band').forEach((b,i)=>{const v=g[i],r=Math.round(v),y=H/2-v*(H/2)/6.6;b.querySelector('.knob').style.top=(y-11)+'px';b.querySelector('.knob').setAttribute('aria-valuenow',r);
    const bar=b.querySelector('.bar');bar.style.top=Math.min(y,H/2)+'px';bar.style.height=Math.abs(H/2-y)+'px';b.querySelector('.val').textContent=(r>0?'+':'')+r;});
}
// anim: the bars, knobs and curve glide to a new preset with a small overshoot (a preset switch, not a drag).
function renderEq(anim){
  const g=S.gains;if(!document.getElementById('eqed'))return;
  cancelAnimationFrame(renderEq._r);
  const prev=renderEq._shown;
  if(anim&&prev&&prev.length===g.length&&prev.some((v,i)=>v!==g[i])){
    const sp=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--speed'))||1,t0=performance.now(),dur=450*sp,from=prev.slice();
    const ease=t=>{const c=1.70158*1.4;return 1+(c+1)*Math.pow(t-1,3)+c*Math.pow(t-1,2);};
    const step=now=>{const t=Math.min(1,(now-t0)/dur),e=ease(t),cur=g.map((v,i)=>from[i]+(v-from[i])*e);
      if(t<1){drawEq(cur);renderEq._shown=cur;renderEq._r=requestAnimationFrame(step);}else{drawEq(g);renderEq._shown=g.slice();}};
    renderEq._r=requestAnimationFrame(step);
  }else{drawEq(g);renderEq._shown=g.slice();}
  // A built-in preset is fixed and its gains are not readable from the buds: the editor shows no values and takes no input.
  document.getElementById('eqed').classList.toggle('locked',!!(window.QBLIVE&&QBLIVE.eq&&S.eqPreset&&PRESETS[S.eqPreset]));
  const sel=n=>S.eqPreset===n?ic('check','ch'):'';
  document.getElementById('eqRec').innerHTML=Object.keys(PRESETS).map(n=>`<button class="row press" data-act="eqp" data-v="${n}" aria-pressed="${S.eqPreset===n}"><div class="txt"><div class="t">${n}</div></div>${sel(n)}</button>`).join('');
  document.getElementById('eqCus').innerHTML=S.custom.map((n,i)=>n?`<button class="row press" data-act="eqp" data-v="${esc(n)}" aria-pressed="${S.eqPreset===n}"><div class="txt"><div class="t">${esc(n)}</div></div>${sel(n)}</button>`
    :`<button class="row press slot" data-act="eqnew" data-v="${i}" title="${$t('eq_save')}">${ic('plus')}<div class="txt"><div class="t">${$t('eq_add')}</div></div></button>`).join('');document.querySelectorAll('[data-eqname]').forEach(e=>e.textContent=S.eqPreset);
  bassUi(document.getElementById('bassR'));
  const names=new Set(S.custom.filter(Boolean));
  if(renderEq._names)document.querySelectorAll('#eqCus [data-act="eqp"]').forEach(b=>{if(!renderEq._names.has(b.dataset.v))b.animate([{opacity:0,transform:'translateY(-8px)'},{opacity:1,transform:'none'}],{duration:280*(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--speed'))||1),easing:'cubic-bezier(.34,1.4,.64,1)'});});
  renderEq._names=names;
  const brr=document.getElementById('bassR');if(brr){brr.disabled=!S.bass;brr.closest('.row').classList.toggle('bassoff',!S.bass);}
  pixelize(document.getElementById('eqRec'));pixelize(document.getElementById('eqCus'));
}
const HZ=['80 Hz','10 kHz','4.8 kHz','2.4 kHz','1.2 kHz','250 Hz'],EAR={L:[.85,.9,.8,.85,.45,.8],R:[.78,.72,.7,.88,.62,.7]};
function renderHearing(){
  if(window.QBLIVE&&QBLIVE.hear)return renderHearingLive();
  const cx=150,cy=135,R=105,ang=[240,300,0,60,120,180].map(a=>a*Math.PI/180),pt=(r,a)=>`${(cx+r*Math.cos(a)).toFixed(1)},${(cy+r*Math.sin(a)).toFixed(1)}`;
  let s='';[1,.75,.5,.25].forEach(k=>s+=`<polygon class="grid" points="${ang.map(a=>pt(R*k,a)).join(' ')}"/>`);
  ang.forEach(a=>s+=`<line x1="${cx}" y1="${cy}" x2="${(cx+R*Math.cos(a)).toFixed(1)}" y2="${(cy+R*Math.sin(a)).toFixed(1)}" class="grid"/>`);
  s+=`<polygon class="lft" style="opacity:${S.showL?1:0}" points="${EAR.L.map((v,i)=>pt(R*v,ang[i])).join(' ')}"/><polygon class="rgt" style="opacity:${S.showR?1:0}" points="${EAR.R.map((v,i)=>pt(R*v,ang[i])).join(' ')}"/>`;
  ang.forEach((a,i)=>{const x=cx+(R+18)*Math.cos(a),y=cy+(R+16)*Math.sin(a)+4;s+=`<text x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="${Math.abs(Math.cos(a))<.2?'middle':Math.cos(a)>0?'start':'end'}">${HZ[i]}</text>`;});
  document.getElementById('radar').innerHTML=s;
  const order=[0,5,4,3,2,1];document.getElementById('freq').innerHTML=order.map(i=>`<span class="hz">${HZ[i]}</span><div class="bars"><span style="width:${EAR.L[i]*100}%;opacity:${S.showL?1:.15}"></span><span class="r" style="width:${EAR.R[i]*100}%;opacity:${S.showR?1:.15}"></span></div>`).join('');
  document.querySelectorAll('.lg').forEach(b=>b.setAttribute('aria-pressed',String(!!S[b.dataset.v])));
}
const ACTS={none:'No action',play:'Play/Pause',prev:'Previous track',next:'Next track',assist:'Voice assistant',game:'Game mode',switchdev:'Switch devices',anc:'ANC',vol:'Volume control',off:'Off',answer:'Answer / end call',decline:'Decline call'};
const GROWS=[['tap1','Single tap',['none','play','prev','next','assist','game','switchdev','anc']],['tap2','Double tap',['none','play','prev','next','assist','game','switchdev','anc']],['tap3','Triple tap',['none','play','prev','next','assist','game','switchdev','anc']],['slide','Slide',['vol','none']],['hold','Hold',['off','anc','assist']],['tap2','Double tap',['none','answer']],['hold','Long hold',['none','decline']]];
function renderGestures(){
  if(window.QBLIVE&&QBLIVE.gest)return renderGesturesLive();
  const sel=(side,i)=>`<button class="sel press pick" data-act="gpick" data-side="${side}" data-v="${i}" aria-label="${GROWS[i][1]}, ${side==='L'?'left':'right'} earbud">${ACTS[S.gest[side][i]]}</button>`;
  const row=i=>`<tr class="r"><td><div class="gl">${ic(GROWS[i][0])}${GROWS[i][1]}</div></td><td>${sel('L',i)}</td><td>${sel('R',i)}</td></tr>`;
  document.getElementById('gt').innerHTML=`<thead><tr><th>Gesture</th><th><span class="budh">${ic('budL')}Left earbud</span></th><th><span class="budh">${ic('budR')}Right earbud</span></th></tr></thead>
    <tbody><tr class="sect"><td colspan="3">When not on call</td></tr>${[0,1,2,3,4].map(row).join('')}<tr class="sect"><td colspan="3">When on call</td></tr>${[5,6].map(row).join('')}</tbody>`;
}

/* colour panel: wallpaper tones, accent picker, or palette presets */
const TOK=[['bg','token_background'],['card','token_card'],['accent','token_accent'],['text','token_text'],['text2','token_text_secondary'],['outline','token_outline']];
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'})[c]);
function hsvOf(c){let[r,g,b]=rgbOf(c).map(v=>v/255);const mx=Math.max(r,g,b),mn=Math.min(r,g,b),d=mx-mn;let h=0;if(d){h=mx===r?((g-b)/d)%6:mx===g?(b-r)/d+2:(r-g)/d+4;h*=60;if(h<0)h+=360;}return[h,mx?d/mx:0,mx];}
function fromHsv(h,s,v){const f=n=>{const k=(n+h/60)%6;return v-v*s*Math.max(0,Math.min(k,4-k,1));};return intOf(f(5)*255,f(3)*255,f(1)*255);}
function picker(val){const[h,s,v]=hsvOf(val);return `<div class="picker"><span class="pv" style="background:${hex(val)}"></span>
  <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap"><label for="hexIn" class="s" style="margin:0">Hex</label><input class="hex" id="hexIn" value="${hex(val).toUpperCase()}" maxlength="7" spellcheck="false">
  <div class="chips">${QUICK.map(c=>`<button class="cs" data-act="swatch" data-v="${c}" style="background:${hex(c)}" aria-label="${hex(c)}" aria-pressed="${c===val}"></button>`).join('')}${S.recent.filter(c=>!QUICK.includes(c)).slice(0,4).map(c=>`<button class="cs" data-act="swatch" data-v="${c}" style="background:${hex(c)}" aria-label="Recent ${hex(c)}"></button>`).join('')}</div></div>
  <div class="sls"><div class="sl"><span>H</span><input id="slH" type="range" min="0" max="360" value="${Math.round(h)}" aria-label="Hue"></div>
  <div class="sl"><span>S</span><input id="slS" type="range" min="0" max="100" value="${Math.round(s*100)}" aria-label="Saturation"></div>
  <div class="sl"><span>B</span><input id="slV" type="range" min="0" max="100" value="${Math.round(v*100)}" aria-label="Brightness"></div></div></div>`;}
function renderColorPanel(){
  const el=document.getElementById('colorPanel');if(!el)return;
  if(S.colorMode==='wallpaper'){const{a1,n1,n2}=tonal(seed());
    el.innerHTML=`<div class="txt"><div class="t">${$t('theme_wallpaper')}</div><div class="s">${$t('theme_wallpaper_sub')}</div></div><div class="ctrl"><div class="tones">${[a1(200),a1(600),n2(200),n2(700),n1(800),n1(900)].map(c=>`<span style="background:${hex(c)}" title="${hex(c)}"></span>`).join('')}</div></div>`;}
  else if(S.colorMode==='accent'){el.innerHTML=`<div class="txt" style="flex-basis:100%"><div class="t">${$t('theme_accent')}</div></div><div style="flex:1 1 100%">${picker(S.accent??palette().accent)}</div>`;wirePicker(c=>{S.accent=c;});}
  else{const pr=S.presets.find(x=>x.id===S.active);
    el.innerHTML=`<div class="txt" style="flex-basis:100%"><div class="t">${$t('theme_custom').replace('%1$d',S.presets.length).replace('%2$d',3)}</div></div>
      <div class="chips" style="flex:1 1 100%">${S.presets.map(x=>`<button class="chip press" data-act="preset" data-v="${x.id}" aria-pressed="${x.id===S.active}">${esc(x.name)}<span class="mini">${TOK.map(([k])=>`<i style="background:${hex(x[k])}"></i>`).join('')}</span></button>`).join('')}${S.presets.length<3?`<button class="chip press" data-act="newpreset">${ic('plus')}${$t('theme_new')}</button>`:''}</div>
      ${pr?`<div style="flex:1 1 100%;display:flex;flex-direction:column;gap:10px"><div style="display:flex;gap:8px;align-items:center"><label class="s" for="pName" style="margin:0">${$t('preset_name')}</label><input class="field" id="pName" value="${esc(pr.name)}" maxlength="24" style="max-width:220px"><span style="flex:1"></span><button class="btn press" data-act="delpreset">${ic('del')}${$t('preset_delete')}</button></div>
      <div class="tokens">${TOK.map(([k,l],i)=>`<button class="tok" data-act="tok" data-v="${i}" aria-pressed="${S.editTok===i}"><i style="background:${hex(pr[k])}"></i>${$t(l)}</button>`).join('')}</div>${picker(pr[TOK[S.editTok][0]])}</div>`:`<div class="s" style="flex:1 1 100%;margin:0">${$t('theme_footer')}</div>`}`;
    if(pr){wirePicker(c=>{pr[TOK[S.editTok][0]]=c;});document.getElementById('pName').addEventListener('input',e=>{pr.name=e.target.value||'Palette';save();});}}
  pixelize(el);
}
function wirePicker(onSet){
  const H=document.getElementById('slH'),Sx=document.getElementById('slS'),V=document.getElementById('slV'),hx=document.getElementById('hexIn'),pv=document.querySelector('#colorPanel .pv');
  const paint=()=>{const h=+H.value,s=Sx.value/100,v=V.value/100;H.style.setProperty('--tr',`linear-gradient(90deg,${[0,60,120,180,240,300,360].map(x=>hex(fromHsv(x,Math.max(s,.6),Math.max(v,.7)))).join(',')})`);
    Sx.style.setProperty('--tr',`linear-gradient(90deg,${hex(fromHsv(h,0,v))},${hex(fromHsv(h,1,v))})`);V.style.setProperty('--tr',`linear-gradient(90deg,#000,${hex(fromHsv(h,s,1))})`);};
  const live=()=>{const c=fromHsv(+H.value,Sx.value/100,V.value/100);onSet(c);hx.value=hex(c).toUpperCase();pv.style.background=hex(c);paint();apply();
    document.querySelectorAll('#colorPanel .tok i').forEach((i,k)=>{const pr=S.presets.find(x=>x.id===S.active);if(pr)i.style.background=hex(pr[TOK[k][0]]);});};
  [H,Sx,V].forEach(x=>{x.addEventListener('input',live);x.addEventListener('change',()=>remember(fromHsv(+H.value,Sx.value/100,V.value/100)));});
  hx.addEventListener('change',()=>{const m=hx.value.trim().match(/^#?([0-9a-f]{6})$/i);if(!m){hx.value=hex(fromHsv(+H.value,Sx.value/100,V.value/100)).toUpperCase();return;}
    const c=parseInt(m[1],16),[h,s,v]=hsvOf(c);H.value=h;Sx.value=s*100;V.value=v*100;onSet(c);pv.style.background=hex(c);paint();remember(c);apply();});
  paint();
}
const remember=c=>{S.recent=[c,...S.recent.filter(x=>x!==c)].slice(0,6);save();};

/* ================= sync controls with the state ================= */
const STYLES=['material','quickbuds','pixel','glass'],MODES=['wallpaper','accent','palette'],THEMES=['oled','dark','white'];
function setSeg(key,idx){document.querySelectorAll(`.seg[data-seg="${key}"]`).forEach(s=>{s.style.setProperty('--sel',Math.max(0,idx));s.querySelector('.ind').style.opacity=idx<0?0:1;
  s.querySelectorAll('.cell').forEach((c,i)=>c.setAttribute('aria-checked',String(i===idx)));});}
function sync(p){
  setSeg('anc',S.anc);setSeg('level',S.level);document.querySelectorAll('.seg.lvls').forEach(x=>slide(x,S.anc===1));
  setSeg('style',STYLES.indexOf(S.style));setSeg('colorMode',MODES.indexOf(S.colorMode));setSeg('theme',S.colorMode==='palette'?-1:THEMES.indexOf(activeId()));setSeg('fbtab',S.page==='test'?1:0);
  document.querySelectorAll('.sw[data-k],.qt[data-k]').forEach(b=>b.setAttribute('aria-checked',String(!!S[b.dataset.k])));
  const bt=document.getElementById('bassT');if(bt){bt.value=S.bassLv;bt.style.setProperty('--p',S.bassLv/3*100+'%');document.getElementById('bassTV').textContent='+'+S.bassLv;}
  const show=(id,on)=>{const e=document.getElementById(id);if(e)e.hidden=!on;};
  show('gbRow',S.style==='glass');show('themeRow',S.colorMode!=='palette');show('autoRow',S.colorMode!=='palette');
  const gb=document.getElementById('gbSw');
  if(gb)gb.innerHTML=GLASS_BGS.map(g=>{let b;if(g.id==='none')b='repeating-conic-gradient(rgba(255,255,255,.16) 0 25%,rgba(255,255,255,.04) 0 50%) 0 0/10px 10px';else if(g.id==='black')b='#000';
    else{const c=g.blobs||[p.accent,blend(p.accent,0x4A6DFF,.5),blend(p.accent,0xFF9D6B,.5)];b=`radial-gradient(circle at 15% 20%,${rgba(c[0],.9)},transparent 60%),radial-gradient(circle at 85% 15%,${rgba(c[1],.8)},transparent 60%),radial-gradient(circle at 75% 90%,${rgba(c[2],.8)},transparent 60%),#0a0a12`;}
    return `<button class="gsw press" data-act="glassbg" data-v="${g.id}" aria-pressed="${S.glassBg===g.id}"><span style="background:${b}"></span>${$t('glass_bg_'+g.id,g.name)}</button>`;}).join('');
  const dn=document.querySelector('.nav[data-v="dev"]');if(dn)dn.hidden=!S.devtools;
  const nv=NAVOF[S.page]||S.page;document.querySelectorAll('.nav').forEach(n=>n.setAttribute('aria-current',n.dataset.v===nv?'page':'false'));
  document.getElementById('vWin').setAttribute('aria-pressed',String(S.view==='window'));document.getElementById('vTray').setAttribute('aria-pressed',String(S.view==='tray'));
  const sm=document.getElementById('sysMode');sm.setAttribute('aria-pressed',String(!S.sysLight));sm.textContent=S.sysLight?'Light mode':'Dark mode';
  document.querySelectorAll('#wps .wp').forEach(b=>b.setAttribute('aria-pressed',String(String(S.wall)===b.dataset.v)));
  const ar=document.getElementById('alertR');if(ar){ar.value=S.alert;ar.style.setProperty('--p',(S.alert-ar.min)/(ar.max-ar.min)*100+'%');}
  const lb=document.getElementById('langBtn');if(lb)lb.textContent=S.lang===0?$t('language_system'):LANGS[S.lang];
  if(!(window.QBLIVE&&QBLIVE.live)){const mn=S.model==='auto'?'OnePlus Buds 4':S.model;document.querySelectorAll('[data-model]').forEach(e=>e.textContent=mn);document.querySelectorAll('[data-modelfull]').forEach(e=>e.textContent=S.model==='auto'?'Automatic (OnePlus Buds 4)':S.model);}
  document.getElementById('win').classList.toggle('sm',S.size==='s');document.querySelectorAll('#szSeg .pc').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.v===S.size)));
  moveNav();
  const dw=document.getElementById('dualW');if(dw)dw.classList.toggle('off',!S.dual);
}

/* ================= motion ================= */
const reduce=()=>{try{return matchMedia('(prefers-reduced-motion: reduce)').matches;}catch(e){return false;}};
// The native glass window is see-through: a view transition paints its snapshot on an opaque layer, so the end would not match.
// There the circle sweeps the background itself: the old and the new background are two layers, each masked to its own part,
// so no pixel shows both and the see-through stays true all the way.
function sweep(ev,fn){
  const app=document.querySelector('.app');if(!app){fn();return;}
  const grab=()=>{const c=getComputedStyle(app);return `${c.backgroundImage}, ${c.backgroundColor}`.replace(/^none, /,'');};
  const before=grab();fn();const after=grab();
  const r0=app.getBoundingClientRect(),x=(ev?.clientX??innerWidth/2)-r0.left,y=(ev?.clientY??innerHeight/2)-r0.top,
    R=Math.hypot(Math.max(x,r0.width-x),Math.max(y,r0.height-y))+4;
  const mk=bg=>{const d=document.createElement('div');d.style.cssText=`position:absolute;inset:0;z-index:-1;border-radius:inherit;pointer-events:none;background:${bg}`;return d;};
  const lo=mk(before),ln=mk(after);app.classList.add('sweeping');app.prepend(ln,lo);
  const sp=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--speed'))||1,dur=420*sp,t0=performance.now();
  const step=now=>{const t=Math.min(1,(now-t0)/dur),e=1-Math.pow(1-t,3),rad=R*e;
    ln.style.webkitMaskImage=`radial-gradient(circle at ${x}px ${y}px,#000 ${rad}px,transparent ${rad+1}px)`;
    lo.style.webkitMaskImage=`radial-gradient(circle at ${x}px ${y}px,transparent ${rad}px,#000 ${rad+1}px)`;
    if(t<1)requestAnimationFrame(step);else{lo.remove();ln.remove();app.classList.remove('sweeping');}};
  requestAnimationFrame(step);}
function reveal(ev,fn){if(reduce()){fn();return;}if(document.documentElement.classList.contains('native')&&S.style==='glass'){sweep(ev,fn);return;}if(!document.startViewTransition){fn();return;}
  const x=ev?.clientX??innerWidth/2,y=ev?.clientY??innerHeight/2,r=Math.hypot(Math.max(x,innerWidth-x),Math.max(y,innerHeight-y));
  const vt=document.startViewTransition(fn);vt.ready.then(()=>document.documentElement.animate({clipPath:[`circle(0px at ${x}px ${y}px)`,`circle(${r}px at ${x}px ${y}px)`]},{duration:420*(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--speed'))||1),easing:'cubic-bezier(.2,0,0,1)',pseudoElement:'::view-transition-new(root)'})).catch(()=>{});}
function replay(el){if(!el)return;el.classList.remove('enter');void el.offsetWidth;el.classList.add('enter');}
function toast(msg){document.querySelectorAll('.toast').forEach(t=>{t.textContent=msg;t.classList.add('on');clearTimeout(t._h);t._h=setTimeout(()=>t.classList.remove('on'),2600);});}
/* A row opens and shuts with a height and fade (Motion.slide on the phone: 280 ms open, 220 ms shut). */
function slide(el,open){
  const sp=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--speed'))||1;
  if(el._open===undefined){el._open=open;el.hidden=!open;return;}
  if(el._open===open)return;el._open=open;
  if(el._a)el._a.cancel();
  el.hidden=false;el.style.overflow='hidden';
  const h=el.scrollHeight,k=open?[{height:'0px',opacity:0},{height:h+'px',opacity:1}]:[{height:h+'px',opacity:1},{height:'0px',opacity:0}];
  const a=el._a=el.animate(k,{duration:(open?280:220)*sp,easing:'cubic-bezier(0,0,.2,1)'});
  a.onfinish=()=>{el._a=null;el.style.overflow='';if(!open)el.hidden=true;};}
function moveNav(){const side=document.querySelector('.side'),ind=side&&side.querySelector('.navind'),cur=side&&side.querySelector('.nav[aria-current="page"]');if(!ind||!cur)return;
  ind.style.transform=`translateY(${cur.offsetTop}px)`;ind.style.height=cur.offsetHeight+'px';}
function go(page){const fwd=ORDER.indexOf(page)>=ORDER.indexOf(S.page);S.page=page;save();document.querySelectorAll('#win .page').forEach(p=>{const on=p.dataset.page===page;p.hidden=!on;
  if(on){p.classList.remove('slide','slideb');void p.offsetWidth;p.classList.add(fwd?'slide':'slideb');}});
  document.getElementById('main').scrollTop=0;apply();requestAnimationFrame(()=>{if(page==='eq')renderEq();pixelize(document.querySelector(`.page[data-page="${page}"]`));});}

/* ================= dialogs (find, fit test, language, known issues) ================= */
function dialog(html,onOpen,onClose){const host=S.view==='tray'?document.getElementById('tray'):document.getElementById('win');const sc=document.createElement('div');sc.className='scrim';
  sc.innerHTML=`<div class="dlg pk card" role="dialog" aria-modal="true">${html}</div>`;host.appendChild(sc);
  let closed=false;const close=()=>{if(closed)return;closed=true;sc.classList.add('out');setTimeout(()=>sc.remove(),160);document.removeEventListener('keydown',esc_);onClose&&onClose();};const esc_=e=>{if(e.key==='Escape')close();};
  document.addEventListener('keydown',esc_);sc.addEventListener('click',e=>{if(e.target===sc||e.target.closest('[data-close]'))close();});
  onOpen&&onOpen(sc.firstChild,close);pixelize(sc);sc.querySelector('button')?.focus();}
const infoDialog=(title,msg)=>dialog(`<h3>${esc(title)}</h3><div class="s" style="text-align:center;margin:0;white-space:pre-line">${esc(msg)}</div><div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button></div>`);
let findOn=false;
function findDialog(){dialog(`<h3>Find my earbuds</h3><div class="fitbuds"><div class="fb">${ic('budL')}Left</div><div class="fb">${ic('budR')}Right</div></div>
  <div class="s" style="text-align:center;margin:0">Plays a loud tone on both earbuds at once. Take the earbuds out before playing it.</div><div class="acts"><button class="btn press" data-close>Close</button><button class="btn pri press" id="fPlay">Play sound</button></div>`,
  d=>{const b=d.querySelector('#fPlay'),bb=d.querySelector('.fitbuds');b.addEventListener('click',()=>{const on=b.textContent==='Play sound';findOn=on;window.QBLIVE&&QBLIVE.send({t:'find',on});b.textContent=on?'Stop':'Play sound';bb.classList.toggle('wave',on);});},()=>{if(findOn){findOn=false;window.QBLIVE&&QBLIVE.send({t:'find',on:false});}});}
function fitDialog(){dialog(`<h3>Earbud fit test</h3><div class="fitbuds"><div class="fb">${ic('budL')}<span>Left</span><b id="fL">–</b></div><div class="fb">${ic('budR')}<span>Right</span><b id="fR">–</b></div></div>
  <div class="s" id="fMsg" style="text-align:center;margin:0">Ensure that your ear tips make a good seal with your ear canals for better noise canceling. Press Play to start the test.</div>
  <div class="acts"><button class="btn press" data-close>Close</button><button class="btn pri press" id="fGo">Play</button></div>`,
  d=>{const b=d.querySelector('#fGo'),m=d.querySelector('#fMsg'),bb=d.querySelector('.fitbuds');b.addEventListener('click',()=>{b.disabled=true;b.textContent='Playing...';m.textContent='Keep your earbuds inserted';bb.classList.add('wave');
    setTimeout(()=>{bb.classList.remove('wave');d.querySelector('#fL').textContent='Good';d.querySelector('#fR').textContent='Average';m.innerHTML='<b style="color:var(--text)">Adjust your earbuds</b><br>Adjust the position of the right earbud or try a different-sized ear tip, and then perform the test again.';b.textContent='Check again';b.disabled=false;},2200);});});}
function langDialog(){dialog(`<h3>Language</h3><div class="langs">${LANGS.map((l,i)=>`<button class="lang press" data-lang="${i}" aria-pressed="${S.lang===i}">${l}</button>`).join('')}</div><div class="acts"><button class="btn press" data-close>Done</button></div>`,
  (d,close)=>d.addEventListener('click',e=>{const b=e.target.closest('[data-lang]');if(!b)return;S.lang=+b.dataset.lang;d.querySelectorAll('.lang').forEach(x=>x.setAttribute('aria-pressed',String(x===b)));apply();pixelize(d);}));}
/* one list dialog, like the app's: tap a choice, the highlight glides to it, Done applies */
function pickDialog(title,opts,cur,onDone,cls=''){let v=String(cur);
  // The options are the same rows as the Equalizer presets, so they follow the style, the theme and the colours.
  const mark=b=>{b.parentNode.querySelectorAll('.pkopt').forEach(x=>{const on=x===b;x.setAttribute('aria-pressed',String(on));const c=x.querySelector('.ch');if(on&&!c)x.insertAdjacentHTML('beforeend',ic('check','ch'));if(!on&&c)c.remove();});};
  dialog(`<h3>${title}</h3><div class="group opts ${cls}${opts.length>5&&!cls.includes('scroll')?' many':''}">${opts.map(([k,l])=>`<button class="row press pkopt" data-o="${esc(String(k))}" aria-pressed="${String(k)===v}"><div class="txt"><div class="t">${l}</div></div>${String(k)===v?ic('check','ch'):''}</button>`).join('')}</div>
    <div class="acts two"><button class="btn press" data-close>${$t('dialog_cancel')}</button><button class="btn pri press" id="pkDone">Done</button></div>`,
  (d,close)=>{d.classList.add('pk');
    d.addEventListener('click',e=>{const b=e.target.closest('.pkopt');if(!b)return;v=b.dataset.o;mark(b);});
    d.addEventListener('dblclick',e=>{if(e.target.closest('.pkopt')){onDone(v);close();}});
    d.querySelector('#pkDone').addEventListener('click',()=>{onDone(v);close();});
    const sb=d.querySelector('.pkopt[aria-pressed="true"]');const ol=d.querySelector('.opts.scroll');if(ol&&sb)ol.scrollTop=sb.offsetTop-ol.clientHeight/2+sb.offsetHeight/2;setTimeout(()=>sb&&sb.focus({preventScroll:true}),0);});}
const MODELS=['OnePlus Buds 4','OnePlus Buds 3','OnePlus Buds Pro 2','OnePlus Buds','OnePlus Buds Z','OPPO Enco Air2','OPPO Enco Buds2','OPPO Enco R','realme Buds T500 Pro','realme Buds T500','realme Buds T310','realme Buds T300','realme Buds T200','realme Buds T200 Lite','realme Buds T200x','realme Buds T110','realme Buds T100','realme Buds Wireless Pro','realme Buds Wireless 6 ANC','realme Buds Wireless 6','realme Buds Wireless 6 Neo','realme Buds Wireless 5 ANC','realme Buds Wireless 3','realme Buds Wireless 3 Neo','realme Buds Wireless 2S','realme Buds Wireless 2','realme Buds Wireless 2 Neo','realme TechLife Buds T500 Pro'];
function modelDialog(){pickDialog('Earbuds model',[['auto','Automatic (OnePlus Buds 4)'],...MODELS.map(m=>[m,m])],S.model,v=>{S.model=v;save();apply();toast(v==='auto'?'Model: found automatically.':`Model: ${v}.`);},'scroll');}
function langPick(){pickDialog('Language',LANGS.map((l,i)=>[i,l]),S.lang,v=>{S.lang=+v;save();apply();toast(`Language: ${LANGS[S.lang]}.`);},'grid g4');}
function knownDialog(){dialog(`<h3>Known issues</h3><div class="group"><div class="row" style="align-items:flex-start;padding-block:12px">${ic('warn')}<div class="txt"><div class="t">High-quality audio and 3D audio</div><div class="s">The earbuds run only one of them. Turning one on turns the other off.</div></div></div></div><div class="acts"><button class="btn press" data-close>Close</button></div>`);}

/* ================= events ================= */
document.addEventListener('click',ev=>{
  const b=ev.target.closest('[data-act]');if(!b||b.tagName==='SELECT')return;const a=b.dataset.act,v=b.dataset.v,k=b.dataset.k;
  if(a==='pick'){const i=+v;
    if(k==='anc'){const enter=i===1&&S.anc!==1;S.anc=i;if(enter)S.level=-1;else window.QBLIVE&&QBLIVE.sendAnc();apply();requestAnimationFrame(()=>pixelize(document));return;}
    if(k==='level'){S.level=i;if(S.anc===1)window.QBLIVE&&QBLIVE.sendAnc();apply();return;}
    if(k==='fbtab'){go(i?'test':'feedback');return;}
    if(k==='style'){reveal(ev,()=>{S.style=STYLES[i];apply();});return;}
    if(k==='colorMode'){reveal(ev,()=>{S.colorMode=MODES[i];if(S.colorMode==='palette'&&!S.active&&S.presets[0])S.active=S.presets[0].id;apply();renderColorPanel();});return;}
    if(k==='theme'){reveal(ev,()=>{const id=THEMES[i];if(S.auto&&id!=='white')S.autoDark=id;else{S.auto=false;S.theme=id;}apply();if(S.colorMode==='accent')renderColorPanel();});return;}}
  if(a==='toggle'){
    if(k==='auto'){reveal(ev,()=>{if(!S.auto&&S.theme!=='white')S.autoDark=S.theme;if(S.auto)S.theme=activeId();S.auto=!S.auto;apply();});return;}
    if(window.QBLIVE&&QBLIVE.live&&QBLIVE.ex&&(k==='hq'||k==='sp'||k==='wind')){
      const ex=QBLIVE.ex,want=!S[k];
      if(k==='wind'){QBLIVE.send({t:'wind',on:want});return;}
      const msg=k==='hq'?ex.hq.confirm:(want?ex.spatial.confirm:null),go=()=>QBLIVE.send({t:'sw',k,on:want});
      if(msg)confirmDialog($t(k==='hq'?'row_hires_title':'row_spatial_title'),msg,$t('codec_dialog_accept'),go);else go();return;}
    const on=!S[k];S[k]=on;
    if(window.QBLIVE&&QBLIVE.setting)QBLIVE.setting(k,on);
    if(k==='devtools'&&!on&&S.page==='dev')go('overview');
    if(k==='ll'&&window.QBLIVE){S._llHold={on,until:Date.now()+2500};QBLIVE.send({t:'ll',on});}
    if(['hq','sp','hp','dual','wear','bass'].includes(k)&&window.QBLIVE)QBLIVE.send({t:'sw',k,on});
    if(on&&k==='hq'&&S.sp){S.sp=false;toast('3D audio went off: the earbuds run only one of them.');}
    if(on&&k==='sp'&&S.hq){S.hq=false;toast('High-quality audio went off: the earbuds run only one of them.');}
    apply();return;}
  if(a==='page'){if(S.page!==v)go(v);return;}
  if(a==='glassbg'){reveal(ev,()=>{S.glassBg=v;apply();});return;}
  if(a==='swatch'){const c=+v;if(S.colorMode==='accent')S.accent=c;else{const pr=S.presets.find(x=>x.id===S.active);if(pr)pr[TOK[S.editTok][0]]=c;}remember(c);reveal(ev,()=>{apply();renderColorPanel();});return;}
  if(a==='newpreset'){const p=palette(),id='p'+Date.now();S.presets.push({id,name:`Palette ${S.presets.length+1}`,bg:p.bg,card:p.card,accent:p.accent,text:p.text,text2:p.text2,outline:p.outline});S.active=id;S.editTok=2;apply();renderColorPanel();return;}
  if(a==='preset'){reveal(ev,()=>{S.active=v;apply();renderColorPanel();});return;}
  if(a==='tok'){S.editTok=+v;renderColorPanel();return;}
  if(a==='delpreset'){S.presets=S.presets.filter(x=>x.id!==S.active);S.active=S.presets[0]?.id||null;reveal(ev,()=>{apply();renderColorPanel();});return;}
  if(window.QBLIVE&&QBLIVE.eq&&a==='eqp'){QBLIVE.eqPick(v);return;}
  if(window.QBLIVE&&QBLIVE.eq&&a==='eqnew'){QBLIVE.eqNew();return;}
  if(window.QBLIVE&&QBLIVE.eq&&a==='eqdel'){QBLIVE.eqDel();return;}
  if(window.QBLIVE&&QBLIVE.eq&&a==='eqsave'){QBLIVE.eqSave();return;}
  if(a==='eqp'){S.eqPreset=v;S.gains=(PRESETS[v]||S.cg[v]||[0,0,0,0,0,0]).slice();renderEq(true);save();return;}
  if(a==='eqnew'){const n=`Custom ${+v+1}`;S.custom[+v]=n;S.cg[n]=S.gains.slice();S.eqPreset=n;renderEq();save();toast(`${n} saved in slot ${+v+1}.`);return;}
  if(a==='eqdel'){const i=S.custom.indexOf(S.eqPreset);if(i<0){toast('Preset modes cannot be deleted.');return;}delete S.cg[S.eqPreset];S.custom[i]=null;S.eqPreset='Balanced';S.gains=PRESETS.Balanced.slice();renderEq();save();return;}
  if(a==='eqsave'){toast(`${S.eqPreset} saved to the earbuds.`);return;}
  
  if(a==='ear'){S[v]=!S[v];renderHearing();save();return;}
  if(a==='fbchip'){b.setAttribute('aria-checked',String(b.getAttribute('aria-checked')!=='true'));return;}
  if(a==='find'){window.QBLIVE&&QBLIVE.live?QBLIVE.findDialog():findDialog();return;}
  if(a==='fit'){window.QBLIVE&&QBLIVE.live?QBLIVE.fitDialog():fitDialog();return;}
  if(window.QBLIVE&&QBLIVE.live){
    if(a==='glive'){QBLIVE.gestureDialog(+b.dataset.g,+b.dataset.side);return;}
    if(a==='gboth'){const c=QBLIVE.gest.bothHold;pickDialog($t('gesture_both_hold'),[[0,$t('gesture_action_none')],[1,$t('gesture_action_game')]],c.on==null?-2:+c.on,x=>{if(x!=='-2')QBLIVE.send({t:'both_hold',on:x==='1'});});return;}
    if(a==='gcall'){const c=QBLIVE.gest.calls.find(x=>x.k===+b.dataset.k);pickDialog(c.label,[[0,$t('gesture_action_none')],[1,c.on_label]],c.on==null?-2:+c.on,x=>{if(x!=='-2')QBLIVE.send({t:'call',k:c.k,on:x==='1'});});return;}
    if(a==='feat'){const id=b.dataset.id,ex=QBLIVE.ex;
      if(id==='pnc'){if(ex.pnc.on)QBLIVE.send({t:'pnc_feature',on:false});else QBLIVE.pncDialog();return;}
      const r=ex.rows.find(x=>String(x.id)===id);if(!r||r.busy)return;
      const go=()=>QBLIVE.send({t:'feature',id:r.id,on:!r.on});
      if(r.confirm&&(!r.on||r.confirmOff))confirmDialog(r.title,r.confirm,$t('codec_dialog_accept'),go);else go();return;}
    if(a==='featpick'){const r=QBLIVE.ex.rows.find(x=>String(x.id)===b.dataset.id),p=r.pick;
      pickDialog(p.title,p.choices.map(c=>[c.v,c.label]),p.cur==null?-2:p.cur,x=>{if(x!=='-2')QBLIVE.send({t:p.kind===1?'game_sound':'head_motion',v:+x});});return;}
    if(a==='codec'){const h=QBLIVE.ex.hq;pickDialog($t('row_hires_title'),h.choices.map(c=>[c.v,c.label]),h.cur==null?-2:h.cur,x=>{if(x!=='-2')QBLIVE.send({t:'codec',v:+x});});return;}
    if(a==='spatialtype'){const p=QBLIVE.ex.spatial;pickDialog($t('row_spatial_title'),p.choices.map(c=>[c.v,c.label]),p.cur,x=>QBLIVE.send({t:'spatial',v:+x}));return;}
    if(a==='devtoggle'){QBLIVE.devAsk(+b.dataset.i);return;}
    if(a==='devadd'){infoDialog($t('dual_add_title'),$t('dual_add_message'));return;}
    if(a==='devpref'){QBLIVE.prefDialog();return;}
    if(a==='hpick'){if(ev.target.closest('[data-act="hdel"]'))return;QBLIVE.send({t:'hearing_pick',i:+b.dataset.i});return;}
    if(a==='hdel'){const i=+b.dataset.i,p=QBLIVE.hear.state.profiles[i];confirmDialog($t('eq_delete'),p.confirm,$t('eq_delete'),()=>QBLIVE.send({t:'hearing_delete',i}));return;}
    if(a==='htest'){QBLIVE.hearTestDialog();return;}
  }
  if(a==='lang'){langDialog();return;}
  if(a==='known'){window.QBLIVE&&QBLIVE.live?QBLIVE.issuesDialog():knownDialog();return;}
  if(a==='fwinfo'){infoDialog($t('firmware_dialog_title'),$t('firmware_dialog_body'));return;}
  if(a==='model'){window.QBLIVE&&QBLIVE.models?QBLIVE.modelDialog():modelDialog();return;}
  if(a==='langdlg'){window.QBLIVE&&QBLIVE.langDialog?QBLIVE.langDialog():langPick();return;}
  if(a==='gpick'){const i=+v,side=b.dataset.side,g=GROWS[i];pickDialog(`${g[1]} · ${side==='L'?'left':'right'} earbud`,g[2].map(x=>[x,ACTS[x]]),S.gest[side][i],x=>{S.gest[side][i]=x;save();renderGestures();pixelize(document.getElementById('gt'));toast(`${g[1]} on the ${side==='L'?'left':'right'} earbud: ${ACTS[x]}.`);});return;}
  if(a==='devreconnect'&&window.QBLIVE){QBLIVE.send({t:'disconnect'});QBLIVE.send({t:'connect'});return;}
  if(a==='devdisc'&&window.QBLIVE){QBLIVE.send({t:'disconnect'});return;}
  if(a==='devcopy'){const t=document.getElementById('devlog').innerText;navigator.clipboard&&navigator.clipboard.writeText(t).then(()=>toast('Log copied.'));return;}
  if(window.QBLIVE&&QBLIVE.live){
    if(a==='updgo'){QBLIVE.updGo();return;}
    if(a==='fbprev'){QBLIVE.fbPreview();return;}
    if(a==='fbsend'){QBLIVE.fbSend();return;}
    if(a==='tstart'){QBLIVE.send({t:'test_start'});return;}
    if(a==='tmark'){QBLIVE.send({t:'test_mark',i:+b.dataset.i,mark:+b.dataset.m});return;}
    if(a==='tprev'){QBLIVE.testPreview();return;}
    if(a==='tsend'){QBLIVE.send({t:'test_send'});return;}
    if(a==='url'){window.__TAURI__.core.invoke('open_url',{url:v});return;}
    if(a==='resetid'){window.__TAURI__.core.invoke('reset_install_id');toast($t('about_privacy_reset'));return;}
  }
  if(a==='conn'){if(window.QBLIVE)QBLIVE.toggleConn();return;}
  if(a==='openwin'){setView('window');return;}
  if(a==='openapp'){if(window.__TAURI__)window.__TAURI__.core.invoke('open_main');else setView('window');return;}
  if(a==='quitapp'){if(window.__TAURI__)window.__TAURI__.core.invoke('quit');return;}
  if(a==='wall'){S.wall=+v;reveal(ev,()=>{apply();renderColorPanel();});return;}
});
document.addEventListener('change',e=>{const t=e.target;
  if(window.QBLIVE){if(t.id==='bassR'&&QBLIVE.eq)QBLIVE.send({t:'bass',level:S.bassLv,band:0});if(t.id==='alertR'&&QBLIVE.earbud)QBLIVE.send({t:'alert',v:S.alert});if(t.id==='tapR'&&QBLIVE.earbud)QBLIVE.send({t:'tap',v:stepped(t)});}
  if(t.classList&&t.classList.contains('range'))glide(t);
  if(t.id==='langSel'){S.lang=+t.value;save();toast(`Language: ${LANGS[S.lang]}.`);}
  if(t.matches('select[data-act="gest"]')){S.gest[t.dataset.side][+t.dataset.i]=t.value;save();toast(`${GROWS[+t.dataset.i][1]} on the ${t.dataset.side==='L'?'left':'right'} earbud: ${ACTS[t.value]}.`);}});
/* Smooth sliders: while the pointer is down the thumb follows it (step any); the value is the nearest whole step; on release the thumb glides to it. */
const stepped=t=>Math.min(+t.max,Math.max(+t.min,Math.round(+t.value)));
const paintRange=t=>t.style.setProperty('--p',((+t.value-t.min)/(t.max-t.min)*100)+'%');
function glide(t){const to=stepped(t),from=+t.value,t0=performance.now(),dur=140;
  const f=now=>{const k=Math.min(1,(now-t0)/dur);t.value=from+(to-from)*(1-Math.pow(1-k,3));paintRange(t);
    if(k<1)requestAnimationFrame(f);else{t.value=to;paintRange(t);t.step=t._step||1;t._drag=false;}};requestAnimationFrame(f);}
document.addEventListener('pointerdown',e=>{const t=e.target;if(t.classList&&t.classList.contains('range')&&t.tagName==='INPUT'){t._step=t.step||1;t.step='any';t._drag=true;}},true);
document.addEventListener('input',e=>{const t=e.target;
  if(t.id==='bassT'){S.bassLv=+t.value;apply();renderEq();}
  if(t.id==='bassR'){S.bassLv=stepped(t);bassUi(t);t.style.setProperty('--p',(+t.value-t.min)/(t.max-t.min)*100+'%');save();}
  if(t.id==='tapR'){t.style.setProperty('--p',(t.value-1)/4*100+'%');const v=document.getElementById('tapV');if(v)v.textContent=stepped(t);}
  if(t.id==='alertR'){S.alert=stepped(t);t.style.setProperty('--p',(+t.value-t.min)/(t.max-t.min)*100+'%');save();}});
/* equalizer knobs: drag in whole dB steps */
document.addEventListener('pointerdown',e=>{const k=e.target.closest('.knob');if(!k||k.closest('.locked'))return;e.preventDefault();k.setPointerCapture(e.pointerId);const i=+k.dataset.band,ed=document.getElementById('eqed');
  const mv=ev=>{const r=ed.getBoundingClientRect(),y=Math.min(Math.max(ev.clientY-r.top,0),r.height),raw=Math.max(-6,Math.min(6,(r.height/2-y)/(r.height/2)*6.6)),v=Math.round(raw);
    // The knob and the curve follow the pointer; the value, the label and the buds get whole dB.
    S.gains[i]=raw;if(S.cg[S.eqPreset])S.cg[S.eqPreset][i]=raw;renderEq();};
  const up=()=>{k.removeEventListener('pointermove',mv);k.removeEventListener('pointerup',up);
    const to=Math.round(S.gains[i]),from=S.gains[i],t0=performance.now();
    const f=now=>{const q=Math.min(1,(now-t0)/140),v=from+(to-from)*(1-Math.pow(1-q,3));S.gains[i]=v;if(S.cg[S.eqPreset])S.cg[S.eqPreset][i]=v;renderEq();
      if(q<1)requestAnimationFrame(f);else{S.gains[i]=to;if(S.cg[S.eqPreset])S.cg[S.eqPreset][i]=to;renderEq();save();window.QBLIVE&&QBLIVE.eq&&QBLIVE.eqSave();}};
    requestAnimationFrame(f);};
  k.addEventListener('pointermove',mv);k.addEventListener('pointerup',up);});
document.addEventListener('keydown',e=>{const k=e.target.closest&&e.target.closest('.knob');if(!k||k.closest('.locked'))return;const i=+k.dataset.band;
  if(e.key==='ArrowUp'||e.key==='ArrowDown'){e.preventDefault();S.gains[i]=Math.max(-6,Math.min(6,S.gains[i]+(e.key==='ArrowUp'?1:-1)));renderEq();save();window.QBLIVE&&QBLIVE.eq&&QBLIVE.eqSave();}});
function setView(v){S.view=v;const tray=document.getElementById('tray'),win=document.getElementById('win'),desk=document.getElementById('desk');
  tray.hidden=v!=='tray';win.hidden=v!=='window';fit();desk.classList.toggle('tray-view',v==='tray');
  if(v==='tray'){tray.classList.remove('pop');void tray.offsetWidth;tray.classList.add('pop');replay(tray);}else replay(win.querySelector('.page:not([hidden]) .enter'));
  apply();requestAnimationFrame(()=>pixelize(document));}
document.getElementById('szSeg').addEventListener('click',e=>{const b=e.target.closest('.pc');if(!b)return;S.size=b.dataset.v;save();apply();fit();requestAnimationFrame(()=>{renderEq();renderBuds();moveNav();document.querySelectorAll('[data-ic],.ring,.sw').forEach(e=>e._dz=null);pixelize(document);});});
document.getElementById('bsSeg').addEventListener('click',e=>{const b=e.target.closest('.pc');if(!b)return;S.budState=b.dataset.v;save();renderBuds();});
document.getElementById('vWin').addEventListener('click',()=>setView('window'));
document.getElementById('vTray').addEventListener('click',()=>setView('tray'));
document.getElementById('sysMode').addEventListener('click',ev=>reveal(ev,()=>{S.sysLight=!S.sysLight;apply();if(S.colorMode!=='palette')renderColorPanel();}));
try{matchMedia('(prefers-color-scheme: light)').addEventListener('change',e=>{S.sysLight=e.matches;apply();});}catch(e){}
document.getElementById('wpFile').addEventListener('change',e=>{const f=e.target.files&&e.target.files[0];if(!f)return;const url=URL.createObjectURL(f),img=new Image();
  img.onload=()=>{const c=document.createElement('canvas');c.width=c.height=48;const x=c.getContext('2d');x.drawImage(img,0,0,48,48);const d=x.getImageData(0,0,48,48).data,bins=new Array(36).fill(0),sums=bins.map(()=>[0,0,0,0]);
    for(let i=0;i<d.length;i+=4){const[L,C,h]=rgbToOklch(intOf(d[i],d[i+1],d[i+2]));if(C<0.03||L<0.15||L>0.95)continue;const b=Math.floor(h/10)%36;bins[b]+=C;const s=sums[b];s[0]+=d[i];s[1]+=d[i+1];s[2]+=d[i+2];s[3]++;}
    const s=sums[bins.indexOf(Math.max(...bins))];S.uploadSeed=s[3]?intOf(s[0]/s[3],s[1]/s[3],s[2]/s[3]):0x6B6B73;uploadUrl=url;S.wall='upload';reveal(null,()=>{apply();renderColorPanel();});};img.src=url;});
document.addEventListener('pointermove',e=>{if(S.style!=='glass')return;const el=e.target.closest('.card,.group>.row');if(!el)return;const r=el.getBoundingClientRect();el.style.setProperty('--mx',(e.clientX-r.left)+'px');el.style.setProperty('--my',(e.clientY-r.top)+'px');});

/* the window keeps its desktop size; a narrow screen shows it scaled down, not rearranged */
function fit(){const desk=document.getElementById('desk'),win=document.getElementById('win'),cs=getComputedStyle(desk);
  const avail=desk.clientWidth-parseFloat(cs.paddingLeft)-parseFloat(cs.paddingRight),s=Math.min(1,avail/(S.size==='s'?600:800));win.style.zoom=s<1?s.toFixed(3):'';}
addEventListener('resize',fit);
/* ================= boot ================= */
READY.then(()=>{
document.getElementById('wps').innerHTML=WALLS.map((w,i)=>`<button class="wp" data-act="wall" data-v="${i}" style="background:${w.css}" aria-label="${w.name} wallpaper" title="${w.name}"></button>`).join('');
buildWindow();buildTray();
if(!document.querySelector(`.page[data-page="${S.page}"]`)||S.page==='dual')S.page='overview';
document.querySelectorAll('#win .page').forEach(p=>p.hidden=p.dataset.page!==S.page);
apply();renderColorPanel();renderEq();renderHearing();renderGestures();renderBuds();fit();
if(document.fonts&&document.fonts.ready)document.fonts.ready.then(()=>{document.querySelectorAll('[data-ic],.ring,.sw').forEach(e=>e._dz=null);pixelize(document);});

/* ===== live link (Tauri): the state of the earbuds comes in, the controls go out ===== */
if(window.__TAURI__){
  const T=window.__TAURI__,LV=['ANC-Light','ANC-Medium','ANC-Deep','ANC-Smart'];
  let snap=null;
  const wearText=(w,chg)=>w===3||w===7?$t('status_in_ear'):w===4?$t('status_in_case'):w===1||w===5?$t('status_out'):'';
  const hzLabel=f=>f>=1000?(f/1000)+'k':String(f);
  const curEq=()=>{const e=QBLIVE.eq;return e&&e.custom.find(p=>p.id===e.current);};
  window.QBLIVE={
    live:false,eq:null,earbud:null,
    send:c=>T.core.invoke('send',{cmd:c}),
    toggleConn(){this.send({t:snap&&snap.status==='on'?'disconnect':'connect'});},
    sendAnc(){const m=S.anc===0?'Off':S.anc===1?LV[S.level]:S.anc===2?'Adaptive':'Transparency';this.send({t:'anc',mode:m});},
    buds(){const b=snap&&snap.status==='on'?snap.battery:[null,null,null],w=snap?snap.wear:[0,0,0];
      const one=(i)=>[b[i]?b[i].level:null,i===2?'':wearText(w[i],b[i]&&b[i].charging)];
      return {L:one(0),R:one(1),C:one(2)};},
    eqPick(name){const e=this.eq,b=e.builtins.find(x=>x.name===name),c=e.custom.find(x=>x.name===name);
      if(b)this.send({t:'eq_builtin',id:b.id});else if(c)this.send({t:'eq_select',id:c.id});},
    // New and delete show at once; the buds' re-read takes about a second and would show the old list first.
    eqLocal(eq,ms){takeEq(eq);this.eqHold=Date.now()+ms;clearTimeout(this.eqT);this.eqT=setTimeout(()=>{if(snap&&snap.eq)takeEq(snap.eq);},ms+50);},
    eqNew(){const e=this.eq;if(e.custom.length>=e.max)return;
      for(let i=1;i<=9;i++){const n='Custom'+i;if(!e.custom.some(p=>p.name===n)){
        this.send({t:'eq_create',name:n});
        this.eqLocal(Object.assign({},e,{current:-1,custom:[...e.custom,{id:-1,name:n,gains:new Array(e.freqs.length).fill(0),freqs:e.freqs}]}),2000);return;}}},
    eqDel(){const p=curEq();if(!p)return;const e=this.eq;this.send({t:'eq_delete',id:p.id});
      this.eqLocal(Object.assign({},e,{current:e.builtins.length?e.builtins[0].id:e.current,custom:e.custom.filter(x=>x.id!==p.id)}),2500);},
    eqSave(){const p=curEq();if(p)this.send({t:'eq_gains',id:p.id,gains:S.gains});},
  };
  // The EQ page follows the buds: their bands, presets and custom slots.
  const takeEq=eq=>{
    QBLIVE.eq=eq.builtins.length||eq.hasCustom?eq:null;if(!QBLIVE.eq)return;
    const n=eq.freqs.length;
    if(FREQS.join()!==eq.freqs.map(hzLabel).join()){
      FREQS.length=0;eq.freqs.forEach(f=>FREQS.push(hzLabel(f)));
      const bands=document.querySelector('#eqed .bands');
      if(bands)bands.innerHTML=FREQS.map((f,i)=>`<div class="band"><div class="rail"></div><div class="bar"></div><button class="knob" data-band="${i}" aria-label="${f} Hz band" role="slider" aria-valuemin="-6" aria-valuemax="6"></button><div class="val"></div><div class="hz">${f}</div></div>`).join('');}
    Object.keys(PRESETS).forEach(k=>delete PRESETS[k]);eq.builtins.forEach(b=>PRESETS[b.name]=new Array(n).fill(0));
    S.custom=Array.from({length:eq.max},(_,i)=>eq.custom[i]?eq.custom[i].name:null);
    S.cg={};eq.custom.forEach(p=>S.cg[p.name]=p.gains.slice());
    const cur=eq.builtins.find(b=>b.id===eq.current)||eq.custom.find(p=>p.id===eq.current);
    S.eqPreset=cur?cur.name:'';
    const cp=curEq();S.gains=cp?cp.gains.slice():new Array(n).fill(0);
    const br=document.getElementById('bassR');
    if(br){br.min=-5;br.max=5;if(eq.bass.level!=null)S.bassLv=eq.bass.level;}
    renderEq(true);};
  const take=s=>{
    if(!s)return;snap=s;QBLIVE.live=true;
    // Disconnected: the tiles dim and go inert (the phone's DISABLED_ALPHA); the connect button's text cross-fades.
    document.documentElement.classList.toggle('off',s.status!=='on');
    if(s.status==='on'&&QBLIVE._was!=='on'&&['earbud','gestures','hearing'].includes(S.page))setTimeout(()=>QBLIVE.pageReads(S.page),300);
    // The two-device list is read as soon as the buds connect, so Earbud settings shows it at once.
    if(s.status==='on'&&QBLIVE._was!=='on'&&s.has&&s.has.dual)setTimeout(()=>QBLIVE.send({t:'dual_open'}),1500);
    // A support card: 3 s after the buds connect, in the main window only, when no other dialog is open.
    if(s.status==='on'&&QBLIVE._was!=='on'&&S.view!=='tray')setTimeout(()=>{if(snap&&snap.status==='on'&&!document.querySelector('.scrim'))QBLIVE.supportCard();},3000);
    QBLIVE._was=s.status;
    const sp=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--speed'))||1,
      fade=(sel,label)=>document.querySelectorAll(sel).forEach(el=>{
        if(el.textContent===label)return;
        if(!el._set){el.textContent=label;el._set=true;return;}
        el.animate({opacity:[1,0]},{duration:120*sp}).onfinish=()=>{el.textContent=label;el.animate({opacity:[0,1]},{duration:120*sp});};});
    fade('[data-ca]',s.status==='on'?s.t.disconnect:s.status==='connecting'?s.t.connecting:s.t.connect);
    fade('[data-cs]',s.status==='on'?s.t.on:s.status==='connecting'?s.t.connecting:s.t.off);
    document.querySelectorAll('[data-cn]').forEach(e=>e.textContent=s.model||s.name||'QuickBuds');
    document.documentElement.classList.toggle('cing',s.status==='connecting');
    if(s.status==='on'&&s.anc){
      if(s.anc==='Off')S.anc=0;else if(s.anc.startsWith('ANC')){S.anc=1;const i=LV.indexOf(s.anc);if(i>=0)S.level=i;}
      else if(s.anc==='Adaptive')S.anc=2;else if(s.anc==='Transparency')S.anc=3;}
    if(s.lowLatency!=null&&!(S._llHold&&S._llHold.until>Date.now()&&s.lowLatency!==S._llHold.on))S.ll=s.lowLatency;
    if(s.status==='on'){Object.assign(S,s.sw);}
    if(s.status==='on'&&s.eq&&!(QBLIVE.eqHold>Date.now()))takeEq(s.eq);
    const eb=s.earbud;QBLIVE.earbud=s.status==='on'?eb:null;
    if(eb&&eb.alert!=null){S.alert=eb.alert;const ar=document.getElementById('alertR');if(ar){ar.min=1;ar.max=10;}}
    if(s.firmware)document.querySelectorAll('.mono').forEach(e=>e.textContent=s.firmware);
    document.querySelectorAll('[data-model]').forEach(e=>e.textContent=s.model||s.name||'No earbuds');
    if(s.status!==undefined&&s.gestures)QBLIVE.takeMore(s);QBLIVE.takeTest(s);
    apply();renderBuds();
    document.querySelectorAll('[data-model]').forEach(e=>e.textContent=s.model||s.name||'');};
  // Dev tools: the packet log of the link thread, while its page is open.
  let logNext=0;
  setInterval(()=>{const box=document.getElementById('devlog');if(!box||S.page!=='dev'||document.hidden)return;
    T.core.invoke('get_log',{from:logNext}).then(r=>{if(!r.lines.length)return;logNext=r.next;
      const stick=box.scrollTop+box.clientHeight>=box.scrollHeight-8;
      for(const l of r.lines){const d=document.createElement('div');d.className='ll '+l.dir.split(' ')[0].toLowerCase();
        d.textContent=`${l.at}  ${l.dir.padEnd(4)}  ${l.human||''}  ${l.hex?'· '+l.hex:''}`;box.appendChild(d);}
      while(box.childElementCount>2000)box.firstChild.remove();
      if(stick)box.scrollTop=box.scrollHeight;});},500);
  // ================= pages beyond the Overview =================
  const send=c=>QBLIVE.send(c);
  const confirmDialog=(title,msg,ok,onOk)=>dialog(`<h3>${esc(title)}</h3><div class="s" style="text-align:center;margin:0;white-space:pre-line">${esc(msg)}</div><div class="acts two"><button class="btn press" data-close>${esc($t('dialog_cancel'))}</button><button class="btn pri press" id="cfOk">${esc(ok)}</button></div>`,(d,close)=>d.querySelector('#cfOk').addEventListener('click',()=>{onOk();close();}));
  const infoDialog=(title,msg)=>dialog(`<h3>${esc(title)}</h3><div class="s" style="text-align:center;margin:0;white-space:pre-line">${esc(msg)}</div><div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button></div>`);
  const setText=(el,t)=>{if(el&&el.textContent!==t)el.textContent=t;};
  const byId=id=>document.getElementById(id);

  // ---- gestures ----
  const gDesc=(g,c)=>c==null?'—':!c.length?$t('gesture_not_set'):c.map(i=>g.actions[i].short).join(', ');
  window.renderGesturesLive=function(){
    const g=QBLIVE.gest,one=g.oneButton,gt=byId('gt');if(!gt)return;
    const sel=(side,r)=>`<button class="sel press pick" data-act="glive" data-g="${r.g}" data-side="${side}" aria-label="${esc(r.label)}">${esc(gDesc(g,r.cur[side-1]))}</button>`;
    const row=r=>`<tr class="r"><td><div class="gl">${ic(r.icon)}${esc(r.label)}</div></td><td>${sel(1,r)}</td>${one?'':`<td>${sel(2,r)}</td>`}</tr>`;
    const span=one?1:2,sect=t=>`<tr class="sect"><td colspan="${span+1}">${esc(t)}</td></tr>`;
    const both=g.bothHold?`<tr class="r"><td><div class="gl">${ic('hold')}${esc($t('gesture_both_hold'))}</div></td><td colspan="${span}"><button class="sel press pick" data-act="gboth">${esc(g.bothHold.on==null?'—':$t(g.bothHold.on?'gesture_action_game':'gesture_action_none'))}</button></td></tr>`:'';
    const call=c=>`<tr class="r"><td><div class="gl">${ic(c.icon)}${esc(c.label)}</div></td><td colspan="${span}"><button class="sel press pick" data-act="gcall" data-k="${c.k}">${esc(c.on==null?'—':c.on?c.on_label:$t('gesture_action_none'))}</button></td></tr>`;
    gt.innerHTML=`<thead><tr><th></th><th><span class="budh">${ic('budL')}${esc($t('status_left'))}</span></th>${one?'':`<th><span class="budh">${ic('budR')}${esc($t('status_right'))}</span></th>`}</tr></thead>
      <tbody>${g.rows.length||both?sect($t('gesture_section_not_in_call')):''}${g.rows.map(row).join('')}${both}${g.calls.length?sect($t('gesture_section_on_call')):''}${g.calls.map(call).join('')}</tbody>`;
    pixelize(gt);};
  function noiseDialog(r,side){
    const g=QBLIVE.gest;let working=(r.cur[side-1]||[]).filter(i=>g.actions[i].noise);
    dialog(`<h3>${esc(r.label)}</h3><div class="group opts">${r.options.map(a=>`<button class="row press pkopt" data-o="${a}" aria-pressed="${working.includes(a)}"><div class="txt"><div class="t">${esc(g.actions[a].label)}</div></div>${working.includes(a)?ic('check','ch'):''}</button>`).join('')}</div>
      <div class="s" style="text-align:center;margin:0;min-height:1em">${g.holdMin===1?esc($t('gesture_hold_rule')):''}</div>
      <div class="acts two"><button class="btn press" data-close>${esc($t('dialog_cancel'))}</button><button class="btn pri press" id="nzGo">${esc($t('eq_save'))}</button></div>`,
    (d,close)=>{d.classList.add('pk');
      d.addEventListener('click',e=>{const b=e.target.closest('.pkopt');if(!b)return;const a=+b.dataset.o,on=working.includes(a);
        // Never below the model's minimum.
        if(on){if(working.length>g.holdMin)working=working.filter(x=>x!==a);}else working.push(a);
        d.querySelectorAll('.pkopt').forEach(x=>{const on=working.includes(+x.dataset.o),c=x.querySelector('.ch');x.setAttribute('aria-pressed',String(on));if(on&&!c)x.insertAdjacentHTML('beforeend',ic('check','ch'));if(!on&&c)c.remove();});});
      d.querySelector('#nzGo').addEventListener('click',()=>{
        // In the list's order, so the cycle matches what was shown.
        if(working.length>=g.holdMin)send({t:'gesture',g:r.g,side,actions:r.options.filter(x=>working.includes(x))});close();});});}
  QBLIVE.gestureDialog=function(gi,side){
    const g=this.gest,r=g.rows.find(x=>x.g===gi),cur=r.cur[side-1];
    if(r.hold){
      if(g.perBud&&r.choices.length){const noise=cur&&cur.some(i=>g.actions[i].noise);
        pickDialog(r.label,r.choices.map(c=>[c.v,c.label]),noise?-1:(cur&&cur.length===1?cur[0]:-2),v=>{if(v==='-2')return;if(+v===-1)noiseDialog(r,side);else send({t:'gesture',g:gi,side,actions:[+v]});});}
      else noiseDialog(r,side);
      return;}
    pickDialog(r.label,r.options.map(i=>[i,g.actions[i].label]),cur&&cur.length?cur[0]:-2,v=>{if(v==='-2')return;send({t:'gesture',g:gi,side,actions:[+v]});});};

  // ---- the model ----
  QBLIVE.modelDialog=function(){
    const m=this.models;
    pickDialog($t('model_title'),[['',$t('model_auto')],...m.sections.flatMap(s=>s.rows.map(r=>[r.id,r.name+(r.sub?` · ${r.sub}`:'')]))],m.current,v=>send({t:'model',id:v}),'scroll');};

  // ---- Earbud settings ----
  const featRow=r=>`<div class="row" data-fid="${r.id}">${ic(r.icon)}<div class="txt"><div class="t">${esc(r.title)}</div><div class="s">${esc(r.busy?$t('feature_applying'):r.sub)}</div></div><button class="sw" role="switch" data-act="feat" data-id="${r.id}" aria-checked="${r.on}" aria-label="${esc(r.title)}"></button></div>`;
  const pickRow=r=>`<button class="row press" data-act="featpick" data-id="${r.id}">${ic('chev','ch')}<div class="txt"><div class="t">${esc(r.pick.title)}</div><div class="s">${esc(r.pick.label||'—')}</div></div></button>`;
  let featSig='';
  function renderEarbud(){
    const ex=QBLIVE.ex,eb=QBLIVE.earbud,g=byId('earFeat');if(!ex||!g)return;
    const rows=[];if(ex.pnc)rows.push({id:'pnc',icon:'anc',title:$t('pnc_title'),sub:$t('pnc_sub'),on:ex.pnc.on,busy:false});
    ex.rows.forEach(r=>rows.push(r));
    const sig=rows.map(r=>r.id+(r.pick&&r.on?'p':'')).join();
    if(sig!==featSig){featSig=sig;g.innerHTML=rows.map(r=>featRow(r)+(r.pick&&r.on?pickRow(r):'')).join('');pixelize(g);}
    else rows.forEach(r=>{const row=g.querySelector(`[data-fid="${r.id}"]`);if(!row)return;row.querySelector('.sw').setAttribute('aria-checked',String(r.on));
      setText(row.querySelector('.s'),r.busy?$t('feature_applying'):r.sub);
      if(r.pick&&r.on){const pr=g.querySelector(`[data-act="featpick"][data-id="${r.id}"] .s`);setText(pr,r.pick.label||'—');}});
    byId('earFeatBox').hidden=!rows.length;
    byId('tileFind').hidden=!ex.find;byId('tileFit').hidden=!ex.fit;byId('earTiles').hidden=!ex.find&&!ex.fit;
    byId('fwBox').hidden=!ex.firmwareRow;
    byId('alertRow').hidden=!(eb&&eb.hasAlert);byId('tapBox').hidden=!(eb&&eb.hasTap);
    byId('wearRow').hidden=!ex.wear;
    byId('earSnd').hidden=!(ex.wear||(eb&&eb.hasAlert));
    if(eb&&eb.hasTap){const tr=byId('tapR');if(tr&&eb.tap){tr.min=1;tr.max=5;if(document.activeElement!==tr){tr.value=eb.tap.v;tr.style.setProperty('--p',(eb.tap.v-1)/4*100+'%');}
      setText(byId('tapV'),String(eb.tap.v));byId('tapWarn').hidden=!(eb.tap.default>0&&eb.tap.v<eb.tap.default);}}}
  QBLIVE.renderEarbud=renderEarbud;
  // The Overview's Hi-Res and 3D rows: a picker for the codec and for the 3D audio type, locked Hi-Res.
  function rowPick(k,label,act){
    const sw=document.querySelector(`#win .sw[data-k="${k}"]`);if(!sw)return;const row=sw.closest('.row');let b=row.querySelector('.rowpick');
    if(label==null){if(b)b.remove();return;}
    if(!b){b=document.createElement('button');b.className='sel press pick rowpick';b.style.cssText='width:auto;min-width:64px;max-width:120px;padding:0 10px';row.insertBefore(b,sw);}
    b.dataset.act=act;setText(b,label);}
  function overviewRows(s){
    const ex=s.earbudx,has=s.has,on=s.status==='on';
    const rowOf=k=>{const e=document.querySelector(`#win .sw[data-k="${k}"]`);return e&&e.closest('.row');};
    [['ll',has.game],['hq',has.hq],['sp',has.sp],['hp',has.hp],['dual',has.dual],['wear',has.wear],['bass',has.eq&&s.eq.bass.has],['wind',ex.wind.has]].forEach(([k,v])=>{const r=rowOf(k);if(r)r.hidden=!v;});
    document.querySelectorAll('#win .nav').forEach(n=>{const v=n.dataset.v;if(v==='eq')n.hidden=!has.eq;if(v==='gestures')n.hidden=!has.controls;if(v==='hearing')n.hidden=!has.hp;});
    rowPick('hq',ex.hq.picker?(ex.hq.codec||'—'):null,'codec');
    rowPick('sp',ex.spatial.head?ex.spatial.label:null,'spatialtype');
    const hq=document.querySelector('#win .sw[data-k="hq"]');if(hq)hq.style.opacity=ex.hq.locked?.4:'',hq&&(hq.style.pointerEvents=ex.hq.locked?'none':'');}
  function dualRender(){
    const d=QBLIVE.dual,box=byId('devs');if(!d||!box)return;
    box.innerHTML=(d.devices.length?'':`<div class="devempty">${ic('devices')}<div class="s">${esc($t('desktop_dual_empty'))}</div></div>`)+d.devices.map((v,i)=>`<${v.can?'button':'div'} class="dev${v.can?' press':''}" ${v.can?`data-act="devtoggle" data-i="${i}"`:''}><span class="dd" style="${v.connected?'':'background:var(--text2)'}"></span><div class="txt"><div class="t">${esc(v.name)}</div><div class="s">${esc(v.state)}</div></div>${v.can?`<span class="v">${esc(v.action)}</span>`:''}</${v.can?'button':'div'}>`).join('')
      +(d.manages?`<button class="dev add press" data-act="devadd">${ic('plus')}<div class="txt"><div class="t">${esc($t('dual_add_title'))}</div></div>${ic('chev','ch')}</button>`:'')
      +(d.hasPreferred?`<button class="dev press" data-act="devpref">${ic('devices')}<div class="txt"><div class="t">${esc($t('dual_preferred'))}</div><div class="s">${esc(d.preferred)}</div></div>${ic('chev','ch')}</button>`:'');
    pixelize(box);}
  QBLIVE.devAsk=function(i){const v=this.dual.devices[i];if(v)confirmDialog(v.name,v.ask,v.action,()=>send({t:'dev_toggle',i}));};
  QBLIVE.prefDialog=function(){const d=this.dual;pickDialog($t('dual_preferred'),d.preferredChoices.map((n,i)=>[i,n]),d.preferredCur,v=>send({t:'preferred',v:+v}));};
  // Find my earbuds
  let findPlaying=false;
  QBLIVE.findDialog=function(){const ex=this.ex;let warn=false;
    const html=()=>`<h3>${esc($t(warn?'find_warn_title':'find_title'))}</h3><div class="fitbuds"><div class="fb">${ic('budL')}${esc($t('status_left'))}</div><div class="fb">${ic('budR')}${esc($t('status_right'))}</div></div><div class="s" id="fMsg" style="text-align:center;margin:0">${esc($t(warn?'find_warn_msg':'find_hint'))}</div><div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button><button class="btn pri press" id="fPlay"></button></div>`;
    dialog(html(),d=>{const b=d.querySelector('#fPlay'),bb=d.querySelector('.fitbuds'),msg=d.querySelector('#fMsg'),h3=d.querySelector('h3');
      const paint=()=>{b.textContent=$t(findPlaying?'find_stop':warn?'find_warn_play':'find_play');bb.classList.toggle('wave',findPlaying);h3.textContent=$t(warn?'find_warn_title':'find_title');msg.textContent=$t(warn?'find_warn_msg':'find_hint');};paint();
      b.addEventListener('click',()=>{
        if(findPlaying){findPlaying=false;send({t:'find',on:false});}
        else if(!warn&&QBLIVE.ex.wornAny)warn=true;
        else{warn=false;findPlaying=true;send({t:'find',on:true});}
        paint();});},()=>{if(findPlaying){findPlaying=false;send({t:'find',on:false});}});};
  // Fit test: 0 ready, 1 insert the buds, 2 running, 3 failed, 4 perfect, 5 adjust.
  QBLIVE.fitDialog=function(){let state=0,shown=false,started=false,deadline=0,seq=this.ex.fitState.seq,timer=null;
    dialog(`<h3></h3><div class="fitbuds"><div class="fb">${ic('budL')}<span>${esc($t('status_left'))}</span><b id="fL">–</b></div><div class="fb">${ic('budR')}<span>${esc($t('status_right'))}</span><b id="fR">–</b></div></div><div class="s" id="fMsg" style="text-align:center;margin:0"></div><div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button><button class="btn pri press" id="fGo"></button></div>`,
    (d,close)=>{const h3=d.querySelector('h3'),msg=d.querySelector('#fMsg'),go=d.querySelector('#fGo'),bb=d.querySelector('.fitbuds');
      const word=v=>v===1?$t('fit_good'):v===0?$t('fit_average'):v===6?$t('fit_poor'):'–';
      const paint=()=>{const f=QBLIVE.ex.fitState;
        h3.textContent=$t(['fit_title','fit_insert','fit_keep','fit_failed','fit_perfect','fit_adjust'][state]);
        msg.textContent=state===2||state===4?'':state===5?$t('fit_adjust_tips').replace('%1$s',$t(f.l!==1&&f.r!==1?'fit_both':f.l!==1?'fit_left':'fit_right')):$t('fit_hint');
        go.textContent=$t(state===2?'fit_playing':state===4?'gesture_done':state===5?'fit_again':'fit_play');go.disabled=state===2;bb.classList.toggle('wave',state===2);
        d.querySelector('#fL').textContent=shown?word(f.l):'–';d.querySelector('#fR').textContent=shown?word(f.r):'–';};
      go.addEventListener('click',()=>{if(state===4){close();return;}
        if(!QBLIVE.ex.worn){state=1;paint();return;}
        state=2;started=true;shown=false;seq=QBLIVE.ex.fitState.seq;deadline=Date.now()+15000;send({t:'fit',on:true});paint();});
      timer=setInterval(()=>{const f=QBLIVE.ex.fitState;
        if(state===2){if(f.seq!==seq){seq=f.seq;const ok=v=>v===0||v===1||v===6;
            if(!ok(f.l)||!ok(f.r))state=3;else{shown=true;state=f.l===1&&f.r===1?4:5;}paint();}
          else if(Date.now()>=deadline){state=3;paint();}}},250);
      paint();},()=>{clearInterval(timer);if(started){started=false;send({t:'fit',on:false});}});};
  // Personalized ANC: 0 asking, 1 stored result offered, 2 test intro, 3 testing, 4 failed, 5 wear both buds, 6 done.
  QBLIVE.pncDialog=function(){let state=0,reason=0,deadline=0,seq=this.ex.pncState.seq,timer=null;
    dialog(`<h3></h3><div class="s" id="pMsg" style="text-align:center;margin:0"></div><div class="acts two"><button class="btn press" id="pLeft"></button><button class="btn pri press" id="pGo"></button></div>`,(d,close)=>{
      const h3=d.querySelector('h3'),msg=d.querySelector('#pMsg'),l=d.querySelector('#pLeft'),go=d.querySelector('#pGo');
      const paint=()=>{h3.textContent=$t(['pnc_title','pnc_stored_title','pnc_test_title','pnc_title','pnc_failed','pnc_title','pnc_title'][state]);
        const why=['','pnc_fail_quiet','pnc_fail_fit','pnc_fail_wind','pnc_fail_still','pnc_fail_audio'][reason<=5?reason:0];
        msg.textContent=[$t('conn_connecting'),$t('pnc_stored_body'),$t('pnc_test_body'),$t('pnc_testing'),why?$t(why):'',$t('pnc_wear'),$t('pnc_done')][state];
        l.textContent=$t(state===1?'pnc_test_again':state===6?'dialog_close':'dialog_cancel');
        go.textContent=[1,2,4,5].includes(state)?$t(state===1?'pnc_use':state===2?'pnc_start':'pnc_retry'):'';go.style.visibility=go.textContent?'':'hidden';};
      const start=()=>{if(!QBLIVE.ex.worn){state=5;return paint();}state=0;seq=QBLIVE.ex.pncState.seq;deadline=Date.now()+5000;send({t:'pnc_query'});paint();};
      const test=()=>{if(!QBLIVE.ex.worn){state=5;return paint();}state=3;deadline=Date.now()+15000;send({t:'pnc',v:1});paint();};
      const finish=()=>{if(state===3)send({t:'pnc',v:3});state=0;close();};
      l.addEventListener('click',()=>{if(state===1)test();else finish();});
      go.addEventListener('click',()=>{if(state===1){send({t:'pnc',v:2});state=0;close();}else if(state===5)start();else test();});
      timer=setInterval(()=>{const p=QBLIVE.ex.pncState;
        if(p.seq!==seq){seq=p.seq;const k=p.kind,v=p.value;
          if(k===1&&state===0){state=v?1:2;deadline=0;}
          if(k===2&&state===3&&v){reason=0;state=4;}
          if(k===3&&state===3){if(v===0){state=6;send({t:'status_read'});}else{reason=v;state=4;}}
          paint();}
        if(deadline&&Date.now()>=deadline){deadline=0;if(state===0)state=2;else if(state===3){reason=0;state=4;}paint();}},250);
      start();},()=>{clearInterval(timer);});};

  // ---- hearing profile ----
  window.renderHearingLive=function(){
    const h=QBLIVE.hear;if(!h||!byId('radar'))return;const st=h.state;
    const cx=150,cy=135,R=105,ang=[240,300,0,60,120,180].map(a=>a*Math.PI/180),pt=(r,a)=>`${(cx+r*Math.cos(a)).toFixed(1)},${(cy+r*Math.sin(a)).toFixed(1)}`;
    const L=st.radar?st.radar.left.map(v=>v/10):null,Rr=st.radar?st.radar.right.map(v=>v/10):null;
    const HZl=st.axes.map(f=>f>=1000?(f/1000)+' kHz':f+' Hz');
    // Rebuild only when the data changes: a rebuild replays the bar animation and makes the page jump.
    const dkey=JSON.stringify([st.axes,st.radar]),rd=byId('radar');
    if(rd._k!==dkey){rd._k=dkey;
    let s='';[1,.75,.5,.25].forEach(k=>s+=`<polygon class="grid" points="${ang.map(a=>pt(R*k,a)).join(' ')}"/>`);
    ang.forEach(a=>s+=`<line x1="${cx}" y1="${cy}" x2="${(cx+R*Math.cos(a)).toFixed(1)}" y2="${(cy+R*Math.sin(a)).toFixed(1)}" class="grid"/>`);
    if(L)s+=`<polygon class="lft" style="opacity:${S.showL?1:0}" points="${L.map((v,i)=>pt(R*v,ang[i])).join(' ')}"/><polygon class="rgt" style="opacity:${S.showR?1:0}" points="${Rr.map((v,i)=>pt(R*v,ang[i])).join(' ')}"/>`;
    ang.forEach((a,i)=>{const x=cx+(R+18)*Math.cos(a),y=cy+(R+16)*Math.sin(a)+4;s+=`<text x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="${Math.abs(Math.cos(a))<.2?'middle':Math.cos(a)>0?'start':'end'}">${HZl[i]}</text>`;});
    rd.innerHTML=s;
    const order=[0,5,4,3,2,1];
    byId('freq').innerHTML=L?order.map(i=>`<span class="hz">${HZl[i]}</span><div class="bars"><span style="width:${L[i]*100}%;opacity:${S.showL?1:.15}"></span><span class="r" style="width:${Rr[i]*100}%;opacity:${S.showR?1:.15}"></span></div>`).join(''):'';}
    // The two legend buttons only fade the lines.
    rd.querySelector('.lft')&&(rd.querySelector('.lft').style.opacity=S.showL?1:0);rd.querySelector('.rgt')&&(rd.querySelector('.rgt').style.opacity=S.showR?1:0);
    byId('freq').querySelectorAll('.bars').forEach(b=>{b.firstChild.style.opacity=S.showL?1:.15;b.lastChild.style.opacity=S.showR?1:.15;});
    document.querySelectorAll('.lg').forEach(b=>b.setAttribute('aria-pressed',String(!!S[b.dataset.v])));
    const pr=byId('hprof'),pkey=JSON.stringify([st.profiles.map(p=>p.name),st.active,h.hasTest]);
    if(pr._k===pkey)return;pr._k=pkey;
    pr.innerHTML=st.profiles.map((p,i)=>`<div class="card tl press${st.active===i?' on':''}" data-act="hpick" data-i="${i}" role="button" tabindex="0"><div class="tlh"><span class="dot" style="background:var(--accent)"></span><span class="t">${esc(p.name)}</span></div><button class="btn icon press tla" data-act="hdel" data-i="${i}" aria-label="${esc($t('eq_delete'))}">${ic('del')}</button></div>`).join('')
      +(st.profiles.length?'':`<div class="card tl"><span class="t">${esc($t('golden_none'))}</span></div>`)
      +(h.hasTest?`<button class="card tl go press" data-act="htest">${ic('hearing')}<span class="t">${esc($t('golden_test_row'))}</span><span class="s">${esc($t('golden_test_sub'))}</span></button>`:'');
    pixelize(pr);};
  function hearTestDialog(){send({t:'htest_open'});
    dialog(`<h3 id="htT"></h3><div class="s" id="htM" style="text-align:center;margin:0;min-height:2.6em"></div><input type="range" class="range" id="htS" min="0" max="24" aria-label="${esc($t('golden_test_title'))}"><div class="acts two"><button class="btn press" data-close>${esc($t('dialog_cancel'))}</button><button class="btn pri press" id="htB"></button></div>`,
    (d,close)=>{const T_=d.querySelector('#htT'),M=d.querySelector('#htM'),S_=d.querySelector('#htS'),B=d.querySelector('#htB');
      const upd=()=>{const t=QBLIVE.hear.state.test;T_.textContent=t.title;M.textContent=t.message;B.textContent=t.label;B.disabled=!t.enabled&&!t.done;
        S_.style.visibility=t.slider?'':'hidden';if(document.activeElement!==S_){S_.value=t.stop;}S_.style.setProperty('--p',S_.value/24*100+'%');};
      QBLIVE.onHear=upd;upd();
      S_.addEventListener('input',()=>{S_.style.setProperty('--p',S_.value/24*100+'%');send({t:'htest_moved',v:+S_.value});});
      B.addEventListener('click',()=>{if(QBLIVE.hear.state.test.done){close();return;}send({t:'htest_action'});});},
    ()=>{QBLIVE.onHear=null;send({t:'htest_closed'});});}
  QBLIVE.hearTestDialog=hearTestDialog;

  // ---- one pass over a new state ----
  const takeMore=s=>{
    QBLIVE.gest=s.gestures;QBLIVE.ex=s.earbudx;QBLIVE.models=s.models;QBLIVE.dual=s.dual;QBLIVE.hear=s.hearing;QBLIVE.hear.hasTest=s.hearing.hasTest;
    S.wind=s.earbudx.wind.on;
    overviewRows(s);renderEarbud();dualRender();renderGesturesLive();renderHearingLive();
    const m=s.models,cur=m.current&&m.sections.flatMap(x=>x.rows).find(r=>r.id===m.current);
    document.querySelectorAll('[data-modelfull]').forEach(e=>e.textContent=cur?cur.name:`${$t('model_auto')}${s.model?` (${s.model})`:''}`);
    byId('dualW')&&byId('dualW').classList.toggle('off',!S.dual);};
  QBLIVE.takeMore=takeMore;
  T.event.listen('hearing',e=>{if(!QBLIVE.hear)return;QBLIVE.hear.state=e.payload;renderHearingLive();QBLIVE.onHear&&QBLIVE.onHear();});
  T.event.listen('reload',()=>location.reload());

  // ---- app settings, update, feedback, test, about ----
  let AS=null;   // get_settings
  const loadSettings=()=>T.core.invoke('get_settings').then(st=>{AS=st;
    S.count=st.usage_ping;S.hide=st.clear_battery;S.devtools=st.dev_tools_button;S.upd=st.update_auto;S.svc=st.autostart;S.lang=st.language;
    const iv=byId('updInst');if(iv)iv.textContent=st.version;
    const ab=byId('aboutVer');if(ab)ab.textContent=$t('about_version').replace('%1$s',st.version);
    const rp=byId('replyBox');if(rp){rp.hidden=!st.reply;if(st.reply)rp.querySelector('.prose').textContent=st.reply;}
    apply();});
  loadSettings();
  // The due card (Ko-fi or a GitHub star): Support opens the page; Later and a closed dialog bring it back in 30 days.
  QBLIVE.supportCard=()=>T.core.invoke('support_due').then(c=>{if(!c)return;
    dialog(`<h3>${esc(c.title)}</h3><div class="s" style="text-align:center;margin:0;white-space:pre-line">${esc(c.body)}</div><div class="acts two"><button class="btn press" data-close>${esc(c.later)}</button><button class="btn pri press" id="spOk">${esc(c.action)}</button></div><div class="acts"><button class="btn press" id="spNever">${esc(c.never)}</button></div>`,
      (d,close)=>{d.querySelector('#spOk').addEventListener('click',()=>{T.core.invoke('support_off',{card:c.card});T.core.invoke('open_url',{url:c.url});close();});
        d.querySelector('#spNever').addEventListener('click',()=>{T.core.invoke('support_off',{card:c.card});close();});});});
  const SETKEY={count:'usage_ping',hide:'clear_battery',devtools:'dev_tools_button',upd:'update_auto'};
  QBLIVE.setting=(k,on)=>{if(SETKEY[k])T.core.invoke('set_setting',{key:SETKEY[k],value:on});if(k==='svc')T.core.invoke('set_autostart',{on});};
  QBLIVE.langDialog=()=>pickDialog($t('settings_language_title'),LANGS.map((l,i)=>[i,i===0?$t('language_system'):l]),AS?AS.language:0,v=>T.core.invoke('set_language',{index:+v}),'scroll');
  QBLIVE.issuesDialog=function(){const list=(this.ex&&this.ex.issues)||[];
    dialog(`<h3>${esc($t('settings_issues_title'))}</h3>${list.length?`<div class="group">${list.map(i=>`<div class="row" style="align-items:flex-start;padding-block:12px">${ic('warn')}<div class="txt"><div class="t">${esc(i.title)}</div><div class="s">${esc(i.body)}</div></div></div>`).join('')}</div>`:`<div class="s" style="text-align:center;margin:0">${esc($t('issues_none'))}</div>`}<div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button></div>`);};
  // App update
  const upd={busy:false,found:false,dl:false,pct:0,latest:'–',status:'',notes:null};
  // The release notes: plain text from the release page, one block per heading or bullet. No notes: a muted line.
  const notesPaint=()=>{const el=byId('updNotes');if(!el)return;const t=(upd.notes||'').trim();
    el.classList.toggle('none',!t);
    el.innerHTML=t?t.split(/\r?\n/).map(l=>l.trim()).filter(Boolean).map(l=>{const h=l.match(/^#+\s*(.*)/),b=l.match(/^[-*]\s+(.*)/);
      return h?`<div class="nh">${esc(h[1])}</div>`:b?`<div class="nb">${esc(b[1].replace(/[*_`]/g,''))}</div>`:`<div>${esc(l.replace(/[*_`]/g,''))}</div>`;}).join(''):esc($t('update_no_notes'));};
  // The button and the progress bar share one cell: the card never changes size.
  const updPaint=()=>{setText(byId('updLatest'),upd.latest);setText(byId('updStatus'),upd.status);
    notesPaint();
    const b=byId('updBtn'),pr=byId('updProg');
    if(b){setText(b,$t(upd.found?'update_install':'update_check'));b.disabled=upd.busy;b.classList.toggle('gone',upd.dl);}
    if(pr){pr.classList.toggle('on',upd.dl);pr.firstChild.style.transform=`scaleX(${upd.pct/100})`;pr.setAttribute('aria-valuenow',upd.pct);}};
  async function updCheck(){if(upd.busy)return;upd.busy=true;upd.status=$t('update_checking');updPaint();
    const r=await T.core.invoke('update_check');upd.busy=false;
    if(r.error)upd.status=$t('update_failed').replace('%1$s',r.error);
    else{upd.latest=r.latest;upd.found=r.newer;upd.notes=r.notes||'';upd.status=r.newer?$t('update_available').replace('%1$s',r.latest):$t('update_up_to_date');}
    updPaint();}
  const dlText=()=>$t('update_downloading').replace('%1$d',Math.round(upd.pct)).replace('%%','%');
  T.event.listen('update_progress',e=>{if(!upd.dl)return;const{done,total}=e.payload;upd.pct=total?Math.min(100,done/total*100):upd.pct;upd.status=dlText();updPaint();});
  async function updInstall(){upd.busy=true;upd.dl=true;upd.pct=0;upd.status=dlText();updPaint();
    const r=await T.core.invoke('update_install');upd.busy=false;upd.dl=false;
    if(r.error)upd.status=$t('update_failed').replace('%1$s',r.error);else if(r.page)upd.status=$t('update_available').replace('%1$s',upd.latest);
    updPaint();}
  QBLIVE.updGo=()=>upd.found?updInstall():updCheck();
  QBLIVE.updOpen=()=>{updPaint();if(!upd.latest||upd.latest==='–')updCheck();};
  // Feedback
  const fbForm=()=>({model:byId('fbModel').value,description:byId('fbDesc').value,log:S.fblog!==false,
    categories:[...document.querySelectorAll('#win .qt')].map((b,i)=>b.getAttribute('aria-checked')==='true'?i:-1).filter(i=>i>=0)});
  const fbNote=t=>{const n=byId('fbNote');if(n){n.textContent=t||'';n.hidden=!t;}};
  QBLIVE.fbOpen=()=>{const m=byId('fbModel');if(m&&!m.value){const n=document.querySelector('[data-model]');m.value=n?n.textContent:'';}};
  QBLIVE.fbPreview=async()=>{const r=await T.core.invoke('report_preview',{form:fbForm()});if(r.note){fbNote(r.note);return;}fbNote('');
    dialog(`<h3>${esc($t('problem_preview'))}</h3><pre class="devlog card pad" style="max-height:300px;overflow:auto;white-space:pre-wrap;margin:0">${esc(r.text)}</pre><div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button></div>`);};
  QBLIVE.fbSend=async()=>{const b=byId('fbSend');b.disabled=true;b.textContent=$t('problem_sending');
    const r=await T.core.invoke('report_send',{form:fbForm()});b.disabled=false;b.textContent=$t('problem_send');
    if(r.ok){fbNote('');byId('fbDesc').value='';toast($t('problem_sent'));}else fbNote(r.note);};
  // Test my earbuds
  let testSig='';
  function renderTest(){const t=QBLIVE.test,box=byId('testBox');if(!box)return;
    const foot=byId('testFoot');
    const sig=JSON.stringify([t.started,t.busy,t.status,t.body&&t.body.rows.map(r=>[r.title,r.mark]),t.body&&t.body.checks,t.body&&t.body.diagnosis]);
    if(sig!==testSig){testSig=sig;
      if(!t.started)box.innerHTML=`<div class="card pad prose">${esc($t('test_intro'))}</div>`;
      else{const b=t.body;
        box.innerHTML=`<div class="cols st"><div class="col"><div class="lbl">${esc($t('test_section_check'))}</div><div class="group">${b.checks.map(c=>`<div class="row"><div class="txt"><div class="t">${esc(c.title)}</div></div><span class="v">${esc(c.sub)}</span></div>`).join('')}${b.diagnosis.length?`<div class="row"><div class="txt"><div class="t">${esc($t('test_section_diff'))}</div><div class="s">${esc(b.diagnosis.join('; '))}</div></div></div>`:''}</div></div>
          <div class="col"><div class="lbl">${esc($t('test_section_features'))}</div><div class="group">${b.rows.map((r,i)=>`<div class="row tight" style="flex-wrap:wrap"><div class="txt"><div class="t">${esc(r.title)}</div><div class="s">${esc(r.sub)}</div></div>${[[1,'test_works'],[2,'test_broken'],[3,'test_skip']].map(([m,k])=>`<button class="btn press mk" data-act="tmark" data-i="${i}" data-m="${m}" aria-pressed="${r.mark===m}">${esc($t(k))}</button>`).join('')}${r.mark===2?`<input class="field" data-note="${i}" style="flex-basis:100%" placeholder="${esc($t('test_broken_hint'))}" value="${esc(r.note)}">`:''}</div>`).join('')}</div></div></div>`;
        pixelize(box);}}
    setText(byId('testNote'),t.status||t.note||'');
    const st=byId('testStart');st.textContent=$t(t.started?'test_restart':'test_start');st.disabled=t.busy;
    byId('testPrev').hidden=!t.started;byId('testSend').hidden=!t.started;byId('testSend').textContent=$t('problem_send');byId('testSend').disabled=t.busy||!!t.note;
    byId('testPrev').textContent=$t('problem_preview');}
  T.event.listen('test',e=>{QBLIVE.test=e.payload;renderTest();});
  QBLIVE.testPreview=async()=>{const r=await T.core.invoke('test_preview');if(r.text)dialog(`<h3>${esc($t('problem_preview'))}</h3><pre class="devlog card pad" style="max-height:300px;overflow:auto;white-space:pre-wrap;margin:0">${esc(r.text)}</pre><div class="acts"><button class="btn press" data-close>${esc($t('dialog_close'))}</button></div>`);};
  document.addEventListener('change',e=>{const n=e.target.dataset&&e.target.dataset.note;if(n!=null)QBLIVE.send({t:'test_note',i:+n,text:e.target.value});});
  const takeTest=s=>{QBLIVE.test=s.test;renderTest();};
  QBLIVE.takeTest=takeTest;

  T.event.listen('snapshot',e=>take(e.payload));
  T.core.invoke('get_snapshot').then(take);
  // The Earbud settings page asks the buds for its values when it opens.
  // A page that was open when the app started, or when the buds connected, asks for its values too.
  QBLIVE.pageReads=v=>{
    if(v==='earbud'){QBLIVE.send({t:'earbud_open'});QBLIVE.send({t:'dual_open'});}
    if(v==='gestures')QBLIVE.send({t:'gestures_open'});
    if(v==='hearing')QBLIVE.send({t:'hearing_open'});
    if(v==='update')QBLIVE.updOpen();
    if(v==='feedback')QBLIVE.fbOpen();
    if(v==='test')QBLIVE.send({t:'test_open'});};
  document.addEventListener('click',e=>{const b=e.target.closest('[data-act="page"]');if(!b)return;QBLIVE.pageReads(b.dataset.v);});
  // Some buds (T300, T310) do not push a state that a gesture changed. The window reads the state again when it gets focus.
  let lastRead=0;
  window.addEventListener('focus',()=>{
    if(!snap||snap.status!=='on'||Date.now()-lastRead<3000)return;
    lastRead=Date.now();QBLIVE.send({t:'status_read'});});
}
});
})();

/* ===== native window (Tauri): own title bar buttons, drag area, rounded corners ===== */
(window.__READY||Promise.resolve()).then(()=>{
if(document.documentElement.classList.contains('native')&&window.__TAURI__){
  const w=window.__TAURI__.window.getCurrentWindow();
  if(/Windows/.test(navigator.userAgent))document.documentElement.classList.add('winos');
  const root=document.documentElement;
  if(root.classList.contains('traywin')){
    // The quick panel window: show the tray view only and size the window to it.
    document.getElementById('vTray').click();
    const tray=document.getElementById('tray');
    window.__TAURI__.core.invoke('fit_panel',{height:Math.ceil(tray.scrollHeight)});
  }else{
    document.getElementById('vWin').click();
    const bar=document.createElement('div');bar.id='dragbar';bar.setAttribute('data-tauri-drag-region','');document.getElementById('win').appendChild(bar);
    const caps=document.querySelectorAll('#win .caps .cap');
    caps[1].remove();
    caps[0].onclick=()=>w.minimize();caps[2].onclick=()=>w.close();
    window.__TAURI__.core.invoke('main_ready');
  }
}

});
/* F2 shows the real frame rate of this window. */
document.addEventListener('keydown',e=>{if(e.key!=='F2')return;
  let el=document.getElementById('fps');if(el){el.remove();return;}
  el=document.createElement('div');el.id='fps';el.style.cssText='position:fixed;right:8px;top:8px;z-index:99999;padding:4px 8px;border-radius:8px;background:#000c;color:#0f0;font:12px monospace';document.body.appendChild(el);
  let n=0,t0=performance.now(),worst=0,last=t0;
  (function tick(t){if(!document.getElementById('fps'))return;n++;worst=Math.max(worst,t-last);last=t;
    if(t-t0>=1000){el.textContent=`${Math.round(n*1000/(t-t0))} fps, worst frame ${Math.round(worst)} ms`;n=0;worst=0;t0=t;}
    requestAnimationFrame(tick);})(t0);});
