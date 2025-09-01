// Bridge WebSocket <-> TCP (Mafia Server)
// Chạy: BACKEND_HOST=127.0.0.1 BACKEND_PORT=12345 PORT=8080 node index.js

const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const net = require('net');

const BACKEND_HOST = process.env.BACKEND_HOST || '127.0.0.1';
const BACKEND_PORT = parseInt(process.env.BACKEND_PORT || '12345', 10);
const PORT = parseInt(process.env.PORT || '8080', 10);

const app = express();
app.use(express.static('public'));

const server = http.createServer(app);
const wss = new WebSocket.Server({ server, path: '/ws' });

wss.on('connection', (ws, req) => {
  console.log('[Bridge] WS connected from', req.socket.remoteAddress);

  const tcp = net.connect(BACKEND_PORT, BACKEND_HOST, () => {
    console.log('[Bridge] TCP connected ->', BACKEND_HOST + ':' + BACKEND_PORT);
  });
  tcp.setNoDelay(true);

  ws.on('message', (data) => {
    let msg = data.toString();
    if (!msg.endsWith('\n')) msg += '\n';
    tcp.write(msg, 'utf8');
  });

  tcp.on('data', (chunk) => ws.send(chunk.toString('utf8')));
  tcp.on('close', () => ws.close());
  tcp.on('error', (err) => { try { ws.send('[Bridge] TCP error: ' + err.message); } catch(_) {} ws.close(); });

  ws.on('close', () => { try { tcp.end(); } catch(_) {} });
});

server.listen(PORT, () => {
  console.log(`[Bridge] http://localhost:${PORT}  (WS /ws) → TCP ${BACKEND_HOST}:${BACKEND_PORT}`);
});
