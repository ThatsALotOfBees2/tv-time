// When each word is heard in intro-sound.mp3, in seconds. The startup intro pops the
// "IT'S TV TIME!" sign in on these moments. Measured from the clip's loudness;
// tweak if you swap the clip (or delete this file to have it estimated automatically).
window.TVTIME_INTRO_TIMING = {
  its: 0.05,   // "IIIIT'S"
  t: 1.25,     // "T"
  v: 2.05,     // "V"
  time: 2.85,  // "TIIIIME"
  bang: 4.3    // loudest moment: "!", confetti and cymbal
};
