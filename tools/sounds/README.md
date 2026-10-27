# VettID notification sounds

The sources of the app's notification sounds (owner decision 2026-10-23). One motif, a rising perfect fourth in the
same glassy tone, in four moods:

| Sound | Length | Used for (Android channel) |
|---|---|---|
| `vettid_general` | 1.4 s, D, soft | Vault updates |
| `vettid_message` | 1.7 s, G, with a tail note | Messages, Missed calls |
| `vettid_urgent` | 2.2 s, C, twice, firmer | Requests, Security, Daily check |
| `vettid_ringtone` | 8.0 s, D, seamless loop | Calls (ringing only) |

Activity and the connection service's notification stay silent.

- `*.wav`: the masters, 44.1 kHz 16-bit mono PCM, the source of truth. Re-export from them; never edit an export.
- `synth_family.py`: generates the masters deterministically: `python3 synth_family.py <out_dir>` (needs numpy and
  scipy). Its output was verified byte-identical to these masters with numpy 2.5.4 and scipy 1.18.1.
- Every note ends at silence (an 80 ms release, also where a sound's length cuts a note), and the ringtone's two
  ends meet at silence (5 ms in, 40 ms out): a note or a loop cut while it still sounds clicks (found in the
  two-phone calls test, 2026-10-27).
- The app's exports are `core/notify/src/main/res/raw/vettid_*.ogg` (Vorbis, ~96 kbit/s). After a change to a master:

      ffmpeg -i vettid_general.wav -c:a libvorbis -b:a 96k vettid_general.ogg

  (the same for each sound), then copy the `.ogg` into `core/notify/src/main/res/raw/`. A channel's sound is fixed
  when Android first creates the channel: a changed sound on an existing channel needs a new channel id
  (`Channels.kt`; the old id goes in `Channels.RETIRED`).

## Licence

The sounds, the masters and `synth_family.py` are part of this repository and under its licence, the GNU Affero
General Public License v3.0 (`LICENSE` at the repository root).
