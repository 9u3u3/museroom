/*
 * The page's guard against playing a song nobody asked for.
 *
 * This is the one decision in Museroom that lives in JavaScript rather than
 * in Kotlin, inside a page nobody can see, so it is the one piece most easily
 * shipped on reasoning alone. It is not: room.js is loaded here against a
 * fake player and made to stray.
 *
 * Run from the repository root:  node app/src/test/js/stray.mjs
 */
import fs from 'fs';

const src = fs.readFileSync('app/src/main/assets/room.js', 'utf8');

let paused = false;
let currentId = 'aaaaaaaaaaa';
const fakeVideo = {
  paused: false, readyState: 4, currentTime: 12.345, duration: 220, playbackRate: 1,
  isConnected: true, preservesPitch: false,
  pause() { this.paused = true; paused = true; },
  play() { this.paused = false; },
  addEventListener() {},
};
let volume = 100;
const fakePlayer = {
  getCurrentTime: () => 10,
  getDuration: () => 200,
  getPlayerState: () => 1,
  getVideoData: () => ({ video_id: currentId, title: 't', author: 'a' }),
  isMuted: () => false,
  getVolume: () => volume,
  loadVideoById(id) { currentId = id; fakeVideo.paused = false; paused = false; },
  // The player's own word for fetching something without playing it, which is
  // how every phone in a room holds the next song until the agreed moment.
  cueVideoById(id) { currentId = id; fakeVideo.paused = true; },
  seekTo() {},
  playVideo() { fakeVideo.paused = false; },
  pauseVideo() { fakeVideo.paused = true; },
  unMute() {},
  setVolume(v) { volume = v; },
  addEventListener() {},
};

const reports = [];
global.window = {};
global.document = {
  querySelector(sel) {
    if (sel === '#movie_player') return fakePlayer;
    if (sel === 'video') return fakeVideo;
    return null;
  },
};
global.setInterval = () => 0;
global.fetch = () => Promise.reject(new Error('no network'));
window.MuseroomBridge = { state: (json) => reports.push(JSON.parse(json)), resolved: () => {} };

new Function(src)();
const room = window.__museroom;

function last() { return reports[reports.length - 1]; }
let pass = 0, fail = 0;
function check(name, got, want) {
  if (got === want) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log(`  FAIL ${name}: got ${got}, wanted ${want}`); }
}

room.load('aaaaaaaaaaa', 0);
room.poll();
check('playing what we asked for is not strayed', !!last().strayed, false);
check('and is left playing', fakeVideo.paused, false);

// The track ends and the page starts something of its own choosing.
currentId = 'zzzzzzzzzzz';
fakeVideo.paused = false;
room.poll();
check('a different id is reported as strayed', !!last().strayed, true);
check('and the page stops itself', fakeVideo.paused, true);
check('without waiting to be told', paused, true);

// Back on the right track.
room.load('zzzzzzzzzzz', 0);
room.poll();
check('once asked for, the same id is fine', !!last().strayed, false);
check('and it plays', fakeVideo.paused, false);

// A page navigation starts the script over with nothing wanted. Kotlin says
// it again, and the guard has to come back with it — otherwise it is off for
// exactly the first track of a room.
room.leave();
room.expect('zzzzzzzzzzz');
currentId = 'wwwwwwwwwww';
fakeVideo.paused = false;
room.poll();
check('after a navigation the guard is back', !!last().strayed, true);
check('and a stray is stopped again', fakeVideo.paused, true);

// Nothing asked for yet: silence is not the page's business to enforce.
room.leave();
currentId = 'qqqqqqqqqqq';
fakeVideo.paused = false;
room.poll();
check('with nothing wanted, nothing is stopped', !!last().strayed, false);
check('and the page is left alone', fakeVideo.paused, false);

// --- what it says, and how dearly ------------------------------------------

room.load('aaaaaaaaaaa', 0);
fakeVideo.currentTime = 12.345;
room.poll();
check('the position is the element clock, to the millisecond', last().positionMs, 12345);
check('and the duration comes with it', last().durationMs, 220000);

// The diagnostic line is six calls and a string. Nobody reads it while the
// music plays, and building it on the hot path is what makes a sync loop too
// slow to sync with.
fakePlayer.getPlayerState = () => 1;
room.poll();
check('a playing player says nothing about itself', last().detail, undefined);
fakePlayer.getPlayerState = () => 2;
room.poll();
check('a stopped one explains itself', typeof last().detail, 'string');
fakePlayer.getPlayerState = () => 1;

// --- closing a gap by speed ------------------------------------------------

check('speed can be set', room.rate(1.03), true);
check('and it lands on the element', fakeVideo.playbackRate, 1.03);
check('with the pitch held, or it is not inaudible', fakeVideo.preservesPitch, true);
room.poll();
check('and it is reported back', last().rate, 1.03);

// A correction must never outlive the track it was correcting.
room.load('aaaaaaaaaaa', 0);
check('a new track starts at ordinary speed', fakeVideo.playbackRate, 1);
room.rate(0.96);
room.pause();
check('so does a pause', fakeVideo.playbackRate, 1);
room.rate(1.04);
room.leave();
check('and so does leaving', fakeVideo.playbackRate, 1);

// --- the host is a client of this too --------------------------------------
//
// In together mode nobody's music app is the speaker, the host's included:
// their song comes out of this page exactly as a listener's does. So every
// rule here applies to them, and the one that has to is the guard against
// playing something nobody chose. A host who strays does not merely hear the
// wrong song — the whole room is steered by what their player is doing, so
// they take everybody with them.

room.leave();
volume = 100;
room.cue('hhhhhhhhhhh', 0);
check('cueing takes the sound down', volume, 0);
room.poll();
check('a cued track is what the page is meant to be playing', last().wanted, 'hhhhhhhhhhh');
check('and it is not strayed', !!last().strayed, false);
check('and it is silent until the moment', fakeVideo.paused, true);

// The page reaches the end of a cued track and helps itself to another.
currentId = 'kkkkkkkkkkk';
fakeVideo.paused = false;
room.poll();
check('a host who strays is caught the same way', !!last().strayed, true);
check('and stopped the same way', fakeVideo.paused, true);

room.cue('hhhhhhhhhhh', 0);
room.begin(0);
check('beginning gives the sound back', volume, 100);
check('and lets the track go', fakeVideo.paused, false);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
