Optional sound effects for the TV Time startup intro.

Put a file here with one of these names (.mp3, .ogg or .wav) and it plays instead of the
built-in chiptune sound:

  power    the TV switching on
  channel  the "CH 03" channel number appearing
  land     each letter landing
  bang     the "!" (flash and star burst)
  sparkle  stars twinkling afterwards
  cheer    the crowd after the voice line
  jingle   the fanfare, only when there is no voice line

Then rebuild: python3 pi/build-extension.py (Pi), or commit and let GitHub build the APK.
