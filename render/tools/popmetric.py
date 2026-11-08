# Frame-to-frame pops in a film: per 32x32 block, the luminance change between consecutive frames; a pop is a block
# whose change exceeds 4x the median of its own changes over the 8 surrounding frame pairs and 0.03 (of 1, sRGB).
import sys, glob, numpy as np
from PIL import Image
d = sys.argv[1]
files = sorted(glob.glob(d + '/f*.png'))
B = 32
L = []
for f in files:
    a = np.asarray(Image.open(f).convert('L'), dtype=np.float32) / 255
    h, w = a.shape
    a = a[:h // B * B, :w // B * B].reshape(h // B, B, w // B, B).mean(axis=(1, 3))
    L.append(a)
L = np.stack(L)
D = np.abs(np.diff(L, axis=0))
pops = 0
worst = 0
per = []
for t in range(len(D)):
    lo, hi = max(0, t - 4), min(len(D), t + 5)
    ref = np.concatenate([D[lo:t], D[t + 1:hi]], axis=0)
    med = np.median(ref, axis=0)
    p = (D[t] > 4 * med + 1e-6) & (D[t] > 0.03)
    per.append(int(p.sum()))
    pops += int(p.sum())
    worst = max(worst, float(D[t].max()))
print(f'{d}: {len(files)} frames, {pops} block pops ({sum(1 for x in per if x)} frames with any), largest block change {worst:.3f}')
