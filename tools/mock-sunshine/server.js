const fs = require('fs');
const http = require('http');
const https = require('https');
const path = require('path');

const generatedDir = path.join(__dirname, '.generated');
const configPath = path.join(generatedDir, 'mock-config.json');

const defaults = {
  hostName: 'Mock Paired PC',
  uniqueId: 'MOCK-PC-PAIRING-UUID',
  hostAddressForClient: '10.0.2.2',
  httpPort: 48089,
  httpsPort: 48084,
  pairStatus: 0,
  httpsServerInfoStatus: 401,
  currentGame: 0,
  appVersion: '99.99.99',
  gfeVersion: '99.99.99',
  appTitles: ['Mock Desktop', 'Mock Steam'],
};

const config = {
  ...defaults,
  ...(fs.existsSync(configPath) ? readConfig(configPath) : {}),
};

const serverKeyPath = path.join(generatedDir, 'mock-server-key.pem');
const serverCertPath = path.join(generatedDir, 'mock-server-cert.pem');

if (!fs.existsSync(serverKeyPath) || !fs.existsSync(serverCertPath)) {
  throw new Error(`Missing mock TLS certificate. Run Start-MockSunshine.ps1 first.`);
}

function readConfig(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8');
  const jsonStart = raw.indexOf('{');
  if (jsonStart < 0) {
    throw new Error(`Invalid config file: ${filePath}`);
  }
  return JSON.parse(raw.slice(jsonStart));
}

function xmlEscape(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

function serverInfoXml() {
  const host = xmlEscape(config.hostName);
  const uuid = xmlEscape(config.uniqueId);
  const address = xmlEscape(config.hostAddressForClient);

  return `<?xml version="1.0" encoding="utf-8"?><root status_code="200"><hostname>${host}</hostname><uniqueid>${uuid}</uniqueid><HttpsPort>${config.httpsPort}</HttpsPort><ExternalPort>${config.httpPort}</ExternalPort><LocalIP>${address}</LocalIP><ExternalIP>${address}</ExternalIP><mac>00:11:22:33:44:55</mac><PairStatus>${config.pairStatus}</PairStatus><state>MJOLNIR_SERVER_AVAILABLE</state><currentgame>${config.currentGame}</currentgame><appversion>${xmlEscape(config.appVersion)}</appversion><GfeVersion>${xmlEscape(config.gfeVersion)}</GfeVersion><DesktopSpecialAppSupport>1</DesktopSpecialAppSupport></root>`;
}

function appListXml() {
  const titles = Array.isArray(config.appTitles) && config.appTitles.length > 0
    ? config.appTitles
    : defaults.appTitles;
  const apps = titles.map((title, index) => {
    return `<App><AppTitle>${xmlEscape(title)}</AppTitle><ID>${index + 1}</ID><IsHdrSupported>0</IsHdrSupported><SuperCmds>null</SuperCmds></App>`;
  }).join('');

  return `<?xml version="1.0" encoding="utf-8"?><root status_code="200">${apps}</root>`;
}

function respond(res, statusCode, body, contentType = 'text/xml') {
  res.writeHead(statusCode, { 'Content-Type': contentType });
  res.end(body);
}

function route(protocol, req, res) {
  console.log(`[${protocol}] ${req.url}`);

  if (req.url.startsWith('/serverinfo')) {
    if (protocol === 'https' && config.httpsServerInfoStatus !== 200) {
      return respond(res, config.httpsServerInfoStatus, 'not paired', 'text/plain');
    }
    return respond(res, 200, serverInfoXml());
  }

  if (req.url.startsWith('/applist')) {
    return respond(res, 200, appListXml());
  }

  return respond(res, 404, 'not found', 'text/plain');
}

const httpServer = http.createServer((req, res) => route('http', req, res));
const httpsServer = https.createServer({
  key: fs.readFileSync(serverKeyPath),
  cert: fs.readFileSync(serverCertPath),
}, (req, res) => route('https', req, res));

httpServer.listen(config.httpPort, '0.0.0.0', () => {
  console.log(`mock Sunshine HTTP listening on ${config.httpPort}`);
});

httpsServer.listen(config.httpsPort, '0.0.0.0', () => {
  console.log(`mock Sunshine HTTPS listening on ${config.httpsPort}`);
});
