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
//   Firestore's REST API, read only. Nothing in the account is changed. Kindroid uses
//   App Check (reCAPTCHA Enterprise), so the page's own App Check token goes along
//   (firebase-app-check-database); without it Firestore answers 403.
// - A selfie's url can start with "!enc:": CryptoJS AES with the user's uid as the
//   passphrase (OpenSSL "Salted__", key from MD5), opened here with the browser's own AES.
(function () {
  if (window.__kpSlides) return;
  window.__kpSlides = true;

  var bridge = window[KP_BRIDGE];
  var TICK_MS = 1000;
  var REFRESH_MS = 10 * 60 * 1000;  // read the selfies again (new ones show up)
  var RETRY_MS = 30 * 1000;         // while the login is being renewed
  var FAILED_WAIT_MS = 5 * 60 * 1000; // after Kindroid turned the read down
  var PAGE = 100;                   // selfies per request
  var MOST = 1000;                  // most selfies kept per Kin
  var FADE_MS = 1200;
  var FIELDS = ['url', 'thumbnail_url', 'timestamp', 'status', 'deleted', 'output_moderation_status',
    'blocked_for_native_policy', 'native_sfw_policy'];

  var lists = {};  // "<kin>|asc" or "<kin>|desc" -> {urls, at, loading}
  var state = {cfg: '', kin: null, pos: -1, bag: [], shown: null, nextAt: 0, busy: false, failed: 0, noPass: 0};
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

  /**
   * Every row of every store in one of the page's own databases, as {found, rows}
   * (found is false when there's no such database).
   */
  function readAll(name) {
    return new Promise(function (resolve) {
      var req, rows = [];
      try { req = indexedDB.open(name); } catch (e) { resolve({found: false, rows: rows}); return; }
      // No such database yet: don't make an empty one (Kindroid's own code would then break).
      req.onupgradeneeded = function () { req.transaction.abort(); };
      req.onerror = function () { resolve({found: false, rows: rows}); };
      req.onsuccess = function () {
        var db = req.result, names = Array.prototype.slice.call(db.objectStoreNames), left = names.length;
        var finish = function () { if (--left <= 0) { db.close(); resolve({found: true, rows: rows}); } };
        if (!left) { db.close(); resolve({found: true, rows: rows}); return; }
        try {
          var tx = db.transaction(names, 'readonly');
          names.forEach(function (n) {
            var all = tx.objectStore(n).getAll();
            all.onsuccess = function () { rows = rows.concat(all.result || []); finish(); };
            all.onerror = finish;
          });
        } catch (e) {
          db.close();
          resolve({found: true, rows: rows});
        }
      };
    });
  }

  /** Kindroid can keep the login in IndexedDB, localStorage or both: the fresher one wins. */
  function readAuth() {
    return readAll('firebaseLocalStorageDb').then(function (db) {
      var rows = db.rows;
      var found = fromLocalStorage();
      rows.forEach(function (row) {
        var a = row && String(row.fbase_key || '').indexOf('firebase:authUser:') === 0 ? fromAuthRow(row.value) : null;
        if (a && (!found || (a.expires || 0) > (found.expires || 0))) found = a;
      });
      return found;
    });
  }

  /**
   * The page's own App Check pass, as {used, token}: used is whether the page keeps App
   * Check passes at all, token is a pass that's still good (or null).
   */
  function readAppCheck() {
    return readAll('firebase-app-check-database').then(function (db) {
      var best = null;
      db.rows.forEach(function (row) {
        var v = row && (row.value && typeof row.value === 'object' ? row.value : row);
        if (v && typeof v.token === 'string' && (v.expireTimeMillis || 0) > Date.now() + 10000 &&
            (!best || v.expireTimeMillis > best.expireTimeMillis)) best = v;
      });
      return {used: db.found, token: best ? best.token : null};
    });
  }

  function fromLocalStorage() {
    var found = null;
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf('firebase:authUser:') === 0) {
          var a = fromAuthRow(JSON.parse(localStorage.getItem(k)));
          if (a && (!found || (a.expires || 0) > (found.expires || 0))) found = a;
        }
      }
    } catch (e) {}
    return found;
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

  /**
   * Two ways of asking: ours (100 at a time, only the fields used), then, if that's turned
   * down, exactly the way Kindroid's own gallery asks (12 at a time, whole selfies), in case
   * Kindroid's database only allows its own kind of question. The one that works is kept.
   */
  var SHAPES = [{page: PAGE, select: true}, {page: 12, select: false}];
  var shapeOk = -1;

  function fetchSelfies(auth, appCheck, kin, dir) {
    var first = shapeOk >= 0 ? shapeOk : 0;
    return fetchWith(SHAPES[first], auth, appCheck, kin, dir).then(function (docs) {
      shapeOk = first;
      return docs;
    }, function (e) {
      if (first !== 0 || !e.refused) throw e;
      return fetchWith(SHAPES[1], auth, appCheck, kin, dir).then(function (docs) {
        shapeOk = 1;
        return docs;
      }, function (e2) { throw e2.refused ? e : e2; });
    });
  }

  function fetchWith(shape, auth, appCheck, kin, dir) {
    var url = 'https://firestore.googleapis.com/v1/projects/kindroid-ai/databases/(default)/documents/Users/' +
      encodeURIComponent(auth.uid) + '/AIs/' + encodeURIComponent(kin) + ':runQuery';
    var headers = {'Authorization': 'Bearer ' + auth.token, 'Content-Type': 'application/json'};
    if (appCheck) headers['X-Firebase-AppCheck'] = appCheck;
    var most = shape.page < PAGE ? 300 : MOST;
    var docs = [];
    function page(after) {
      var q = {
        from: [{collectionId: 'Selfies'}],
        orderBy: [{field: {fieldPath: 'timestamp'}, direction: dir === 'asc' ? 'ASCENDING' : 'DESCENDING'}],
        limit: shape.page
      };
      if (shape.select) q.select = {fields: FIELDS.map(function (f) { return {fieldPath: f}; })};
      if (after) q.startAt = {values: [after], before: false};
      return fetch(url, {method: 'POST', headers: headers, body: JSON.stringify({structuredQuery: q})}).then(function (r) {
        if (r.ok) return r.json();
        return r.text().then(function (body) {
          var why = '';
          try { why = JSON.parse(body).error.message || ''; } catch (e) {}
          var err = new Error("Kindroid wouldn't share the selfie list (" + r.status + (why ? ': ' + why.slice(0, 120) : '') + ')');
          err.refused = r.status === 400 || r.status === 403;
          throw err;
        });
      }).then(function (rows) {
        var got = (rows || []).filter(function (r) { return r && r.document; }).map(function (r) { return r.document; });
        docs = docs.concat(got);
        var last = got.length && got[got.length - 1].fields && got[got.length - 1].fields.timestamp;
        return got.length < shape.page || docs.length >= most || !last ? docs : page(last);
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
      if (auth.expires && auth.expires < Date.now() + 30000) {
        var wait = new Error('Waiting for Kindroid to renew the login');
        wait.soon = true;
        throw wait;
      }
      return readAppCheck().then(function (pass) {
        // Kindroid's database wants the page's App Check pass. Right after the page opens it
        // may not have a fresh one yet: wait for it (a minute at most, then ask without).
        if (pass.used && !pass.token && ++state.noPass <= 2) {
          var later = new Error("Waiting for Kindroid's app check");
          later.soon = true;
          throw later;
        }
        return fetchSelfies(auth, pass.token, kin, dir);
      }).then(function (docs) { return toUrls(docs, auth.uid); });
    }).then(function (urls) {
      list.urls = urls;
      list.problem = '';
      list.soon = false;
      list.at = Date.now();
      list.loading = false;
      tell(kin, cfg);
    }, function (e) {
      list.loading = false;
      list.problem = String((e && e.message) || e);
      list.soon = !!(e && e.soon);
      // A turned-down question isn't asked again for a while; a login being renewed soon is.
      list.at = Date.now() - REFRESH_MS + (e && e.soon ? RETRY_MS : FAILED_WAIT_MS);
      tell(kin, cfg);
    });
  }

  /** Tells the app's settings pop-up how many selfies there are, or what went wrong. */
  function tell(kin, cfg) {
    var list = lists[kin + '|' + direction(cfg)] || {urls: []};
    var picked = gathered(kin);
    if (list.urls.length) report({kin: kin, count: list.urls.length, problem: ''});
    else if (picked.length) report({kin: kin, count: picked.length, problem: '', from: 'gallery'});
    else if (list.problem && list.soon) report({kin: kin, count: 0, problem: list.problem + '…'});
    else if (list.problem) report({kin: kin, count: 0, problem: list.problem +
      ". Open this Kin's selfie gallery and scroll through it once, and the slideshow will use those."});
    else if (list.at) report({kin: kin, count: 0, problem: 'This Kin has no selfies yet'});
  }

  // ---- Backup: selfies picked up from Kindroid's own gallery when it's open ----

  function gatheredKey(kin) {
    return 'kp-selfies:' + kin;
  }

  function gathered(kin) {
    try {
      var v = JSON.parse(localStorage.getItem(gatheredKey(kin)) || '[]');
      return Array.isArray(v) ? v : [];
    } catch (e) {
      return [];
    }
  }

  /** The picture's own address (Next.js's resized copies point at it). */
  function pictureUrl(img) {
    var u = img.currentSrc || img.src || '';
    var m = u.match(/\/_next\/image\?(.*)$/);
    if (m) {
      try { u = new URLSearchParams(m[1]).get('url') || u; } catch (e) {}
      if (u.charAt(0) === '/') u = location.origin + u;
    }
    u = u.replace(/^https:\/\/storage2\.kindroid\.ai\//, 'https://storage.kindroid.ai/');
    return /^https:\/\//.test(u) ? u : '';
  }

  /** While the photo gallery is open, keeps the selfies it shows, newest first, for this Kin. */
  function gather(kin) {
    var buttons = document.querySelectorAll('button, [role="button"]'), button = null;
    for (var i = 0; i < buttons.length && !button; i++) {
      if (/generate\s+photo\s+selfie/i.test(buttons[i].textContent || '')) button = buttons[i];
    }
    if (!button) return;
    var big = function (img) {
      var r = img.getBoundingClientRect();
      return r.width >= 90 && r.height >= 90;
    };
    var shown = [];
    for (var el = button.parentElement; el && el !== document.body; el = el.parentElement) {
      shown = Array.prototype.filter.call(el.querySelectorAll('img'), big);
      if (shown.length) break;
    }
    var urls = [];
    shown.forEach(function (img) {
      var u = pictureUrl(img);
      if (u && urls.indexOf(u) < 0) urls.push(u);
    });
    if (!urls.length) return;
    var kept = gathered(kin), merged = urls.slice();
    // The gallery lists newest first; ones kept from before that aren't on screen go after.
    kept.forEach(function (u) { if (merged.indexOf(u) < 0) merged.push(u); });
    merged = merged.slice(0, MOST);
    if (JSON.stringify(merged) === JSON.stringify(kept)) return;
    try { localStorage.setItem(gatheredKey(kin), JSON.stringify(merged)); } catch (e) {}
    var cfg = config();
    tell(kin, cfg);
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
    gather(kin);
    var list = lists[kin + '|' + direction(cfg)];
    if (!list || (!list.loading && Date.now() - list.at > REFRESH_MS)) load(kin, cfg);
    var urls = list && list.urls.length ? list.urls : gathered(kin);
    if (!list || !list.urls.length) {
      if (direction(cfg) === 'asc') urls = urls.slice().reverse();
      if (state.from !== 'gallery') { state.from = 'gallery'; restart(); }
    } else if (state.from !== 'list') {
      state.from = 'list';
      restart();
    }
    if (!urls.length) return;
    if (!ensureLayers()) return;
    matchKindroid();
    if (!state.busy && Date.now() >= state.nextAt) {
      var url = nextUrl(urls, cfg);
      if (url) show(url, cfg, kin);
    }
  }

  window.__kpSlidesTick = tick;
  window.__kpSlidesTest = {md5: md5, openEnc: openEnc, shows: shows};
  setInterval(tick, TICK_MS);
  tick();
})();
