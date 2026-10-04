// Uses the independent standard Web Push sender. Never embed production private keys.
import webpush from 'web-push';
import { createECDH, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
const [command, ...args] = process.argv.slice(2);
if (command === 'keys') {
  if (!args[0]) throw new Error('Usage: node interop.mjs keys /private/path/keys.json');
  const keys = webpush.generateVAPIDKeys();
  writeFileSync(args[0], JSON.stringify(keys), { mode: 0o600, flag: 'wx' });
  console.log(`Approve this public key in LelloStore: ${keys.publicKey}`);
} else if (command === 'send') {
  if (args.length !== 3) throw new Error('Usage: node interop.mjs send keys.json subscription.json "Message"');
  const keys = JSON.parse(readFileSync(args[0]));
  const subscription = JSON.parse(readFileSync(args[1]));
  const result = await webpush.sendNotification(subscription, args[2], {
    vapidDetails: { subject: 'mailto:push-test@example.com', ...keys },
    TTL: 300, urgency: 'high', contentEncoding: 'aes128gcm',
  });
  console.log(`Accepted: HTTP ${result.statusCode}`);
} else if (command === 'vector') {
  const keys = webpush.generateVAPIDKeys();
  const recipient = createECDH('prime256v1'); recipient.generateKeys();
  const auth = randomBytes(16).toString('base64url');
  const subscription = { endpoint: 'https://push.example/api/push/v1/send/' + 'b'.repeat(64), keys: { p256dh: recipient.getPublicKey().toString('base64url'), auth } };
  const plaintext = 'LelloStore UnifiedPush interoperability ✓';
  const request = webpush.generateRequestDetails(subscription, plaintext, { vapidDetails: { subject: 'mailto:test@example.com', ...keys }, TTL: 300, urgency: 'high', contentEncoding: 'aes128gcm' });
  // Test-only recipient private material enables the official connector's decryptor test.
  const vector = { generated_at: Math.floor(Date.now()/1000), vapid: keys.publicKey, authorization: request.headers.Authorization, headers: request.headers, body: request.body.toString('base64'), subscription, recipient_private: recipient.getPrivateKey().toString('base64url'), plaintext };
  process.stdout.write(JSON.stringify(vector, null, 2) + '\n');
} else { throw new Error('Commands: keys, send, vector'); }
