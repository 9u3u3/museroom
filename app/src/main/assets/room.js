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

  /** YouTube's own numbering, so the hot path can compare without a call. */
  var PLAYING = 1;

  var bridge = window.MuseroomBridge;
  var wanted = '';
  var api = null;
  var media = null;
  var adStore = null;
  var lastError = '';

  /*
   * The player and the media element, held rather than looked up.
   *
   * This runs several times a second now, and a querySelector across a page
   * the size of YouTube Music is not free. Both are re-resolved the moment the
   * one we are holding stops answering, which is what a navigation looks like
   * from in here.
   */
  function player() {
    if (api && typeof api.getCurrentTime === 'function') return api;
    api = document.querySelector('#movie_player');
    if (api && typeof api.getCurrentTime === 'function') return api;
    api = null;
    return null;
  }

  function video() {
    if (media && media.isConnected) return media;
    media = document.querySelector('video');
    return media;
  }

  /*
   * Ads. The player keeps its own flag in the queue's store, and reading it is
   * cheap, but it is undocumented and may vanish. The identity check in Kotlin
   * (are we playing the id we asked for?) is the one that has to be right, so
   * this is only used to explain a stall to the listener, never to decide.
   */
  function adPlaying() {
    try {
      if (!adStore) {
        var queue = document.querySelector('#queue');
        adStore = (queue && queue.queue && queue.queue.store && queue.queue.store.store) || null;
      }
      if (adStore) return !!adStore.getState().player.adPlaying;
    } catch (e) {
      adStore = null;
    }
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
    var state = -1;
    try { state = p.getPlayerState(); } catch (e) {}
    var v = video();

    var payload = { ready: true, wanted: wanted, ad: adPlaying(), state: state };

    /*
     * A one-line account of what the player is actually doing, built only when
     * it is not doing it.
     *
     * Nobody reads this while the music plays; it exists because the room is
     * invisible and "behind by 68 seconds" is what a stopped player looks like
     * from outside. Six calls and a string every quarter of a second, for a
     * line nobody will look at, is the kind of cost that turns a sync loop
     * into the reason it cannot keep up.
     */
    if (state !== PLAYING) {
      try {
        payload.detail =
          'state=' + state +
          ' video=' + (v ? (v.paused ? 'paused' : 'running') : 'none') +
          ' ready=' + (v ? v.readyState : '-') +
          ' muted=' + (function () { try { return p.isMuted() ? 1 : 0; } catch (e) { return '?'; } })() +
          ' vol=' + (function () { try { return p.getVolume(); } catch (e) { return '?'; } })() +
          (lastError ? ' err=' + lastError : '');
      } catch (e) {
        payload.detail = 'unreadable';
      }
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
        var stray = video();
        if (stray && !stray.paused) { try { stray.pause(); } catch (e) {} }
      }
    } catch (e) {}
    try { payload.title = data.title || ''; } catch (e) { payload.title = ''; }
    try { payload.author = data.author || ''; } catch (e) { payload.author = ''; }
    /*
     * The element's own clock, not the player's. It is the thing actually
     * making the sound, it is a plain property read rather than a call across
     * the player's API, and it is not rounded to whole seconds — which is the
     * difference between telling half a second of drift from none.
     */
    payload.positionMs = 0;
    payload.durationMs = 0;
    try {
      if (v && !isNaN(v.currentTime)) payload.positionMs = Math.round(v.currentTime * 1000);
      else payload.positionMs = Math.round((p.getCurrentTime() || 0) * 1000);
      if (v && isFinite(v.duration)) payload.durationMs = Math.round(v.duration * 1000);
      else payload.durationMs = Math.round((p.getDuration() || 0) * 1000);
      payload.rate = v ? v.playbackRate : 1;
    } catch (e) {}
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
        try {
          var v = video();
          if (v) v.playbackRate = 1;
        } catch (e) {}
        p.loadVideoById(id, startSeconds || 0);
        return true;
      } catch (e) {
        lastError = String(e && e.name ? e.name : e);
        return false;
      }
    },

    /*
     * Move to a moment, and mean it.
     *
     * The second argument is not optional in practice. Without it the player
     * will not fetch anything it does not already hold, so a seek past the end
     * of the buffer quietly lands short or does not happen at all. Every
     * correction then measures the same gap it just tried to close and tries
     * again, which is heard as a listener scrubbing back and forth and never
     * arriving.
     */
    /*
     * Fetch a track and hold it, silent.
     *
     * Everybody in a room begins a song at the same moment, worked out from
     * the host's own position rather than sent to anybody, so the fetching has
     * to be finished before that moment and must not make a sound when it
     * lands. cueVideoById is the player's own word for this; where it is
     * missing, loading and stopping at once gets to the same place, and the
     * volume is taken down across the join so nothing escapes if the stop
     * lands a frame late.
     */
    cue: function (id, startSeconds) {
      wanted = id || '';
      lastError = '';
      var p = player();
      if (!p) return false;
      try {
        try {
          var v = video();
          if (v) v.playbackRate = 1;
        } catch (e) {}
        try { p.setVolume(0); } catch (e) {}
        if (typeof p.cueVideoById === 'function') {
          p.cueVideoById(id, startSeconds || 0);
        } else if (typeof p.loadVideoById === 'function') {
          p.loadVideoById(id, startSeconds || 0);
        } else {
          return false;
        }
        try { p.pauseVideo(); } catch (e) {}
        return true;
      } catch (e) {
        lastError = String(e && e.name ? e.name : e);
        return false;
      }
    },

    /* The shared moment: put where everybody else is, then let go. */
    begin: function (seconds) {
      var p = player();
      if (!p) return false;
      try {
        try { p.seekTo(seconds, true); } catch (e) {}
        try { p.unMute(); } catch (e) {}
        try { p.setVolume(100); } catch (e) {}
        var v = video();
        if (v) {
          var started = v.play();
          if (started && started.catch) {
            started
              .then(function () { lastError = ''; })
              .catch(function (e) { lastError = String(e && e.name ? e.name : e); });
          }
        } else if (typeof p.playVideo === 'function') {
          p.playVideo();
        }
        return true;
      } catch (e) {
        lastError = String(e && e.name ? e.name : e);
        return false;
      }
    },

    seek: function (seconds) {
      var p = player();
      if (!p) return false;
      try { p.seekTo(seconds, true); return true; } catch (e) { return false; }
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
      var v = video();
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
      var v = video();
      if (v) {
        // A rate left over from a correction would still be there on the way
        // back in, quietly running the next track fast.
        try { v.playbackRate = 1; } catch (e) {}
        try { v.pause(); return true; } catch (e) {}
      }
      var p = player();
      if (!p) return false;
      try { p.pauseVideo(); return true; } catch (e) { return false; }
    },

    leave: function () {
      wanted = '';
      var v = video();
      if (v) {
        try { v.playbackRate = 1; } catch (e) {}
        try { v.pause(); } catch (e) {}
      }
    },

    /*
     * Closing a small gap by speed rather than by jumping.
     *
     * A seek is audible: the music stops, skips and starts again, and doing
     * that every time a joiner drifts half a second apart from the host would
     * be worse than the drift. Playing a few per cent fast or slow is not
     * audible at all — the pitch is held — and it closes the gap smoothly and
     * then stays closed. Seeking is left for gaps too large to walk back.
     */
    rate: function (r) {
      var v = video();
      if (!v) return false;
      try {
        // Without this the browser is free to shift the pitch instead, which
        // is exactly what nobody would tolerate in music.
        v.preservesPitch = true;
        v.mozPreservesPitch = true;
        v.webkitPreservesPitch = true;
      } catch (e) {}
      try { v.playbackRate = r; return true; } catch (e) { return false; }
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
   * When to speak.
   *
   * Polling on a timer is the wrong shape for this. The moments that matter —
   * a track ending and the page starting one of its own, the player finally
   * making a sound — are events, and waiting up to a second to notice one is
   * a second of the wrong music or of silence. So the events are listened for
   * and the timer is only a net underneath them.
   *
   * The media element's own timeupdate fires several times a second while
   * audio plays and not at all while it does not, which is exactly the shape
   * of how often anybody needs to hear from this.
   */
  var attached = false;
  var listening = null;

  function attach() {
    var p = player();
    if (p && !attached && typeof p.addEventListener === 'function') {
      try {
        p.addEventListener('videodatachange', report);
        p.addEventListener('onStateChange', report);
        attached = true;
      } catch (e) {}
    }
    var v = video();
    if (v && v !== listening) {
      listening = v;
      try {
        v.addEventListener('timeupdate', report);
        v.addEventListener('play', report);
        v.addEventListener('pause', report);
        v.addEventListener('seeked', report);
        v.addEventListener('ended', report);
        v.addEventListener('ratechange', report);
      } catch (e) {}
    }
  }

  setInterval(function () {
    attach();
    report();
  }, 1000);

  report();
})();
