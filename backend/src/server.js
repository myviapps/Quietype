import http from 'node:http';
import { handle } from './app.js';

// Local dev server. On Vercel the same handler runs as a function (see api/index.js).
const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';

http.createServer(handle).listen(PORT, HOST, () => {
  console.log(`[quietype] backend listening on http://${HOST}:${PORT}`);
});
