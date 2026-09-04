/*
 * Museroom's hand on the YouTube Music web player.
 *
 * The listener never sees this page. Museroom keeps it in a one-pixel WebView
 * and drives it from Kotlin, so joining somebody's room looks like Museroom
 * playing music rather than like being thrown into another app.
 *
 * Everything here talks to #movie_player, which is the same object the desktop
 * clients drive. It is not a public API and it can change, so every call is
 * wrapped: a missing method degrades to "not ready" rather than to a crash.
 */
(function () {
  if (window.__museroom) return;

  var bridge = window.MuseroomBridge;
  var wanted = '';
  var api = null;
  var lastError = '';

  /** The player, or null while the page is still assembling itself. */
  function player() {
    if (api && typeof api.getCurrentTime === 'function') return api;
    api = document.querySelector('#movie_player');
    if (api && typeof api.getCurrentTime === 'function') return api;
    api = null;
    return null;
  }

  /*
   * Ads. The player keeps its own flag in the queue's store, and reading it is
   * cheap, but it is undocumented and may vanish. The identity check in Kotlin
   * (are we playing the id we asked for?) is the one that has to be right, so
   * this is only used to explain a stall to the listener, never to decide.
   */
  function adPlaying() {
    try {
      var queue = document.querySelector('#queue');
      var store = queue && queue.queue && queue.queue.store && queue.queue.store.store;
      if (store) return !!store.getState().player.adPlaying;
    } catch (e) {}
    return false;
  }

  function report() {
    var p = player();
    if (!p) {
      try { bridge.state(JSON.stringify({ ready: false, wanted: wanted })); } catch (e) {}
      return;
    }
    var data = {};
    try { data = p.getVideoData() || {}; } catch (e) {}
    var payload = { ready: true, wanted: wanted, ad: adPlaying() };
    /*
     * A one-line account of what the player is actually doing. The room is
     * invisible, so when it does not play there is otherwise nothing to look
     * at, and "behind by 68 seconds" is what a stopped player looks like from
     * the outside.
     */
    try {
      var v = document.querySelector('video');
      payload.detail =
        'state=' + (function () { try { return p.getPlayerState(); } catch (e) { return '?'; } })() +
        ' video=' + (v ? (v.paused ? 'paused' : 'running') : 'none') +
        ' ready=' + (v ? v.readyState : '-') +
        ' muted=' + (function () { try { return p.isMuted() ? 1 : 0; } catch (e) { return '?'; } })() +
        ' vol=' + (function () { try { return p.getVolume(); } catch (e) { return '?'; } })() +
        (lastError ? ' err=' + lastError : '');
    } catch (e) {
      payload.detail = 'unreadable';
    }
    try { payload.videoId = data.video_id || data.videoId || ''; } catch (e) { payload.videoId = ''; }

    /*
     * Never play something nobody asked for.
     *
     * A track ending hands the page back its own queue, and it starts the
     * next thing it fancies — which, after a Drake song, is another Drake
     * song. Kotlin notices and corrects, but not for a second or two, and in
     * those seconds the listener is hearing music the host is not playing and
     * has no way to know it. Silence is the honest answer for that gap, so
     * the page stops itself here rather than waiting to be told.
     *
     * Identity, not timing: whatever the reason the id changed, if it is not
     * the one we asked for then it is not the room's music.
     */
    try {
      if (wanted && payload.videoId && payload.videoId !== wanted) {
        payload.strayed = true;
        var stray = document.querySelector('video');
        if (stray && !stray.paused) { try { stray.pause(); } catch (e) {} }
      }
    } catch (e) {}
    try { payload.title = data.title || ''; } catch (e) { payload.title = ''; }
    try { payload.author = data.author || ''; } catch (e) { payload.author = ''; }
    try { payload.positionMs = Math.round((p.getCurrentTime() || 0) * 1000); } catch (e) { payload.positionMs = 0; }
    try { payload.durationMs = Math.round((p.getDuration() || 0) * 1000); } catch (e) { payload.durationMs = 0; }
    try { payload.state = p.getPlayerState(); } catch (e) { payload.state = -1; }
    try { bridge.state(JSON.stringify(payload)); } catch (e) {}
  }

  window.__museroom = {
    /**
     * Start a track at an offset. One call, because loadVideoById takes the
     * offset itself; seeking separately would play the opening first.
     */
    load: function (id, startSeconds) {
      wanted = id || '';
      lastError = '';
      var p = player();
      if (!p || typeof p.loadVideoById !== 'function') return false;
      try {
        // Nothing else in Museroom sets the volume, so a player left muted by
        // the page is silence with no way for anybody to notice or fix it.
        try { p.unMute(); } catch (e) {}
        try { p.setVolume(100); } catch (e) {}
        p.loadVideoById(id, startSeconds || 0);
        return true;
      } catch (e) {
        lastError = String(e && e.name ? e.name : e);
        return false;
      }
    },

    seek: function (seconds) {
      var p = player();
      if (!p) return false;
      try { p.seekTo(seconds); return true; } catch (e) { return false; }
    },

    /*
     * Resume, not restart. The player's own playVideo() begins the track from
     * the beginning, which for a listener who is mid-song is not resuming at
     * all, so the media element is asked first and the player is the fallback.
     */
    play: function () {
      var p = player();
      if (p) {
        try { p.unMute(); } catch (e) {}
        try { p.setVolume(100); } catch (e) {}
      }
      var v = document.querySelector('video');
      if (v) {
        try {
          var started = v.play();
          // play() rejects rather than throwing, and the rejection carries the
          // only explanation there is for a player that will not start.
          if (started && started.catch) {
            started
              .then(function () { lastError = ''; })
              .catch(function (e) { lastError = String(e && e.name ? e.name : e); });
          }
          return true;
        } catch (e) {
          lastError = String(e && e.name ? e.name : e);
        }
      }
      if (!p) return false;
      try { p.playVideo(); return true; } catch (e) { lastError = String(e); return false; }
    },

    pause: function () {
      var v = document.querySelector('video');
      if (v) { try { v.pause(); return true; } catch (e) {} }
      var p = player();
      if (!p) return false;
      try { p.pauseVideo(); return true; } catch (e) { return false; }
    },

    leave: function () {
      wanted = '';
      var v = document.querySelector('video');
      if (v) { try { v.pause(); } catch (e) {} }
    },

    /*
     * What this page is meant to be playing, said again after a navigation.
     *
     * The first track of a room arrives as a page load rather than as a call
     * to the player, and a page load starts this script over with nothing
     * wanted — which would leave the guard above switched off for exactly the
     * song most likely to end and hand the queue back. So Kotlin says it once
     * more when the page comes up.
     */
    expect: function (id) {
      wanted = id || '';
    },

    poll: report,

    /*
     * Turning a title into a video id, using the search this page already has.
     *
     * Worth the awkwardness: it runs as whoever is signed in here, so it
     * returns music rather than whatever a keyless web search would, and it
     * spends no API quota. The reply comes back on a token because a WebView
     * evaluation cannot wait for a fetch.
     */
    search: function (token, query, params) {
      function answer(id) {
        try { bridge.resolved(token, id || ''); } catch (e) {}
      }
      function firstId(payload) {
        var m = /"videoId":"([A-Za-z0-9_-]{11})"/.exec(
          typeof payload === 'string' ? payload : JSON.stringify(payload),
        );
        return m ? m[1] : '';
      }

      /*
       * The app's own network layer. It signs the request, carries the client
       * context and uses whoever is signed in here, so this returns songs and
       * costs no API quota. Nothing is hand-rolled, which is why it is first.
       */
      try {
        var app = document.querySelector('ytmusic-app');
        var box = document.querySelector('ytmusic-search-box');
        if (app && app.networkManager) {
          var body = { query: query };
          if (params) body.params = params;
          if (box && box.getSearchboxStats) body.suggestStats = box.getSearchboxStats();
          app.networkManager
            .fetch('/search', body)
            .then(function (r) { answer(firstId(r)); })
            .catch(function () { plainSearch(); });
          return;
        }
      } catch (e) {}
      plainSearch();

      /* If the app element is not up yet, InnerTube directly. */
      function plainSearch() {
        try {
          var cfg = window.ytcfg;
          var key = cfg.get('INNERTUBE_API_KEY');
          var context = cfg.get('INNERTUBE_CONTEXT');
          if (!key || !context) return answer('');
          var body = { context: context, query: query };
          if (params) body.params = params;
          fetch('/youtubei/v1/search?key=' + key + '&prettyPrint=false', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
          })
            .then(function (r) { return r.text(); })
            .then(function (text) { answer(firstId(text)); })
            .catch(function () { answer(''); });
        } catch (e) {
          answer('');
        }
      }
    },
  };

  /*
   * A track can end on its own and the player will helpfully start the next
   * thing it fancies. Reporting on every change means Kotlin notices that
   * within a tick and pulls it back to whatever the host is playing.
   */
  var attached = false;
  setInterval(function () {
    var p = player();
    if (p && !attached && typeof p.addEventListener === 'function') {
      try {
        p.addEventListener('videodatachange', report);
        p.addEventListener('onStateChange', report);
        attached = true;
      } catch (e) {}
    }
    report();
  }, 1000);

  report();
})();
