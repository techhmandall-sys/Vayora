import { createServer } from 'node:http';
import express from 'express';
import { MongoClient } from 'mongodb';
import { Server } from 'socket.io';
import { socketAuth } from './auth.js';
import { connectWithRetry } from './connect.js';
import { coreApiMembership } from './membership.js';
import { attachRealtime } from './realtime.js';
import { MongoStore } from './store.js';

const PORT = process.env.PORT ?? 3000;
const JWT_SECRET = process.env.JWT_SECRET;
const MONGO_URI = process.env.MONGO_URI ?? 'mongodb://localhost:27017/vayora';
const CORE_API_URL = process.env.CORE_API_URL ?? 'http://localhost:8080';
// Browser origins allowed to open a socket, comma-separated.
const ORIGINS = (process.env.CORS_ALLOWED_ORIGINS ?? 'http://localhost:5173').split(',');

if (!JWT_SECRET) {
  console.error('JWT_SECRET is required');
  process.exit(1);
}

const app = express();
app.get('/health', (_req, res) => {
  res.json({ status: 'UP', service: 'gateway' });
});

const httpServer = createServer(app);
const io = new Server(httpServer, { cors: { origin: ORIGINS } });
io.use(socketAuth(JWT_SECRET));

const mongo = new MongoClient(MONGO_URI, { serverSelectionTimeoutMS: 2000 });
const store = new MongoStore(mongo.db());
// Not awaited: the gateway serves /health and accepts sockets while MongoDB comes up, and
// a store call made too early fails as a server_error to that one client. It keeps trying:
// a first attempt that fails must not leave the gateway without MongoDB for good.
void connectWithRetry(mongo, {
  onConnected: async () => {
    await store.ensureIndexes();
    console.log('connected to MongoDB');
  },
});

attachRealtime(io, { membership: coreApiMembership(CORE_API_URL), store });

httpServer.listen(PORT, () => {
  console.log(`gateway listening on ${PORT}`);
});
