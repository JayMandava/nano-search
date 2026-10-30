#!/usr/bin/env python3
"""Original deterministic V8-inspired sound synthesis; contains no sampled recording."""
import argparse
import math
from pathlib import Path
import random
import struct
import wave

RATE = 48000
DURATION = 2.65
SEED = 8419


def synthesize():
    rng = random.Random(SEED)
    knots = [(0, 1800), (.2, 2100), (.5, 3700), (1.2, 8300), (1.42, 8600), (1.7, 5800), (2.1, 2700), (2.65, 1500)]
    phase = 0.0
    rumble = 0.0
    hiss = 0.0
    values = []
    for i in range(round(RATE * DURATION)):
        t = i / RATE
        a, b = next((a, b) for a, b in zip(knots, knots[1:]) if a[0] <= t <= b[0])
        u = (t-a[0])/(b[0]-a[0]); u = u*u*(3-2*u)
        rpm = a[1]+(b[1]-a[1])*u
        phase += 2*math.pi*(rpm/60*4)/RATE
        # Uneven paired exhaust pulses, broad harmonics and filtered turbulent flow.
        tone = sum(math.sin(k*phase + .13*math.sin(phase*.5)) / k**1.13 for k in range(1, 14))
        tone += .4*math.sin(phase*.5)+.13*math.sin(phase*2.03)
        noise = rng.uniform(-1,1)
        rumble += .035*(noise-rumble)
        hiss += .32*(noise-hiss)
        throttle = min(1, rpm/7500)
        body = tone*.36 + rumble*.55 + (hiss-rumble)*(.06+.08*throttle)
        # A soft induction whine grows with the rev; no alarm-like tones or gunshot pops.
        body += .045*throttle*math.sin(phase*3.01)
        attack = min(1, t/.055)
        release = min(1, (DURATION-t)/.32)
        envelope = math.sin(attack*math.pi/2)**2 * math.sin(release*math.pi/2)**2
        values.append(math.tanh(body*1.55)*envelope*(.62+.30*throttle))
    peak = max(abs(v) for v in values)
    return [v*.65/peak for v in values]


def write(path, values, gain=1):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(RATE)
        out.writeframes(b''.join(struct.pack('<h', round(max(-1,min(1,v*gain))*32767)) for v in values))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--preview',type=Path)
    args=parser.parse_args()
    values=synthesize();write(args.output,values)
    if args.preview:write(args.preview,values,.35)
