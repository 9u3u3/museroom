/*
 * Keeping ad breaks out of a listening room.
 *
 * A room is two people on one song. An ad break only ever happens to one of
 * them, and while it runs there is nothing to stay in step with: the position
 * we are correcting towards belongs to a track that is not playing here. So an
 * ad does not merely annoy, it breaks the feature.
 *
 * The method is the one the YouTube Music desktop clients use, which is uBlock
 * Origin's: the player is never told there are ads to play. Ad slots arrive as
 * three fields on the player response, and both routes they arrive by are
 * closed here.
 *
 * This must run before the page's own scripts, which is why it is registered
 * as a document-start script rather than injected when the page finishes.
 */
(function () {
  if (window.__museroomAdblock) return;
  window.__museroomAdblock = true;

  var FIELDS = ['playerAds', 'adPlacements', 'adSlots'];

  /* Route one: anything parsed from the network. */
  function prune(o) {
    if (!o || typeof o !== 'object') return o;
    for (var i = 0; i < FIELDS.length; i++) {
      delete o[FIELDS[i]];
      if (o.playerResponse) delete o.playerResponse[FIELDS[i]];
      if (o.ytInitialPlayerResponse) delete o.ytInitialPlayerResponse[FIELDS[i]];
    }
    return o;
  }

  JSON.parse = new Proxy(JSON.parse, {
    apply: function () {
      return prune(Reflect.apply.apply(null, arguments));
    },
  });

  Response.prototype.json = new Proxy(Response.prototype.json, {
    apply: function () {
      return Reflect.apply.apply(null, arguments).then(prune);
    },
  });

  /*
   * Route two: baked into the HTML the page was served with, so it is never
   * parsed and the proxies above never see it. These properties are pinned to
   * undefined instead, so whatever the page assigns, the player reads nothing.
   */
  function pin(owner, chain) {
    var dot = chain.indexOf('.');
    if (dot === -1) {
      var existing = Object.getOwnPropertyDescriptor(owner, chain);
      if (existing && existing.configurable === false) return;
      try {
        Object.defineProperty(owner, chain, {
          configurable: false,
          get: function () { return undefined; },
          set: function () {},
        });
      } catch (e) {}
      return;
    }

    var head = chain.slice(0, dot);
    var rest = chain.slice(dot + 1);
    var value = owner[head];
    if (value && typeof value === 'object') {
      pin(value, rest);
      return;
    }

    // The parent does not exist yet. Wait for the page to create it, then pin
    // the child the moment it does.
    var held;
    var descriptor = Object.getOwnPropertyDescriptor(owner, head);
    if (descriptor && descriptor.configurable === false) return;
    try {
      Object.defineProperty(owner, head, {
        configurable: true,
        get: function () { return held; },
        set: function (v) {
          held = v;
          if (v && typeof v === 'object') pin(v, rest);
        },
      });
    } catch (e) {}
  }

  pin(window, 'playerResponse.adPlacements');
  pin(window, 'ytInitialPlayerResponse.playerAds');
  pin(window, 'ytInitialPlayerResponse.adPlacements');
  pin(window, 'ytInitialPlayerResponse.adSlots');
})();
