const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// Exercise the real routes without opening sockets or reading .generated files.
function mockServer(protocol) {
  const handlers = {};
  const createServer = (name, handler) => {
    handlers[name] = handler;
    return { listen() {} };
  };
  const modules = {
    fs: {
      existsSync: (file) => !file.endsWith('mock-config.json'),
      readFileSync: () => 'unused TLS fixture',
    },
    http: { createServer: (handler) => createServer('http', handler) },
    https: { createServer: (_, handler) => createServer('https', handler) },
    path,
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), {
    __dirname,
    URL,
    console: { log() {} },
    require(name) {
      assert.ok(Object.hasOwn(modules, name), `Unexpected module: ${name}`);
      return modules[name];
    },
  });
  return async (method, url, payload = '') => {
    const req = Object.assign(new EventEmitter(), { method, url });
    const response = {};
    const handled = handlers[protocol](req, {
      writeHead(status, headers) { Object.assign(response, { status, headers }); },
      end(body) { response.body = JSON.parse(body); },
    });
    req.emit('data', payload);
    req.emit('end');
    await handled;
    return response;
  };
}

for (const protocol of ['http', 'https']) {
  test(`${protocol}: supported display scales update subsequent reads`, async () => {
    const request = mockServer(protocol);
    let previous = 100;
    for (const scale of [100, 125, 150, 100]) {
      const response = await request('POST', '/display-scale', JSON.stringify({
        device_id: 'MOCK-DISPLAY-1', scale_percent: scale,
      }));
      assert.equal(response.status, 200);
      assert.equal(response.body.success, true);
      assert.equal(response.body.previous_scale_percent, previous);
      assert.equal(response.body.current_scale_percent, scale);
      const options = await request('GET', '/display-scale-options');
      assert.equal(options.body.current_scale_percent, scale);
      previous = scale;
    }
  });

  test(`${protocol}: non-object and malformed JSON are rejected without mutation`, async () => {
    const request = mockServer(protocol);
    const before = (await request('GET', '/displays')).body;
    for (const payload of [
      'null', '[]', '[{"scale_percent":125}]', '125', '"125"', 'true', 'false',
      '{', '{"scale_percent":', '',
    ]) {
      const response = await request('POST', '/display-scale', payload);
      assert.equal(response.status, 400, payload);
      assert.equal(response.body.status_code, 400, payload);
      assert.equal(response.body.success, false, payload);
      assert.equal(response.body.error_code, 'invalid_request', payload);
      assert.equal(response.headers['Content-Type'], 'application/json');
      assert.deepEqual((await request('GET', '/displays')).body, before, payload);
    }
  });

  test(`${protocol}: unsupported scale retains its response and display state`, async () => {
    const request = mockServer(protocol);
    const before = (await request('GET', '/displays')).body;
    const response = await request('POST', '/display-scale', '{"scale_percent":175}');
    assert.equal(response.status, 400);
    assert.equal(response.body.error_code, 'unsupported_scale');
    assert.equal(response.body.success, false);
    assert.equal(response.body.current_scale_percent, 100);
    assert.deepEqual(response.body.supported_scale_percents, [100, 125, 150]);
    assert.deepEqual((await request('GET', '/displays')).body, before);
  });
}
