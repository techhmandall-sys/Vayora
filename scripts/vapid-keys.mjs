// Makes a key pair for push notifications (RFC 8292). Prints the two values core-api reads:
//
//   node scripts/vapid-keys.mjs
//
// Put them in the environment as VAYORA_PUSH_PUBLIC_KEY and VAYORA_PUSH_PRIVATE_KEY. Keep
// the private key out of the repository. A new pair stops every existing subscription
// working: browsers subscribed with the old public key must subscribe again.
import { generateKeyPairSync } from 'node:crypto';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const jwk = (key) => key.export({ format: 'jwk' });
const pub = jwk(publicKey);
const raw = Buffer.concat([Buffer.from([4]), Buffer.from(pub.x, 'base64url'), Buffer.from(pub.y, 'base64url')]);

console.log(`VAYORA_PUSH_PUBLIC_KEY=${raw.toString('base64url')}`);
console.log(`VAYORA_PUSH_PRIVATE_KEY=${jwk(privateKey).d}`);
