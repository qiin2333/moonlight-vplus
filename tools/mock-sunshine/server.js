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
  displays: [{
    display_name: '\\\\.\\DISPLAY1',
    device_id: 'MOCK-DISPLAY-1',
    friendly_name: 'Mock display',
    is_primary: true,
    current_scale_percent: 100,
    recommended_scale_percent: 125,
    supported_scale_percents: [100, 125, 150],
    scale_set_supported: true,
  }],
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

function displayScaleInfo(display) {
  return {
    display_name: display.display_name,
    device_id: display.device_id,
    friendly_name: display.friendly_name,
    is_primary: Boolean(display.is_primary),
    current_scale_percent: display.current_scale_percent ?? null,
    recommended_scale_percent: display.recommended_scale_percent ?? null,
    supported_scale_percents: Array.isArray(display.supported_scale_percents)
      ? display.supported_scale_percents
      : [],
    scale_set_supported: Boolean(display.scale_set_supported),
  };
}

function displayListResponse() {
  const displays = Array.isArray(config.displays) ? config.displays : [];
  return {
    status_code: 200,
    status_message: 'OK',
    displays: displays.map((display, index) => ({ index, ...displayScaleInfo(display) })),
    count: displays.length,
  };
}

function findDisplay(url) {
  const query = new URL(url, 'https://mock-sunshine.invalid').searchParams;
  const displayName = query.get('display_name');
  const deviceId = query.get('device_id');
  const displays = Array.isArray(config.displays) ? config.displays : [];
  const match = displays.find((display) =>
    (deviceId && display.device_id === deviceId) ||
    (displayName && display.display_name === displayName) ||
    (displayName && display.friendly_name === displayName)
  );
  if (match) return match;
  if (deviceId || displayName) return undefined;
  return displays.find((display) => display.is_primary) || displays[0];
}

function displayScaleOptions(url) {
  const display = findDisplay(url);
  if (!display) {
    return { status_code: 404, status_message: 'Display not found', success: false, error_code: 'display_not_found' };
  }
  return { status_code: 200, status_message: 'OK', success: true, ...displayScaleInfo(display) };
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try { resolve(JSON.parse(body || '{}')); } catch (error) { reject(error); }
    });
    req.on('error', reject);
  });
}

async function setDisplayScale(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
    if (body === null || typeof body !== 'object' || Array.isArray(body)) {
      throw new TypeError('Expected a JSON object');
    }
  } catch (_) {
    return respond(res, 400, JSON.stringify({
      status_code: 400,
      status_message: 'Invalid JSON body',
      success: false,
      error_code: 'invalid_request',
    }), 'application/json');
  }
  const display = findDisplay(`https://mock-sunshine.invalid/?display_name=${encodeURIComponent(body.display_name || '')}&device_id=${encodeURIComponent(body.device_id || '')}`);
  const requested = Number(body.scale_percent);
  if (!display || !Number.isInteger(requested)) {
    return respond(res, 400, JSON.stringify({
      status_code: 400,
      status_message: 'Missing or invalid scale_percent',
      success: false,
      error_code: 'invalid_request',
    }), 'application/json');
  }
  const supported = Array.isArray(display.supported_scale_percents) ? display.supported_scale_percents : [];
  if (!display.scale_set_supported || !supported.includes(requested)) {
    return respond(res, 400, JSON.stringify({
      status_code: 400,
      status_message: 'Unsupported display scale',
      success: false,
      error_code: 'unsupported_scale',
      ...displayScaleInfo(display),
    }), 'application/json');
  }
  const previous = display.current_scale_percent ?? null;
  display.current_scale_percent = requested;
  return respond(res, 200, JSON.stringify({
    status_code: 200,
    status_message: 'OK',
    success: true,
    display_name: display.display_name,
    device_id: display.device_id,
    previous_scale_percent: previous,
    current_scale_percent: requested,
    supported_scale_percents: supported,
    restart_required: false,
    effective_immediately: true,
  }), 'application/json');
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

  if (req.url.startsWith('/displays')) {
    return respond(res, 200, JSON.stringify(displayListResponse()), 'application/json');
  }

  if (req.url.startsWith('/display-scale-options')) {
    const response = displayScaleOptions(req.url);
    return respond(res, response.status_code, JSON.stringify(response), 'application/json');
  }

  if (req.url.startsWith('/display-scale') && req.method === 'POST') {
    return setDisplayScale(req, res);
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
