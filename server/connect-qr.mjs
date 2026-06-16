// 打印"手机一键连接"二维码:把 server 地址 + token 编码成 claudeeverywhere://connect deep link。
// 手机用系统相机扫一下 → 唤起 Claude Everywhere App → 自动填好地址和令牌并连接。
// 用法: node connect-qr.mjs <ws-url> <token>
import qrcode from "qrcode-terminal";

const [, , url, token] = process.argv;
if (!url || !token) {
  console.error("用法: node connect-qr.mjs <ws-url> <token>");
  process.exit(1);
}

const deeplink =
  `claudeeverywhere://connect?url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`;

qrcode.generate(deeplink, { small: true });
console.log("  扫不出来?在 App「手动连接」里填:");
console.log("    地址: " + url);
console.log("    令牌: " + token);
console.log("  (deep link: " + deeplink + ")\n");
