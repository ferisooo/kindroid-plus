// Kindroid+ selfie slideshow: the Kin's selfie photos take turns as the chat background.
//
// The app runs this on Kindroid's pages wrapped as (function (KP_BRIDGE) { ... })('<name>'),
// where KP_BRIDGE names the app's bridge: settings() gives {"on", "order", "seconds"} as JSON,
// report(json) tells the app what was found (or what went wrong) for its settings pop-up.
//
// How Kindroid works (read from its public web code, not guessed):
// - A Kin's chat is /v2/chat/<kin id> (group chats: /v2/chat/group/<id>, left alone).
// - Its background is a div with an inline background-image inside
//   <div class="..._backdrop" aria-hidden="true">. The class names are made up per build,
//   so it's found by its shape. Our pictures go in a layer of our own inside that backdrop,
//   above Kindroid's, so they sit behind the chat like the real background.
// - Selfies are in Firestore at Users/<uid>/AIs/<kin id>/Selfies (newest by "timestamp").
//   They're read with the login Kindroid's own page keeps (firebaseLocalStorageDb), with
//   Firestore's REST API, read only. Nothing in the account is changed.
// - A selfie's url can start with "!enc:": CryptoJS AES with the user's uid as the
//   passphrase (OpenSSL "Salted__", key from MD5), opened here with the browser's own AES.
(function () {
  if (window.__kpSlides) return;
  window.__kpSlides = true;

  var bridge = window[KP_BRIDGE];
  var TICK_MS = 1000;
  var REFRESH_MS = 10 * 60 * 1000;  // read the selfies again (new ones show up)
  var RETRY_MS = 30 * 1000;         // after a failed read
  var PAGE = 100;                   // selfies per request
  var MOST = 1000;                  // most selfies kept per Kin
  var FADE_MS = 1200;
  var FIELDS = ['url', 'thumbnail_url', 'timestamp', 'status', 'deleted', 'output_moderation_status',
    'blocked_for_native_policy', 'native_sfw_policy'];

  var lists = {};  // "<kin>|asc" or "<kin>|desc" -> {urls, at, loading}
  var state = {cfg: '', kin: null, pos: -1, bag: [], shown: null, nextAt: 0, busy: false, failed: 0};
  var box = null, layers = [], front = 0;

  function config() {
    try { return JSON.parse(bridge.settings()); } catch (e) { return {on: false}; }
  }

  function report(o) {
    try { bridge.report(JSON.stringify(o)); } catch (e) {}
  }

  /** The Kin whose chat is open, or null on any other page. */
  function kinId() {
    var m = location.pathname.match(/^\/v2\/chat\/([^\/]+)\/?$/);
    return m && m[1] !== 'group' ? decodeURIComponent(m[1]) : null;
  }

  function direction(cfg) {
    return cfg.order === 'oldest' ? 'asc' : 'desc';
  }

  // ---- The login Kindroid's page keeps ----

  function fromAuthRow(value) {
    if (!value || !value.uid || !value.stsTokenManager) return null;
    return {uid: value.uid, token: value.stsTokenManager.accessToken, expires: value.stsTokenManager.expirationTime};
  }

  function readAuth() {
    return new Promise(function (resolve) {
      var done = function (v) { resolve(v || fromLocalStorage()); };
      var req;
      try { req = indexedDB.open('firebaseLocalStorageDb'); } catch (e) { done(null); return; }
      // No such database yet: don't make an empty one (Kindroid's own login would then break).
      req.onupgradeneeded = function () { req.transaction.abort(); };
      req.onerror = function () { done(null); };
      req.onsuccess = function () {
        var db = req.result;
        try {
          var all = db.transaction('firebaseLocalStorage', 'readonly').objectStore('firebaseLocalStorage').getAll();
          all.onsuccess = function () {
            var found = null;
            (all.result || []).forEach(function (row) {
              if (row && String(row.fbase_key || '').indexOf('firebase:authUser:') === 0) found = fromAuthRow(row.value) || found;
            });
            db.close();
            done(found);
          };
          all.onerror = function () { db.close(); done(null); };
        } catch (e) {
          db.close();
          done(null);
        }
      };
    });
  }

  function fromLocalStorage() {
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf('firebase:authUser:') === 0) {
          var a = fromAuthRow(JSON.parse(localStorage.getItem(k)));
          if (a) return a;
        }
      }
    } catch (e) {}
    return null;
  }

  // ---- Opening "!enc:" addresses (CryptoJS's AES with a passphrase) ----

  var S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
  var K = [];
  for (var ki = 0; ki < 64; ki++) K[ki] = Math.floor(Math.abs(Math.sin(ki + 1)) * 4294967296) | 0;

  function md5(bytes) {
    var n = bytes.length, blocks = ((n + 8) >>> 6) + 1, w = new Int32Array(blocks * 16), i;
    for (i = 0; i < n; i++) w[i >> 2] |= bytes[i] << ((i % 4) * 8);
    w[n >> 2] |= 0x80 << ((n % 4) * 8);
    w[blocks * 16 - 2] = n * 8;
    w[blocks * 16 - 1] = Math.floor(n / 536870912);
    var a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
    for (var off = 0; off < w.length; off += 16) {
      var A = a0, B = b0, C = c0, D = d0;
      for (i = 0; i < 64; i++) {
        var F, g;
        if (i < 16) { F = (B & C) | (~B & D); g = i; }
        else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
        else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * i) % 16; }
        var t = D; D = C; C = B;
        var x = (A + F + K[i] + w[off + g]) | 0;
        B = (B + ((x << S[i]) | (x >>> (32 - S[i])))) | 0;
        A = t;
      }
      a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
    }
    var out = new Uint8Array(16), st = [a0, b0, c0, d0];
    for (i = 0; i < 16; i++) out[i] = (st[i >> 2] >>> ((i % 4) * 8)) & 255;
    return out;
  }

  /** OpenSSL's EVP_BytesToKey with MD5, one round: 32 key bytes and 16 IV bytes. */
  function keyAndIv(pass, salt) {
    var out = new Uint8Array(48), have = 0, prev = new Uint8Array(0);
    while (have < 48) {
      var buf = new Uint8Array(prev.length + pass.length + salt.length);
      buf.set(prev);
      buf.set(pass, prev.length);
      buf.set(salt, prev.length + pass.length);
      prev = md5(buf);
      out.set(prev.subarray(0, Math.min(16, 48 - have)), have);
      have += 16;
    }
    return out;
  }

  function openEnc(value, uid) {
    try {
      var bin = atob(value.replace(/\s+/g, ''));
      var raw = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) raw[i] = bin.charCodeAt(i);
      if (raw.length < 32 || bin.slice(0, 8) !== 'Salted__') return Promise.resolve('');
      var kv = keyAndIv(new TextEncoder().encode(uid), raw.subarray(8, 16));
      return crypto.subtle.importKey('raw', kv.subarray(0, 32), {name: 'AES-CBC'}, false, ['decrypt'])
        .then(function (key) { return crypto.subtle.decrypt({name: 'AES-CBC', iv: kv.subarray(32, 48)}, key, raw.subarray(16)); })
        .then(function (plain) { return new TextDecoder().decode(plain); })
        .catch(function () { return ''; });
    } catch (e) {
      return Promise.resolve('');
    }
  }

  // ---- Reading the selfies ----

  function value(f) {
    if (!f) return undefined;
    if ('stringValue' in f) return f.stringValue;
    if ('booleanValue' in f) return f.booleanValue;
    if ('integerValue' in f) return Number(f.integerValue);
    if ('doubleValue' in f) return f.doubleValue;
    if ('timestampValue' in f) return Date.parse(f.timestampValue);
    return undefined;
  }

  /** Kindroid's own rule for selfies its gallery hides, plus ones still being made. */
  function shows(x) {
    var status = String(x.status == null ? '' : x.status).toLowerCase();
    var checked = String(x.output_moderation_status || '').toLowerCase();
    var blocked = !!x.blocked_for_native_policy || checked === 'blocked';
    if (x.deleted) return false;
    if ((status === 'error' || status === 'failed') && !blocked) return false;
    if (/pend|progress|queue|generat|process|wait|running|start/.test(status)) return false;
    if (x.native_sfw_policy && status === 'completed' && ['passed', 'error', 'blocked'].indexOf(checked) < 0) return false;
    return true;
  }

  function fetchSelfies(auth, kin, dir) {
    var url = 'https://firestore.googleapis.com/v1/projects/kindroid-ai/databases/(default)/documents/Users/' +
      encodeURIComponent(auth.uid) + '/AIs/' + encodeURIComponent(kin) + ':runQuery';
    var docs = [];
    function page(after) {
      var q = {
        from: [{collectionId: 'Selfies'}],
        select: {fields: FIELDS.map(function (f) { return {fieldPath: f}; })},
        orderBy: [{field: {fieldPath: 'timestamp'}, direction: dir === 'asc' ? 'ASCENDING' : 'DESCENDING'}],
        limit: PAGE
      };
      if (after) q.startAt = {values: [after], before: false};
      return fetch(url, {
        method: 'POST',
        headers: {'Authorization': 'Bearer ' + auth.token, 'Content-Type': 'application/json'},
        body: JSON.stringify({structuredQuery: q})
      }).then(function (r) {
        if (!r.ok) throw new Error('Kindroid said no (' + r.status + ')');
        return r.json();
      }).then(function (rows) {
        var got = (rows || []).filter(function (r) { return r && r.document; }).map(function (r) { return r.document; });
        docs = docs.concat(got);
        var last = got.length && got[got.length - 1].fields && got[got.length - 1].fields.timestamp;
        return got.length < PAGE || docs.length >= MOST || !last ? docs : page(last);
      });
    }
    return page(null);
  }

  function toUrls(docs, uid) {
    return Promise.all(docs.map(function (d) {
      var x = {};
      FIELDS.forEach(function (f) { x[f] = value(d.fields && d.fields[f]); });
      if (!shows(x)) return '';
      var u = typeof x.url === 'string' && x.url ? x.url : x.thumbnail_url;
      if (typeof u !== 'string' || !u) return '';
      return (u.indexOf('!enc:') === 0 ? openEnc(u.substring(5), uid) : Promise.resolve(u)).then(function (open) {
        open = String(open || '').replace(/^https:\/\/storage2\.kindroid\.ai\//, 'https://storage.kindroid.ai/');
        return /^https:\/\//.test(open) ? open : '';
      });
    })).then(function (urls) {
      var seen = {};
      return urls.filter(function (u) { if (!u || seen[u]) return false; seen[u] = 1; return true; });
    });
  }

  function load(kin, cfg) {
    var dir = direction(cfg), key = kin + '|' + dir;
    var list = lists[key] || (lists[key] = {urls: [], at: 0, loading: false});
    if (list.loading) return;
    list.loading = true;
    readAuth().then(function (auth) {
      if (!auth || !auth.token) throw new Error('Not logged in to Kindroid');
      if (auth.expires && auth.expires < Date.now() + 30000) throw new Error('Waiting for Kindroid to renew the login');
      return fetchSelfies(auth, kin, dir).then(function (docs) { return toUrls(docs, auth.uid); });
    }).then(function (urls) {
      list.urls = urls;
      list.at = Date.now();
      list.loading = false;
      report({kin: kin, count: urls.length, problem: urls.length ? '' : 'This Kin has no selfies yet'});
    }, function (e) {
      list.loading = false;
      list.at = Date.now() - REFRESH_MS + RETRY_MS;
      report({kin: kin, count: list.urls.length, problem: String((e && e.message) || e)});
    });
  }

  // ---- Showing them ----

  function findHost() {
    var spots = document.querySelectorAll('[aria-hidden="true"][class*="_backdrop"]');
    for (var i = 0; i < spots.length; i++) if (spots[i].offsetWidth > 0 && spots[i].offsetHeight > 0) return spots[i];
    // No background chosen: Kindroid's "ambience" layer, if it covers the chat.
    var amb = document.querySelectorAll('[class*="mbience"]');
    for (var j = 0; j < amb.length; j++) {
      var p = getComputedStyle(amb[j]).position;
      if ((p === 'absolute' || p === 'fixed') && amb[j].offsetWidth >= innerWidth * 0.8 && amb[j].offsetHeight >= innerHeight * 0.5) return amb[j];
    }
    return null;
  }

  function css(url) {
    return 'url("' + url.replace(/"/g, '%22') + '")';
  }

  function drop() {
    if (box && box.parentNode) box.parentNode.removeChild(box);
    box = null;
    layers = [];
  }

  function ensureLayers() {
    var host = findHost();
    if (!host) { drop(); return null; }
    if (box && box.parentNode === host) return box;
    drop();
    box = document.createElement('div');
    box.setAttribute('data-kp-slides', '1');
    box.setAttribute('aria-hidden', 'true');
    box.style.cssText = 'position:absolute;inset:0;z-index:1;pointer-events:none;';
    for (var i = 0; i < 2; i++) {
      var d = document.createElement('div');
      d.style.cssText = 'position:absolute;inset:0;background-size:cover;background-position:50% 30%;' +
        'background-repeat:no-repeat;opacity:0;transition:opacity ' + FADE_MS + 'ms ease;';
      box.appendChild(d);
      layers.push(d);
    }
    front = 0;
    host.appendChild(box);
    // Kindroid drew its page again and took ours away: put the last picture straight back.
    if (state.shown) {
      layers[0].style.transition = 'none';
      layers[0].style.backgroundImage = css(state.shown);
      layers[0].style.opacity = '1';
      void layers[0].offsetWidth;
      layers[0].style.transition = 'opacity ' + FADE_MS + 'ms ease';
    }
    return box;
  }

  /** As see-through and as blurred as the background chosen in Kindroid. */
  function matchKindroid() {
    var theirs = null, kids = box.parentNode.children;
    for (var i = 0; i < kids.length; i++) if (kids[i] !== box && kids[i].style && kids[i].style.backgroundImage) theirs = kids[i];
    var cs = theirs ? getComputedStyle(theirs) : null;
    box.style.opacity = cs ? cs.opacity : '1';
    box.style.filter = cs && cs.filter && cs.filter !== 'none' ? cs.filter : '';
  }

  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1)), t = a[i];
      a[i] = a[j];
      a[j] = t;
    }
    return a;
  }

  function nextUrl(urls, cfg) {
    if (cfg.order === 'random') {
      if (!state.bag.length) {
        state.bag = shuffle(urls.slice());
        if (state.bag.length > 1 && state.bag[0] === state.shown) state.bag.push(state.bag.shift());
      }
      return state.bag.shift();
    }
    state.pos = (state.pos + 1) % urls.length;
    return urls[state.pos];
  }

  function show(url, cfg, kin) {
    state.busy = true;
    var img = new Image();
    img.onload = function () {
      state.busy = false;
      state.failed = 0;
      if (state.kin !== kin || !ensureLayers()) return;
      var incoming = layers[1 - front], old = layers[front];
      incoming.style.zIndex = '2';
      old.style.zIndex = '1';
      incoming.style.backgroundImage = css(url);
      incoming.style.opacity = '1';
      front = 1 - front;
      // The old one goes once the new one covers it, so Kindroid's own never peeks through.
      setTimeout(function () { if (layers[front] !== old) old.style.opacity = '0'; }, FADE_MS);
      state.shown = url;
      state.nextAt = Date.now() + Math.max(2, Number(cfg.seconds) || 10) * 1000;
    };
    img.onerror = function () {
      state.busy = false;
      state.failed++;
      state.nextAt = Date.now() + (state.failed > 20 ? 30000 : 0);
      if (state.failed === 20) report({kin: kin, count: 0, problem: "The selfie pictures didn't load"});
    };
    img.src = url;
  }

  function restart() {
    state.pos = -1;
    state.bag = [];
    state.nextAt = 0;
    state.failed = 0;
  }

  function tick() {
    if (document.visibilityState === 'hidden') return;
    var cfg = config(), key = JSON.stringify(cfg);
    if (key !== state.cfg) {
      state.cfg = key;
      restart();
    }
    var kin = cfg.on ? kinId() : null;
    if (kin !== state.kin) {
      state.kin = kin;
      state.shown = null;
      drop();
      restart();
    }
    if (!kin) return;
    var list = lists[kin + '|' + direction(cfg)];
    if (!list || (!list.loading && Date.now() - list.at > REFRESH_MS)) load(kin, cfg);
    if (!list || !list.urls.length) return;
    if (!ensureLayers()) return;
    matchKindroid();
    if (!state.busy && Date.now() >= state.nextAt) {
      var url = nextUrl(list.urls, cfg);
      if (url) show(url, cfg, kin);
    }
  }

  window.__kpSlidesTick = tick;
  window.__kpSlidesTest = {md5: md5, openEnc: openEnc, shows: shows};
  setInterval(tick, TICK_MS);
  tick();
})();
