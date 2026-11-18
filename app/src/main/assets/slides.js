// Kindroid+ selfie slideshow: the Kin's selfie photos take turns as the chat background.
//
// The app runs this on Kindroid's pages wrapped as (function (KP_BRIDGE, KP_MODE) { ... })('<name>', '<mode>'),
// where KP_BRIDGE names the app's bridge: settings() gives {"on", "order", "seconds"} as JSON,
// report(json) tells the app what was found (or what went wrong) for its settings pop-up.
// KP_MODE is "show" (the chat you see: the slideshow) or "gather" (a hidden page the app
// opens on the Kin's photo gallery: it scrolls through it and keeps the selfies it shows).
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
  var MODE = typeof KP_MODE === 'string' ? KP_MODE : 'show';
  var mark = MODE === 'gather' ? '__kpGather' : '__kpSlides';
  if (window[mark]) return;
  window[mark] = true;

  var bridge = window[KP_BRIDGE];
  var TICK_MS = 250;
  var GATHER_EVERY_MS = 1000;       // looks for an open gallery this often
  var REFRESH_MS = 10 * 60 * 1000;  // read the selfies again (new ones show up)
  var RETRY_MS = 30 * 1000;         // while the login is being renewed
  var FAILED_WAIT_MS = 5 * 60 * 1000; // after Kindroid turned the read down
  var PAGE = 100;                   // selfies per request
  var MOST = 1000;                  // most selfies kept per Kin
  var FADE_MS = 1200;
  var FIELDS = ['url', 'thumbnail_url', 'timestamp', 'status', 'deleted', 'output_moderation_status',
    'blocked_for_native_policy', 'native_sfw_policy'];

  var lists = {};  // kin id -> {photos, videos, at, loading, problem, soon, videoProblem}
  var state = {cfg: '', kin: null, pos: -1, bag: [], shown: null, nextAt: 0, busy: false, failed: 0, noPass: 0, gatheredAt: 0, sheet: null, covered: null};  // sheet: null until first told, so a new page always says
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

  /** The fields read from each selfie: photos ("Selfies") and videos ("VideoSelfies"). */
  var READS = {
    Selfies: FIELDS,
    VideoSelfies: ['video_url', 'image_url', 'timestamp', 'status', 'deleted', 'output_moderation_status',
      'blocked_for_native_policy', 'native_sfw_policy']
  };

  function fetchSelfies(auth, appCheck, kin, collection) {
    var first = shapeOk >= 0 ? shapeOk : 0;
    return fetchWith(SHAPES[first], auth, appCheck, kin, collection).then(function (docs) {
      shapeOk = first;
      return docs;
    }, function (e) {
      if (first !== 0 || !e.refused) throw e;
      return fetchWith(SHAPES[1], auth, appCheck, kin, collection).then(function (docs) {
        shapeOk = 1;
        return docs;
      }, function (e2) { throw e2.refused ? e : e2; });
    });
  }

  /** Newest first, [shape.page] at a time, up to [MOST] (300 when asking 12 at a time). */
  function fetchWith(shape, auth, appCheck, kin, collection) {
    var url = 'https://firestore.googleapis.com/v1/projects/kindroid-ai/databases/(default)/documents/Users/' +
      encodeURIComponent(auth.uid) + '/AIs/' + encodeURIComponent(kin) + ':runQuery';
    var headers = {'Authorization': 'Bearer ' + auth.token, 'Content-Type': 'application/json'};
    if (appCheck) headers['X-Firebase-AppCheck'] = appCheck;
    var most = shape.page < PAGE ? 300 : MOST;
    var docs = [];
    function page(after) {
      var q = {
        from: [{collectionId: collection}],
        orderBy: [{field: {fieldPath: 'timestamp'}, direction: 'DESCENDING'}],
        limit: shape.page
      };
      if (shape.select) q.select = {fields: READS[collection].map(function (f) { return {fieldPath: f}; })};
      if (after) q.startAt = {values: [after], before: false};
      return fetch(url, {method: 'POST', headers: headers, body: JSON.stringify({structuredQuery: q})}).then(function (r) {
        if (r.ok) return r.json();
        return r.text().then(function (body) {
          var why = '';
          try { why = JSON.parse(body).error.message || ''; } catch (e) {}
          var err = new Error("Kindroid wouldn't share the " + (collection === 'Selfies' ? 'selfie' : 'video selfie') +
            ' list (' + r.status + (why ? ': ' + why.slice(0, 120) : '') + ')');
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

  /**
   * A selfie's address as Kindroid's own page makes it (its resolver, module 252913): web
   * addresses stay (storage2 becomes storage), and a bare storage path like
   * "users/<uid>/…/<id>.jpg" (what "!enc:" values open to) goes on storage.kindroid.ai.
   */
  function fullUrl(value) {
    var v = String(value || '').trim();
    if (!v || /^(blob|data):/i.test(v)) return '';
    if (/^https?:\/\//i.test(v)) {
      v = v.replace(/^https:\/\/storage2\.kindroid\.ai\//, 'https://storage.kindroid.ai/');
      return /^https:\/\//.test(v) ? v : '';
    }
    return 'https://storage.kindroid.ai/' + v.replace(/^\/+/, '');
  }

  /** Kindroid's own resizer for pictures on its storage: a phone-sized copy, so even 1-second slides load fast. */
  function lighter(url, width) {
    var m = url.match(/^https:\/\/storage\.kindroid\.ai\/(?!cdn-cgi\/)(.+)$/);
    if (!m || /\.(gif|mp4|mov|webm|m4v)(\?|$)/i.test(m[1])) return url;
    return 'https://storage.kindroid.ai/cdn-cgi/image/width=' + (width || 1080) + ',fit=scale-down,format=auto/' + m[1];
  }

  function openValue(v, uid) {
    if (typeof v !== 'string' || !v) return Promise.resolve('');
    return v.indexOf('!enc:') === 0 ? openEnc(v.substring(5), uid) : Promise.resolve(v);
  }

  /**
   * What the slideshow shows: {kind: "photo" | "video", url, poster (videos' still), key (the
   * picture's own address, for favorites), at (when it was made)}.
   */
  function toItems(docs, uid, kind) {
    var fields = kind === 'video' ? READS.VideoSelfies : FIELDS;
    return Promise.all(docs.map(function (d) {
      var x = {};
      fields.forEach(function (f) { x[f] = value(d.fields && d.fields[f]); });
      if (!shows(x)) return null;
      var main = kind === 'video' ? x.video_url : (x.url || x.thumbnail_url);
      return Promise.all([openValue(main, uid), openValue(kind === 'video' ? x.image_url : '', uid)]).then(function (r) {
        var url = fullUrl(r[0]);
        if (!url) return null;
        var at = typeof x.timestamp === 'number' && isFinite(x.timestamp) ? x.timestamp : 0;
        return kind === 'video'
          ? {kind: 'video', key: url, url: url, poster: r[1] ? lighter(fullUrl(r[1])) : '', at: at}
          : {kind: 'photo', key: url, url: lighter(url), at: at};
      });
    })).then(function (items) {
      var seen = {};
      return items.filter(function (it) { if (!it || seen[it.key]) return false; seen[it.key] = 1; return true; });
    });
  }

  function load(kin, cfg) {
    var list = lists[kin] || (lists[kin] = {photos: [], videos: [], at: 0, loading: false});
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
        var photos = fetchSelfies(auth, pass.token, kin, 'Selfies').then(function (docs) { return toItems(docs, auth.uid, 'photo'); });
        // Videos are extra: if they can't be read, the photos still show.
        var videos = fetchSelfies(auth, pass.token, kin, 'VideoSelfies').then(function (docs) {
          list.videoProblem = '';
          return toItems(docs, auth.uid, 'video');
        }, function (e) {
          list.videoProblem = String((e && e.message) || e);
          return list.videos;
        });
        return Promise.all([photos, videos]);
      });
    }).then(function (both) {
      list.photos = both[0];
      list.videos = both[1];
      list.problem = '';
      list.soon = false;
      list.at = Date.now();
      list.loading = false;
      tell(kin, config());
    }, function (e) {
      list.loading = false;
      list.problem = String((e && e.message) || e);
      list.soon = !!(e && e.soon);
      // A turned-down question isn't asked again for a while; a login being renewed soon is.
      list.at = Date.now() - REFRESH_MS + (e && e.soon ? RETRY_MS : FAILED_WAIT_MS);
      tell(kin, config());
    });
  }

  // ---- My pictures (picked from the phone; the app serves them at /__kindroidplus/mine/…) ----

  var mineCache = {gen: -1, items: []};

  function mine(cfg) {
    var gen = cfg && cfg.mineGen != null ? cfg.mineGen : mineCache.gen;
    if (gen !== mineCache.gen) {
      mineCache.gen = gen;
      try {
        mineCache.items = JSON.parse(bridge.myPictures() || '[]').map(function (m) {
          return {kind: 'photo', key: m.url, url: m.url, at: m.at || 0, mine: true};
        });
      } catch (e) {
        mineCache.items = [];
      }
    }
    return mineCache.items;
  }

  // ---- Favorites: starred pictures, kept per Kin in localStorage ----

  var favVersion = 0;

  function favs(kin) {
    try { return JSON.parse(localStorage.getItem('kp-favs:' + kin) || '{}') || {}; } catch (e) { return {}; }
  }

  function toggleFav(kin, key) {
    var f = favs(kin);
    if (f[key]) delete f[key]; else f[key] = 1;
    try { localStorage.setItem('kp-favs:' + kin, JSON.stringify(f)); } catch (e) {}
    favVersion++;
    return !!f[key];
  }

  /** Everything this Kin's slideshow could show, newest first (before the settings pick from it). */
  function everything(kin, cfg) {
    var list = lists[kin] || {photos: [], videos: []};
    var photos = list.photos.length ? list.photos : gathered(kin).map(function (u, i) {
      return {kind: 'photo', key: u, url: u, at: -i};
    });
    return {photos: photos, videos: list.videos, mine: mine(cfg)};
  }

  var itemsCache = {sig: '', items: []};

  /** What the slideshow shows now: the chosen kinds, in the chosen order, maybe favorites only. */
  function itemsFor(kin, cfg) {
    var list = lists[kin] || {at: 0};
    var sig = [kin, list.at, gatheredCache.at, mineCache.gen, favVersion, cfg.order, cfg.photos, cfg.videos, cfg.mine, cfg.favorites].join('|');
    if (sig === itemsCache.sig) return itemsCache.items;
    var all = everything(kin, cfg), out = [];
    if (cfg.photos !== false) out = out.concat(all.photos);
    if (cfg.videos !== false) out = out.concat(all.videos);
    if (cfg.mine) out = out.concat(all.mine);
    out.sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
    if (cfg.order === 'oldest') out.reverse();
    state.noFavs = false;
    if (cfg.favorites) {
      var f = favs(kin), only = out.filter(function (it) { return f[it.key]; });
      if (only.length) out = only; else state.noFavs = true;
    }
    itemsCache = {sig: sig, items: out};
    return out;
  }

  /**
   * Tells the app what there is and where it came from, or what went wrong.
   * needGallery asks the app to look through the gallery by itself (the photo list couldn't be read).
   */
  function tell(kin, cfg) {
    var list = lists[kin] || {photos: [], videos: []};
    var all = everything(kin, cfg);
    var o = {
      kin: kin, count: itemsFor(kin, cfg).length, photos: all.photos.length, videos: all.videos.length,
      mine: all.mine.length, favorites: Object.keys(favs(kin)).length, noFavs: !!state.noFavs,
      from: list.photos.length || !list.problem ? 'list' : 'gallery'
    };
    if (list.problem && list.soon) o.waiting = list.problem;
    else if (list.problem) {
      o.listProblem = list.problem;
      o.needGallery = cfg.photos !== false;
    }
    if (list.videoProblem) o.videoProblem = list.videoProblem;
    if (!o.count && list.at && !list.problem) o.problem = 'Nothing to show yet: no selfies' + (cfg.mine ? ' or pictures' : '');
    report(o);
  }

  // ---- Backup: selfies picked up from Kindroid's own gallery when it's open ----

  function gatheredKey(kin) {
    return 'kp-selfies:' + kin;
  }

  /** Read from localStorage at most every few seconds (the hidden gallery page writes it too). */
  var gatheredCache = {kin: null, list: [], read: 0, at: 0};

  function gathered(kin) {
    if (gatheredCache.kin === kin && Date.now() - gatheredCache.read < 5000) return gatheredCache.list;
    var v = [];
    try { v = JSON.parse(localStorage.getItem(gatheredKey(kin)) || '[]'); } catch (e) {}
    if (!Array.isArray(v)) v = [];
    if (gatheredCache.kin !== kin || v.length !== gatheredCache.list.length || v[0] !== gatheredCache.list[0]) gatheredCache.at = Date.now();
    gatheredCache = {kin: kin, list: v, read: Date.now(), at: gatheredCache.at};
    return v;
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

  /** The open photo gallery's "Generate Photo Selfie" button, or null. */
  function galleryButton() {
    var buttons = document.querySelectorAll('button, [role="button"]');
    for (var i = 0; i < buttons.length; i++) {
      if (/generate\s+photo\s+selfie/i.test(buttons[i].textContent || '')) return buttons[i];
    }
    return null;
  }

  function bigPicture(img) {
    var r = img.getBoundingClientRect();
    return r.width >= 90 && r.height >= 90;
  }

  /**
   * While the photo gallery is open, keeps the selfies it shows, newest first, for this Kin.
   * Gives the ones on show (null when the gallery isn't open).
   */
  function gather(kin) {
    var button = galleryButton();
    if (!button) return null;
    var shown = [];
    for (var el = button.parentElement; el && el !== document.body; el = el.parentElement) {
      shown = Array.prototype.filter.call(el.querySelectorAll('img'), bigPicture);
      if (shown.length) break;
    }
    var urls = [];
    shown.forEach(function (img) {
      var u = pictureUrl(img);
      if (u && urls.indexOf(u) < 0) urls.push(u);
    });
    if (!urls.length) return urls;
    // In the gallery's order (newest first): a new one goes right after the known one
    // before it, or first if none is, so the order holds even if the gallery only keeps
    // what's on screen.
    var kept = gathered(kin), merged = kept.slice(), at = -1;
    urls.forEach(function (u) {
      var i = merged.indexOf(u);
      if (i >= 0) { at = i; return; }
      merged.splice(at + 1, 0, u);
      at++;
    });
    merged = merged.slice(0, MOST);
    if (merged.length === kept.length && merged.every(function (u, i) { return u === kept[i]; })) return urls;
    try { localStorage.setItem(gatheredKey(kin), JSON.stringify(merged)); } catch (e) {}
    gatheredCache = {kin: kin, list: merged, read: Date.now(), at: Date.now()};
    if (MODE === 'show') tell(kin, config());
    return urls;
  }

  /** The part of the gallery that scrolls (the page itself if nothing else does). */
  function galleryScroller(button) {
    for (var el = button.parentElement; el && el !== document.body; el = el.parentElement) {
      var boxes = [el].concat(Array.prototype.slice.call(el.querySelectorAll('div')));
      for (var i = 0; i < boxes.length; i++) {
        var b = boxes[i], y = getComputedStyle(b).overflowY;
        if ((y === 'auto' || y === 'scroll') && b.scrollHeight > b.clientHeight + 20 &&
            Array.prototype.some.call(b.querySelectorAll('img'), bigPicture)) return b;
      }
    }
    return document.scrollingElement || document.documentElement;
  }

  /**
   * The hidden page the app opens on the Kin's photo gallery: scrolls to the bottom again and
   * again so the gallery loads more, keeping every selfie it shows, then says it's done. A
   * Kin looked through before only needs the new ones: it stops after a run of known ones.
   */
  function gatherAll() {
    var kin = kinId(), began = Date.now(), before = {}, seen = {}, count = 0, knownRun = 0, quiet = 0, last = -1;
    gathered(kin).forEach(function (u) { before[u] = 1; });
    var quick = Object.keys(before).length > 0;
    var timer = setInterval(function () {
      kin = kinId() || kin;
      var urls = gather(kin) || [];
      urls.forEach(function (u) {
        if (seen[u]) return;
        seen[u] = 1;
        count++;
        knownRun = before[u] ? knownRun + 1 : 0;
      });
      var button = urls.length ? galleryButton() : null;
      if (button) {
        var box = galleryScroller(button);
        box.scrollTop = box.scrollHeight;
      }
      quiet = count === last ? quiet + 1 : 0;
      last = count;
      var took = Date.now() - began;
      var done = took > 150000 || count >= MOST || (quick && knownRun >= 24) ||
        (count > 0 && quiet >= 15) || (count === 0 && took > 45000);
      report({mode: 'gather', kin: kin, count: count, done: done});
      if (done) clearInterval(timer);
    }, 800);
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
      paint(layers[0], state.shown, state.shown.kind === 'video' ? makeVideo(state.shown.url) : null);
      layers[0].style.opacity = '1';
      void layers[0].offsetWidth;
      layers[0].style.transition = 'opacity ' + FADE_MS + 'ms ease';
    }
    return box;
  }

  /**
   * The Kin's menu (tap the avatar) slides the chat aside and its words sit right over the
   * background: with selfies there they were hard to read (owner's screenshot). While it's
   * open the slideshow turns into plain dark, so the pictures only show behind the chat.
   */
  function darkenForMenu(peek) {
    if (!box) return;
    if (peek) {
      box.style.opacity = '1';
      box.style.filter = '';
      box.style.backgroundColor = 'var(--v2-fixed-black, #0f0e0f)';
    } else {
      box.style.backgroundColor = '';
    }
    layers.forEach(function (l) {
      l.style.visibility = peek ? 'hidden' : '';
      Array.prototype.forEach.call(l.querySelectorAll('video'), function (v) {
        if (peek) v.pause();
        else if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
      });
    });
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

  function nextItem(items, cfg) {
    if (cfg.order === 'random') {
      if (!state.bag.length) {
        state.bag = shuffle(items.slice());
        if (state.bag.length > 1 && state.shown && state.bag[0].key === state.shown.key) state.bag.push(state.bag.shift());
      }
      return state.bag.shift();
    }
    state.pos = (state.pos + 1) % items.length;
    return items[state.pos];
  }

  /** A muted video that plays round and round, filling the screen like the pictures do. */
  function makeVideo(url) {
    var v = document.createElement('video');
    v.muted = true;
    v.defaultMuted = true;
    v.loop = true;
    v.playsInline = true;
    v.autoplay = true;
    v.preload = 'auto';
    v.setAttribute('muted', '');
    v.setAttribute('playsinline', '');
    v.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:50% 30%;';
    v.src = url;
    return v;
  }

  function dispose(v) {
    try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) {}
    if (v.parentNode) v.parentNode.removeChild(v);
  }

  /** Stops and takes out a layer's video, if it has one. */
  function clearLayer(layer) {
    Array.prototype.slice.call(layer.querySelectorAll('video')).forEach(dispose);
  }

  function paint(layer, item, video) {
    clearLayer(layer);
    if (item.kind === 'video') {
      layer.style.backgroundImage = item.poster ? css(item.poster) : '';
      if (video) {
        layer.appendChild(video);
        var p = video.play();
        if (p && p.catch) p.catch(function () {});
      }
    } else {
      layer.style.backgroundImage = css(item.url);
    }
  }

  /** Loads [item] first (a picture, or enough of a video to play), then fades it in. */
  function show(item, cfg, kin) {
    state.busy = true;
    var done = function (ok, video) {
      state.busy = false;
      if (!ok) {
        state.failed++;
        state.nextAt = Date.now() + (state.failed > 20 ? 30000 : 0);
        if (state.failed === 20) report({kin: kin, count: 0, problem: "The selfies didn't load"});
        return;
      }
      state.failed = 0;
      if (state.kin !== kin || !ensureLayers()) {
        if (video) dispose(video);
        return;
      }
      var incoming = layers[1 - front], old = layers[front], fade = fadeMs(cfg);
      incoming.style.transition = old.style.transition = 'opacity ' + fade + 'ms ease';
      incoming.style.zIndex = '2';
      old.style.zIndex = '1';
      paint(incoming, item, video);
      incoming.style.opacity = '1';
      front = 1 - front;
      // The old one goes once the new one covers it, so Kindroid's own never peeks through.
      setTimeout(function () {
        if (layers[front] !== old) {
          old.style.opacity = '0';
          clearLayer(old);
        }
      }, fade);
      state.shown = item;
      // A video plays all the way through at least once (up to a minute), even on fast slideshows.
      var length = video && isFinite(video.duration) ? video.duration * 1000 : 0;
      state.nextAt = Date.now() + Math.max(slideMs(cfg), Math.min(60000, length));
    };
    if (item.kind === 'video') {
      var v = makeVideo(item.url), settled = false, timer = 0;
      var finish = function (ok) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (!ok) dispose(v);
        done(ok, ok ? v : null);
      };
      timer = setTimeout(function () { finish(false); }, 15000);
      v.addEventListener('canplay', function () { finish(true); });
      v.addEventListener('error', function () { finish(false); });
      v.load();
    } else {
      var img = new Image();
      img.onload = function () { done(true); };
      img.onerror = function () { done(false); };
      img.src = item.url;
    }
  }

  // ---- ⭐ Favorites screen: every selfie (and picture) of this Kin; tap to star ----

  function closeFavorites() {
    var o = document.getElementById('kp-favorites');
    if (o) o.parentNode.removeChild(o);
  }

  window.__kpOpenFavorites = function () {
    var kin = kinId();
    if (!kin) return false;
    closeFavorites();
    var cfg = config(), all = everything(kin, cfg);
    var items = all.photos.concat(all.videos, all.mine).sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
    var starred = favs(kin);
    var o = document.createElement('div');
    o.id = 'kp-favorites';
    o.setAttribute('role', 'dialog');
    o.setAttribute('aria-modal', 'true');
    o.setAttribute('aria-label', 'Favorites');
    o.style.cssText = 'position:fixed;inset:0;z-index:2147482000;pointer-events:auto;overflow-y:auto;' +
      'background:rgba(14,11,16,.97);color:#fff;font:15px system-ui,sans-serif;';
    var head = document.createElement('div');
    head.style.cssText = 'position:sticky;top:0;z-index:1;display:flex;align-items:center;gap:10px;padding:14px 14px 10px;background:rgba(14,11,16,.97);';
    var title = document.createElement('div');
    title.style.cssText = 'flex:1;font-weight:600;';
    var count = function () {
      var n = Object.keys(starred).length;
      title.textContent = '⭐ Tap to star · ' + n + ' starred';
    };
    count();
    var doneButton = document.createElement('button');
    doneButton.textContent = 'Done';
    doneButton.style.cssText = 'padding:8px 18px;border-radius:999px;border:1px solid rgba(255,111,165,.7);background:transparent;color:#fff;font:600 14px system-ui,sans-serif;';
    doneButton.addEventListener('click', closeFavorites);
    head.appendChild(title);
    head.appendChild(doneButton);
    o.appendChild(head);
    var grid = document.createElement('div');
    grid.style.cssText = 'display:grid;grid-template-columns:repeat(3,1fr);gap:4px;padding:4px;';
    if (!items.length) {
      var none = document.createElement('div');
      none.textContent = 'No selfies found yet.';
      none.style.cssText = 'padding:30px;text-align:center;opacity:.7;grid-column:1/-1;';
      grid.appendChild(none);
    }
    items.forEach(function (it) {
      var cell = document.createElement('div');
      cell.style.cssText = 'position:relative;aspect-ratio:3/4;background:#222;border-radius:6px;overflow:hidden;';
      var img = document.createElement('img');
      img.loading = 'lazy';
      img.src = (it.kind === 'video' ? it.poster : it.url).replace('/cdn-cgi/image/width=1080,', '/cdn-cgi/image/width=300,');
      img.style.cssText = 'width:100%;height:100%;object-fit:cover;display:block;';
      cell.appendChild(img);
      if (it.kind === 'video' || it.mine) {
        var tag = document.createElement('div');
        tag.textContent = it.kind === 'video' ? '▶' : '📱';
        tag.style.cssText = 'position:absolute;left:6px;bottom:6px;font-size:14px;text-shadow:0 1px 3px #000;';
        cell.appendChild(tag);
      }
      var star = document.createElement('div');
      star.style.cssText = 'position:absolute;right:6px;top:4px;font-size:22px;text-shadow:0 1px 4px #000;';
      var paintStar = function () {
        star.textContent = starred[it.key] ? '⭐' : '☆';
        cell.style.outline = starred[it.key] ? '3px solid #ffc83d' : 'none';
        cell.style.outlineOffset = '-3px';
      };
      paintStar();
      cell.appendChild(star);
      cell.addEventListener('click', function () {
        if (toggleFav(kin, it.key)) starred[it.key] = 1; else delete starred[it.key];
        paintStar();
        count();
      });
      grid.appendChild(cell);
    });
    o.appendChild(grid);
    // Back (Escape) closes it, like Kindroid's own sheets.
    var esc = function (e) {
      if (e.key === 'Escape' && document.getElementById('kp-favorites') === o) {
        closeFavorites();
        document.removeEventListener('keydown', esc, true);
      }
    };
    document.addEventListener('keydown', esc, true);
    document.body.appendChild(o);
    return true;
  };

  /** Stars (or unstars) the one showing now. Gives "on", "off" or "none". */
  window.__kpStarCurrent = function () {
    var kin = kinId();
    if (!kin || !state.shown) return 'none';
    return toggleFav(kin, state.shown.key) ? 'on' : 'off';
  };

  // ---- See-through message boxes ----
  // Kindroid's bubbles are always solid (its "Immersive" preset only brightens the background
  // and fades the top of the chat). Classes are CSS-module hashes: matched by their local names
  // (…_bubble-self / …_bubble-other), leaving out bare ones (pictures, videos, voice notes).
  // No blur behind them: the owner wants the picture clear through the box ("not blurry").
  // A soft shadow under the words keeps them readable on busy pictures.
  // "none": no box at all (no colour, edge or shadow), only the words, with a darker glow
  // around them so they stay readable on any picture.
  var BUBBLES = {glass: {keep: 55, shadow: 0.45}, clear: {keep: 25, shadow: 0.7}, none: {keep: 0, shadow: 0.95}};

  function bubbleCss(b) {
    var rule = function (side, color) {
      var box = b.keep > 0
        ? 'background:color-mix(in srgb, ' + color + ' ' + b.keep + '%, transparent) !important;'
        : 'background:transparent !important;border-color:transparent !important;box-shadow:none !important;';
      var glow = b.keep > 0
        ? 'text-shadow:0 1px 3px rgba(0,0,0,' + b.shadow + ');'
        : 'text-shadow:0 0 2px rgba(0,0,0,.95),0 1px 4px rgba(0,0,0,.9),0 0 10px rgba(0,0,0,.6);';
      return '[class*="_bubble-' + side + '"]:not([class*="_bubble-bare"]):not([class*="_bubble-wrap"]){' + box +
        '-webkit-backdrop-filter:none !important;backdrop-filter:none !important;' + glow + '}';
    };
    return rule('other', 'var(--chat-message-color-other, var(--chat-message-surface-other, #1b1a1c))') +
      rule('self', 'var(--chat-message-color-self, var(--color-cta, #964f43))');
  }

  // ---- Shorter voice bars ----
  // Kindroid's voice player (CSS module …_card in a …_voice-drawer: ···, ▷, the waveform
  // (role=slider "Seek voice audio", bars counted from its width by a ResizeObserver), 0:00)
  // stretches across the bubble. A fixed 140 px waveform gives fewer bars; seeking uses the
  // slider's own size, so it still works (checked live by the owner's PC session).
  var VOICE_CSS =
    '[class*="_voice-drawer"] [class*="_card"],[class*="_card"]:has(> [aria-label="Seek voice audio"]){flex:0 1 auto !important;}' +
    '[class*="_voice-drawer"] [class*="_waveform"],[role="slider"][aria-label="Seek voice audio"]' +
    '{flex:0 0 140px !important;width:140px !important;max-width:140px !important;min-width:0 !important;}';

  function shortVoiceBars() {
    if (document.getElementById('kp-voice')) return;
    var el = document.createElement('style');
    el.id = 'kp-voice';
    el.textContent = VOICE_CSS;
    (document.head || document.documentElement).appendChild(el);
  }

  function styleBubbles(kind) {
    var el = document.getElementById('kp-bubbles'), b = BUBBLES[kind];
    if (!b) {
      if (el) el.parentNode.removeChild(el);
      return;
    }
    var css = bubbleCss(b);
    if (!el) {
      el = document.createElement('style');
      el.id = 'kp-bubbles';
      (document.head || document.documentElement).appendChild(el);
    }
    if (el.textContent !== css) el.textContent = css;
  }

  // ---- ✨ Idea buttons on Kindroid's text boxes ----
  // From kindroid.ai's code: every box is a React-controlled <textarea> (so text goes in with
  // the native value setter + an "input" event), and nothing saves until the owner taps
  // Kindroid's own Save. The buttons float over the boxes from the end of the page, never
  // inside React's own elements.
  // - The scene: the "Set the scene" sheet's textarea (maxlength 160, "location, attire…"),
  //   its "Suggested scene" one, or #kin-scene-v2 in the Kin's settings.
  // - The Kin's settings ("Personality" tab): textareas with these ids; the limit is the
  //   box's own maxlength (it's bigger for subscribers).
  // - The chat's message box (aria-label "Message"): ideas for what to say.
  var FIELDS_BY_ID = {
    'kin-backstory-v2': ['Personality', "who your Kin is: personality, backstory, how they talk and act, and your relationship, written about them by name"],
    'kin-memory-v2': ['Key Memories', 'the most important things your Kin should always remember about you and your time together, as short facts'],
    'kin-developed-self-v2': ['Growth & relationship', 'how your Kin has changed and how your relationship has grown'],
    'kin-durable-facts-v2': ['Important facts', 'stable facts about you, your Kin and your lives that stay true'],
    'kin-ongoing-context-v2': ['Ongoing threads', 'current plans, promises and things in progress between you'],
    'kin-directive-v2': ['Response Directive', "a short instruction for your Kin's style of replying"],
    'kin-example-message-v2': ['Example Message', 'one example message written exactly in your Kin\'s own voice and style'],
    'kin-additional-context-v2': ['Additional Context', 'extra context: world details, places, people, rules of the story'],
    'kin-call-directive-v2': ['Call Directive', 'how your Kin should behave on voice calls'],
    'kin-away-directive-v2': ['Away Directive', 'how your Kin should behave when reaching out on their own']
  };

  /**
   * Room at the bottom of the boxes that get a ✨ pill, so it never covers the last line of
   * text (the PC session saw it covering words in Personality and Key Memories).
   */
  function makeRoomForPills() {
    if (document.getElementById('kp-ideas-room')) return;
    var boxes = Object.keys(FIELDS_BY_ID).map(function (id) { return 'textarea#' + id; })
      .concat(['textarea#kin-scene-v2', 'textarea[maxlength="160"]', 'textarea[class*="_suggestion-input"]']);
    var el = document.createElement('style');
    el.id = 'kp-ideas-room';
    el.textContent = boxes.join(',') + '{padding-bottom:46px !important;}';
    (document.head || document.documentElement).appendChild(el);
  }

  function isSceneBox(t) {
    var hint = (t.getAttribute('placeholder') || '').toLowerCase();
    return t.id === 'kin-scene-v2' || t.getAttribute('maxlength') === '160' || /attire/.test(hint) ||
      /_suggestion-input/.test(String(t.className));
  }

  function isMessageBox(t) {
    return t.getAttribute('aria-label') === 'Message' ||
      (/_input\b/.test(String(t.className)) && /message/i.test(t.getAttribute('placeholder') || ''));
  }

  /** What kind of box this is: "scene", "field" (a Kin setting), "reply" (the message box) or null. */
  function boxKind(t) {
    if (isSceneBox(t)) return 'scene';
    if (FIELDS_BY_ID[t.id]) return 'field';
    if (isMessageBox(t)) return 'reply';
    return null;
  }

  function visibleBoxes(kind) {
    return Array.prototype.filter.call(document.querySelectorAll('textarea'), function (t) {
      return t.offsetWidth > 0 && t.offsetHeight > 0 && (!kind || boxKind(t) === kind);
    });
  }

  function sceneBoxes() {
    return visibleBoxes('scene');
  }

  /**
   * The Kin's name from the chat's header: the innermost "…_name" element near the top (an
   * outer one holds the name twice, which read "avaava"), and a name that's the same word twice
   * is halved.
   */
  function kinName() {
    var names = document.querySelectorAll('[class*="_name"]');
    for (var i = 0; i < names.length; i++) {
      var inner = names[i].querySelectorAll('[class*="_name"]'), hasInner = false;
      for (var j = 0; j < inner.length; j++) if (inner[j].offsetWidth > 0 && (inner[j].textContent || '').trim()) hasInner = true;
      if (hasInner) continue;
      var r = names[i].getBoundingClientRect();
      var t = (names[i].innerText || names[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (t.length % 2 === 0 && t.slice(0, t.length / 2) === t.slice(t.length / 2)) t = t.slice(0, t.length / 2);
      if (r.top < innerHeight * 0.25 && r.width > 0 && t && t.length <= 40) return t;
    }
    return '';
  }

  function recentMessages(n) {
    var bubbles = Array.prototype.filter.call(
      document.querySelectorAll('[class*="_bubble-self"], [class*="_bubble-other"]'),
      function (b) { return !/_bubble-(bare|wrap)/.test(String(b.className)); }).slice(-n);
    return bubbles.map(function (b) {
      var text = (b.innerText || b.textContent || '').replace(/…|\.\.\.|▷|\b\d{1,2}:\d{2}\b/g, ' ').replace(/\s+/g, ' ').trim();
      return {mine: /_bubble-self/.test(String(b.className)), text: text.slice(0, 300)};
    }).filter(function (m) { return m.text; });
  }

  /** What the AI gets to fit its idea: the box, the Kin's name, the last messages, the Kin's other settings. */
  function ideaContext(box, kind) {
    var c = {kind: kind, name: kinName(), current: box ? box.value : '', recent: recentMessages(kind === 'reply' ? 10 : 6)};
    if (kind === 'field' && box) {
      var f = FIELDS_BY_ID[box.id];
      c.field = {id: box.id, label: f[0], guide: f[1], limit: Number(box.getAttribute('maxlength')) || 1000,
        example: box.getAttribute('placeholder') || ''};
      c.others = {};
      Object.keys(FIELDS_BY_ID).forEach(function (id) {
        var other = document.getElementById(id);
        if (other && other !== box && other.value && other.value.trim()) c.others[FIELDS_BY_ID[id][0]] = other.value.trim().slice(0, 1200);
      });
    }
    if (kind === 'scene') c.limit = Number(box && box.getAttribute('maxlength')) || 160;
    return c;
  }

  var ideaPool = [];  // {box, button}

  function ideaButton(kind) {
    var b = document.createElement('button');
    b.type = 'button';
    b.setAttribute('data-kp-ideas', kind);
    if (kind === 'scene') b.id = 'kp-ideas';
    b.textContent = kind === 'reply' ? '💬 What to say?' : '✨ Ideas';
    // pointer-events:auto: a sheet that's open may turn taps off on the rest of the page.
    b.style.cssText = 'position:fixed;z-index:2147483000;pointer-events:auto;padding:6px 12px;border-radius:999px;' +
      'border:1px solid rgba(255,111,165,.7);background:rgba(40,20,32,.92);color:#fff;font:600 13px system-ui,sans-serif;' +
      'box-shadow:0 2px 8px rgba(0,0,0,.4);white-space:nowrap;';
    // The sheet mustn't take a tap on the button as a tap outside it (which would close it).
    ['pointerdown', 'mousedown', 'touchstart'].forEach(function (k) {
      b.addEventListener(k, function (e) { e.stopPropagation(); }, true);
    });
    return b;
  }

  /** One button per visible box: on the box's bottom-right corner, or above the message box. */
  function showIdeasButtons() {
    var boxes = visibleBoxes(null).filter(function (t) { return boxKind(t); });
    // A box that's under an open sheet (the message box behind the scene sheet) gets none.
    var sheet = sheetOpen();
    if (sheet) boxes = boxes.filter(function (t) { return sheet.contains(t); });
    var keep = [];
    boxes.forEach(function (box) {
      var kind = boxKind(box), entry = null;
      for (var i = 0; i < ideaPool.length; i++) if (ideaPool[i].box === box) entry = ideaPool[i];
      if (!entry) {
        var button = ideaButton(kind);
        entry = {box: box, button: button};
        button.addEventListener('click', function (e) {
          e.preventDefault();
          e.stopPropagation();
          window.__kpTarget = box;
          try { bridge.ideas(JSON.stringify(ideaContext(box, kind))); } catch (err) {}
        }, true);
        document.body.appendChild(button);
      }
      var r = box.getBoundingClientRect(), b = entry.button;
      b.style.display = '';
      if (kind === 'reply') {
        b.style.top = Math.max(0, r.top - 40) + 'px';
        b.style.left = Math.max(4, r.right - (b.offsetWidth || 130)) + 'px';
      } else {
        b.style.top = Math.max(0, r.bottom - 38) + 'px';
        b.style.left = Math.max(0, r.right - (b.offsetWidth || 90) - 8) + 'px';
      }
      keep.push(entry);
    });
    ideaPool.forEach(function (entry) {
      if (keep.indexOf(entry) < 0 && entry.button.parentNode) entry.button.parentNode.removeChild(entry.button);
    });
    ideaPool = keep;
  }

  /** Puts [text] in a box the way typing would (React only hears an "input" event). */
  function putIn(t, text) {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(t, String(text));
    t.dispatchEvent(new Event('input', {bubbles: true}));
    t.focus();
    return true;
  }

  /** Puts [text] in the box whose button was tapped ([kind] says which kind of box to look for if it's gone). */
  window.__kpPut = function (text, kind) {
    var t = window.__kpTarget;
    if (!t || !t.isConnected || !t.offsetWidth || (kind && boxKind(t) !== kind)) t = visibleBoxes(kind || 'scene').pop();
    return t ? putIn(t, text) : false;
  };

  window.__kpSetScene = function (text) {
    return window.__kpPut(text, 'scene');
  };

  /** For the app's menu: the context for [kind] ideas from what's on the page. */
  window.__kpContext = function (kind) {
    var box = visibleBoxes(kind).pop() || null;
    if (box) window.__kpTarget = box;
    return JSON.stringify(ideaContext(box, kind));
  };

  window.__kpSceneContext = function () {
    return window.__kpContext('scene');
  };

  // ---- Kindroid's sheets (gallery, scene, settings): the app hides its 🖼 button while one is
  // open, and the phone's back button closes the sheet instead of leaving the chat. ----

  function sheetOpen() {
    var spots = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
    for (var i = 0; i < spots.length; i++) {
      var r = spots[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && getComputedStyle(spots[i]).visibility !== 'hidden') return spots[i];
    }
    return null;
  }

  /** The Kin's menu (tap the avatar): the chat card slides aside, "peeked". */
  function chatPeeked() {
    var spots = document.querySelectorAll('[class*="_card-peeked"]');
    for (var i = 0; i < spots.length; i++) if (spots[i].getBoundingClientRect().width > 0) return true;
    return false;
  }

  function tellSheet() {
    var open = !!sheetOpen();
    if (open !== state.sheet) {
      state.sheet = open;
      try { bridge.sheet(open); } catch (e) {}
    }
    // The 🖼 button also keeps out of the way of the Kin's menu (back there works as it is).
    state.peeked = chatPeeked();
    var covered = open || state.peeked;
    if (covered !== state.covered) {
      state.covered = covered;
      try { bridge.cover(covered); } catch (e) {}
    }
  }

  /** Back with a sheet open: Escape (what sheets close on), then its close button, then back. */
  window.__kpCloseSheet = function () {
    var esc = function (target) {
      ['keydown', 'keyup'].forEach(function (kind) {
        target.dispatchEvent(new KeyboardEvent(kind, {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true}));
      });
    };
    esc(document.activeElement || document.body);
    if (document.activeElement !== document.body) esc(document.body);
    setTimeout(function () {
      var sheet = sheetOpen();
      if (!sheet) return;
      var close = sheet.querySelector('[aria-label*="close" i], [aria-label*="dismiss" i], [data-dismiss]');
      if (close) close.click();
      setTimeout(function () { if (sheetOpen()) history.back(); }, 300);
    }, 300);
    return true;
  };

  function slideMs(cfg) {
    return Math.max(1, Number(cfg.seconds) || 10) * 1000;
  }

  /** The crossfade: at most 1.2 s, and under half a slide, so fast slideshows still show each one. */
  function fadeMs(cfg) {
    return Math.min(FADE_MS, Math.round(slideMs(cfg) * 0.4));
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
    styleBubbles(cfg.bubbles);
    shortVoiceBars();
    makeRoomForPills();
    showIdeasButtons();
    tellSheet();
    if (key !== state.cfg) {
      state.cfg = key;
      restart();
    }
    var kin = cfg.on ? kinId() : null;
    if (kin !== state.kin) {
      state.kin = kin;
      state.shown = null;
      state.items = null;
      drop();
      restart();
    }
    if (!kin) return;
    if (Date.now() - state.gatheredAt >= GATHER_EVERY_MS) {
      state.gatheredAt = Date.now();
      gather(kin);
    }
    var list = lists[kin];
    if (!list || (!list.loading && Date.now() - list.at > REFRESH_MS)) load(kin, cfg);
    var items = itemsFor(kin, cfg);
    if (items !== state.items) {
      // What's on offer changed (new selfies, other settings): go round again from the start.
      var first = !state.items;
      state.items = items;
      if (!first) restart();
      tell(kin, cfg);
    }
    if (!items.length) return;
    if (!ensureLayers()) return;
    matchKindroid();
    darkenForMenu(state.peeked);
    if (state.peeked) return;  // no new pictures while the menu covers them
    if (!state.busy && Date.now() >= state.nextAt) {
      var item = nextItem(items, cfg);
      if (item) show(item, cfg, kin);
    }
  }

  window.__kpSlidesTest = {md5: md5, openEnc: openEnc, shows: shows};
  if (MODE === 'gather') {
    gatherAll();
    return;
  }
  setInterval(tick, TICK_MS);
  tick();
})();
