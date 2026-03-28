#!/usr/bin/env python3
"""
Generate Caro BLE app icons for all Android mipmap densities.
Icon design: dark navy background, 3x3 grid lines, X (coral) and O (sky blue) pieces.
"""
import os
import struct
import zlib

BASE = os.path.join(os.path.dirname(__file__), '..', 'android', 'app', 'src', 'main', 'res')

SIZES = [
    ('mipmap-mdpi',    48),
    ('mipmap-hdpi',    72),
    ('mipmap-xhdpi',   96),
    ('mipmap-xxhdpi',  144),
    ('mipmap-xxxhdpi', 192),
]

# Palette (matches app theme)
BG       = (26,  26,  46 )   # #1a1a2e
GRID     = (60,  60,  100)   # grid lines
X_COL    = (248, 113, 113)   # coral  #f87171
O_COL    = (96,  165, 250)   # sky    #60a5fa


def make_png(width, height, pixels):
    """Encode a list-of-rows-of-(r,g,b) tuples as a valid PNG."""
    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', crc)

    sig  = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))

    # Use bytearray for O(n) assembly instead of O(n²) bytes concatenation
    raw = bytearray()
    for row in pixels:
        raw += b'\x00'
        for r, g, b in row:
            raw += bytes([r, g, b])

    idat = chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    iend = chunk(b'IEND', b'')
    return sig + ihdr + idat + iend


def draw_icon(size):
    pixels = [[BG] * size for _ in range(size)]

    pad   = max(4, size // 8)
    inner = size - 2 * pad
    lw    = max(1, size // 48)   # grid line width

    # ── 3x3 grid lines ──
    for i in range(1, 3):
        pos = pad + (inner * i) // 3
        for t in range(lw):
            for y in range(pad, size - pad):
                if 0 <= pos + t < size:
                    pixels[y][pos + t] = GRID
            for x in range(pad, size - pad):
                if 0 <= pos + t < size:
                    pixels[pos + t][x] = GRID

    cell = inner // 3

    def draw_x(row_off, col_off, cs, color):
        t      = max(1, cs // 7)
        margin = cs // 5
        span   = cs - 2 * margin
        for i in range(span):
            for th in range(-t, t + 1):
                # top-left → bottom-right stroke
                ry = row_off + margin + i + th
                rx = col_off + margin + i
                if 0 <= ry < size and 0 <= rx < size:
                    pixels[ry][rx] = color
                # top-right → bottom-left stroke
                ry = row_off + margin + i + th
                rx = col_off + cs - margin - i - 1
                if 0 <= ry < size and 0 <= rx < size:
                    pixels[ry][rx] = color

    def draw_o(row_off, col_off, cs, color):
        cy = row_off + cs // 2
        cx = col_off + cs // 2
        r  = cs // 3
        t  = max(1, cs // 10)
        for y in range(row_off, row_off + cs):
            for x in range(col_off, col_off + cs):
                dist = ((y - cy) ** 2 + (x - cx) ** 2) ** 0.5
                if r - t <= dist <= r + t:
                    if 0 <= y < size and 0 <= x < size:
                        pixels[y][x] = color

    # Place pieces on diagonal: X top-left, O center, X bottom-right, O top-right
    draw_x(pad,                  pad,                  cell, X_COL)
    draw_o(pad + cell,           pad + cell,           cell, O_COL)
    draw_x(pad + 2 * cell,       pad + 2 * cell,       cell, X_COL)
    draw_o(pad,                  pad + 2 * cell,       cell, O_COL)

    return pixels


def main():
    for density, sz in SIZES:
        pixels = draw_icon(sz)
        data   = make_png(sz, sz, pixels)
        for fname in ('ic_launcher.png', 'ic_launcher_round.png'):
            path = os.path.normpath(os.path.join(BASE, density, fname))
            with open(path, 'wb') as f:
                f.write(data)
            print(f'  wrote {path}')

    # ── iOS icons ──────────────────────────────────────────────────────────
    IOS_DIR = os.path.normpath(os.path.join(
        os.path.dirname(__file__), '..', 'ios', 'ReactNativeRoom',
        'Images.xcassets', 'AppIcon.appiconset'
    ))
    IOS_SIZES = [
        ('icon-40.png',   40),
        ('icon-60.png',   60),
        ('icon-58.png',   58),
        ('icon-87.png',   87),
        ('icon-80.png',   80),
        ('icon-120a.png', 120),
        ('icon-120b.png', 120),
        ('icon-180.png',  180),
        ('icon-1024.png', 1024),
    ]
    for fname, sz in IOS_SIZES:
        data = make_png(sz, sz, draw_icon(sz))
        path = os.path.join(IOS_DIR, fname)
        with open(path, 'wb') as f:
            f.write(data)
        print(f'  wrote {fname} ({sz}x{sz})')

    print('Done.')


if __name__ == '__main__':
    main()
