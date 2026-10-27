"""VettID notification family: one motif (rising fourth), four moods.

general  -> motif in D, once, soft
message  -> motif in G, brighter, with a tail note
urgent   -> motif in C, twice, firmer, held
ringtone -> motif in D, unfolded into a loopable 8 s phrase over a soft pad

Usage: python3 synth_family.py out_dir
"""
import sys, os
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter

SR = 44100


def t(dur):
    return np.arange(int(SR * dur)) / SR


def note(name):
    names = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}
    return 440.0 * 2 ** ((names[name[:-1]] + (int(name[-1]) - 4) * 12 - 9) / 12)


def lowpass(sig, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2))
    return lfilter(b, a, sig)


def glass(freq, dur, amp=1.0, attack=0.010, decay=0.45, firm=0.0):
    """Soft glassy tone: sine + gentle 2nd/3rd partials, rounded attack.
    firm (0..1) adds a little more edge and a faster attack for the urgent voice."""
    x = t(dur)
    att = attack * (1 - 0.6 * firm)
    env = np.minimum(1.0, x / att) * np.exp(-x / decay)
    # end at silence: a note cut off while it still sounds clicks (raised-cosine release over the last 80 ms)
    rel = min(len(x), int(SR * 0.08))
    env[-rel:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, rel)))
    out = (np.sin(2 * np.pi * freq * x)
           + (0.28 + 0.25 * firm) * np.exp(-x / (decay * 0.6)) * np.sin(2 * np.pi * freq * 2 * x)
           + (0.10 + 0.12 * firm) * np.exp(-x / (decay * 0.4)) * np.sin(2 * np.pi * freq * 3 * x)
           + 0.04 * firm * np.exp(-x / (decay * 0.25)) * np.sin(2 * np.pi * freq * 5 * x))
    # a touch of warmth an octave down, so it isn't thin on a phone speaker
    out += 0.18 * np.exp(-x / decay) * np.sin(2 * np.pi * freq * 0.5 * x)
    return amp * env * out


def pad(freq, dur, amp=0.25):
    """Very soft sustained bed for the ringtone: two detuned sines + fifth, slow swell."""
    x = t(dur)
    env = np.minimum(1.0, x / 1.2)
    out = (np.sin(2 * np.pi * freq * x) + np.sin(2 * np.pi * freq * 1.003 * x)
           + 0.5 * np.sin(2 * np.pi * freq * 1.5 * x) + 0.4 * np.sin(2 * np.pi * freq * 2.0 * x))
    return amp * env * lowpass(out, 900)


def sheen(sig, mix=0.12):
    """Tiny early-reflection sheen (adds polish, no 'room')."""
    taps = ((0.017, 0.5), (0.029, 0.35), (0.043, 0.2))
    wet = np.zeros(len(sig) + int(SR * 0.06))
    for d, g in taps:
        i = int(d * SR)
        wet[i:i + len(sig)] += g * sig
    out = np.zeros_like(wet)
    out[: len(sig)] = sig
    return out + mix * wet


def place(canvas, sound, start):
    i = int(start * SR)
    end = min(len(canvas), i + len(sound))
    part = sound[: end - i].copy()
    if len(part) < len(sound):  # cut by the canvas: release it there instead (a cut that still sounds clicks)
        rel = min(len(part), int(SR * 0.08))
        part[-rel:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, rel)))
    canvas[i:end] += part
    return canvas


def finish(sig, fade_ms=80, peak_db=-1.0, fade_in_ms=0):
    sig = sig.astype(np.float64)
    n = int(SR * fade_ms / 1000)
    if n:
        sig[-n:] *= np.linspace(1, 0, n)
    n = int(SR * fade_in_ms / 1000)
    if n:
        sig[:n] *= np.linspace(0, 1, n)
    sig /= np.max(np.abs(sig)) + 1e-9
    sig *= 10 ** (peak_db / 20)
    return (sig * 32767).astype(np.int16)


def motif(root, start, canvas, amp=1.0, firm=0.0, gap=0.17, decay=(0.35, 0.6)):
    """The VettID cell: root -> up a perfect fourth. Short-long."""
    canvas = place(canvas, glass(root, 0.8, amp * 0.85, decay=decay[0], firm=firm), start)
    canvas = place(canvas, glass(root * 4 / 3, 1.2, amp, decay=decay[1], firm=firm), start + gap)
    return canvas


out = {}

# 1. GENERAL — D4 -> G4, once, soft. ~1.2 s
c = np.zeros(int(SR * 1.3))
c = motif(note("D4"), 0.0, c, amp=0.9)
out["vettid_general"] = (sheen(c), -3.0)

# 2. MESSAGE — G4 -> C5, then a light tail on D5: "something arrived". ~1.5 s
c = np.zeros(int(SR * 1.6))
c = motif(note("G4"), 0.0, c, amp=1.0, gap=0.15)
c = place(c, glass(note("D5"), 1.0, 0.55, decay=0.5), 0.40)
out["vettid_message"] = (sheen(c), -2.0)

# 3. URGENT — C5 -> F5 twice, firmer, second time held. ~2 s
c = np.zeros(int(SR * 2.1))
c = motif(note("C5"), 0.00, c, amp=0.95, firm=0.6, gap=0.14, decay=(0.25, 0.3))
c = motif(note("C5"), 0.46, c, amp=1.0, firm=0.6, gap=0.14, decay=(0.25, 0.9))
out["vettid_urgent"] = (sheen(c), -1.0)

# 4. RINGTONE — D major, 8 s loop. Motif D4->G4 answered by A4->D5, over a soft D pad.
dur = 8.0
c = np.zeros(int(SR * dur))
c = place(c, pad(note("D3"), dur, amp=0.22), 0.0)
phrase = [  # (note, start, amp)
    ("D4", 0.00, 0.80), ("G4", 0.22, 0.95),
    ("A4", 1.00, 0.70), ("D5", 1.22, 0.85),
    ("G4", 2.30, 0.60), ("A4", 2.52, 0.70),
    ("F#4", 3.40, 0.55), ("G4", 3.62, 0.65),
]
for n, s, a in phrase:
    c = place(c, glass(note(n), 1.4, a, decay=0.7), s)
for n, s, a in phrase:  # second half: same phrase, slightly softer, so the loop breathes
    c = place(c, glass(note(n), 1.4, a * 0.85, decay=0.7), s + 4.0)
# make the loop seamless: crossfade the last 150 ms into the first 150 ms
xf = int(SR * 0.15)
ramp = np.linspace(0, 1, xf)
head = c[:xf].copy()
c[-xf:] = c[-xf:] * (1 - ramp) + head * ramp
c[:xf] *= ramp
out["vettid_ringtone"] = (c, -3.0)

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
for name, (sig, peak) in out.items():
    # the ringtone loops: both ends meet at silence, briefly, so the loop point does not click
    fade, fade_in = (40, 5) if name == "vettid_ringtone" else (80, 0)
    wavfile.write(os.path.join(out_dir, f"{name}.wav"), SR, finish(sig, fade_ms=fade, peak_db=peak, fade_in_ms=fade_in))
    print(name, f"{len(sig)/SR:.2f}s  peak {peak} dBFS")
