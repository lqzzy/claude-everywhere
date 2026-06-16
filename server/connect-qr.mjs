// Print a "one-tap phone connect" QR code: encode the server address + token into a claudeeverywhere://connect deep link.
// Scan it with the phone's system camera → launches the Claude Everywhere app → auto-fills the address and token and connects.
// Usage: node connect-qr.mjs <ws-url> <token>
import qrcode from "qrcode-terminal";

const [, , url, token] = process.argv;
if (!url || !token) {
  console.error("Usage: node connect-qr.mjs <ws-url> <token>");
  process.exit(1);
}

const deeplink =
  `claudeeverywhere://connect?url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`;

qrcode.generate(deeplink, { small: true });
console.log("  Can't scan it? Enter manually in the app's \"Manual connect\" screen:");
console.log("    address: " + url);
console.log("    token: " + token);
console.log("  (deep link: " + deeplink + ")\n");
