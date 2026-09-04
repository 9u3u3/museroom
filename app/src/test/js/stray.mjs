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
const fakeVideo = { paused: false, pause() { this.paused = true; paused = true; }, play() {}, readyState: 4 };
const fakePlayer = {
  getCurrentTime: () => 10,
  getDuration: () => 200,
  getPlayerState: () => 1,
  getVideoData: () => ({ video_id: currentId, title: 't', author: 'a' }),
  isMuted: () => false,
  getVolume: () => 100,
  loadVideoById(id) { currentId = id; fakeVideo.paused = false; paused = false; },
  seekTo() {}, playVideo() {}, pauseVideo() {}, unMute() {}, setVolume() {},
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

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
