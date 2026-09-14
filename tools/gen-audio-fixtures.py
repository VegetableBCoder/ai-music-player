#!/usr/bin/env python3
"""合成音频测试夹具（Phase 3b）。

为什么要自己合成：本机没有 ffmpeg / sox / lame / flac，而 `MmrMetadataReader` 的真实读取
路径（`MediaMetadataRetriever` 能不能解析出我们给的文件）**必须在真机上验证**，
不能只靠假 retriever 测逻辑。用外部工具还有一个问题：工具版本不同，生成出的字节可能不同，
夹具就不再确定。

生成的东西是**最小合法**的：
  · ID3v2.3 头 + 文本帧（TIT2/TPE1/TALB/TPE2/TYER），文本帧按需要选 ISO-8859-1 或 UTF-16
  · 可选 APIC 帧（1×1 PNG），用来验证「内嵌封面存在性」
  · 38 个 MPEG1 Layer III 静音帧（128kbps/44.1kHz，每帧 417 字节）≈ 0.99 秒
    全零主数据在 Layer III 里就是静音，是合法码流

时长刻意做成约 1 秒：断言 `durationMs > 0` 就够了，不需要更大的文件。

用法：  python3 tools/gen-audio-fixtures.py
输出：  core/storage/src/androidTest/assets/*.mp3
"""

from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "core/storage/src/androidTest/assets"

# 1×1 透明 PNG（67 字节，结构固定）
PNG_1X1 = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082"
)

FRAME_HEADER = bytes([0xFF, 0xFB, 0x90, 0x00])  # MPEG1 Layer III, 128kbps, 44.1kHz, 无 CRC
FRAME_LEN = 144 * 128_000 // 44_100  # = 417
FRAME_COUNT = 38  # 44.1kHz / 1152 采样每帧 ≈ 38.28 帧/秒


def syncsafe(n: int) -> bytes:
    """ID3v2 的长度字段：每字节只用 7 位。"""
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def text_frame(frame_id: str, value: str, utf16: bool) -> bytes:
    body = value.encode("utf-16") if utf16 else value.encode("iso-8859-1")
    payload = bytes([0x01 if utf16 else 0x00]) + body
    return frame_id.encode("ascii") + len(payload).to_bytes(4, "big") + b"\x00\x00" + payload


def apic_frame(mime: str = "image/png") -> bytes:
    payload = (
        bytes([0x00])                # 编码：ISO-8859-1
        + mime.encode("ascii") + b"\x00"
        + bytes([0x03])              # 图片类型：Cover (front)
        + b"\x00"                    # 描述：空
        + PNG_1X1
    )
    return b"APIC" + len(payload).to_bytes(4, "big") + b"\x00\x00" + payload


def build_mp3(tags: dict[str, str], utf16: bool, with_cover: bool) -> bytes:
    frames = b"".join(text_frame(k, v, utf16) for k, v in tags.items())
    if with_cover:
        frames += apic_frame()

    id3 = b"ID3" + bytes([0x03, 0x00, 0x00]) + syncsafe(len(frames)) + frames

    audio = b"".join(
        FRAME_HEADER + bytes(FRAME_LEN - len(FRAME_HEADER)) for _ in range(FRAME_COUNT)
    )
    return id3 + audio


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # 纯 ASCII 标签：覆盖「能读出 title/artist/album/year/时长」这条主路径
    ascii_mp3 = build_mp3(
        {
            "TIT2": "Sunny Day",
            "TPE1": "Jay Chou",
            "TALB": "Ye Hui Mei",
            "TPE2": "Jay Chou",
            "TYER": "2003",
        },
        utf16=False,
        with_cover=False,
    )
    (OUT_DIR / "tagged.mp3").write_bytes(ascii_mp3)

    # 中文 + UTF-16 + 内嵌封面：覆盖编码与 hasEmbeddedPicture
    cover_mp3 = build_mp3(
        {
            "TIT2": "晴天",
            "TPE1": "周杰伦",
            "TALB": "叶惠美",
            "TYER": "2003",
        },
        utf16=True,
        with_cover=True,
    )
    (OUT_DIR / "tagged-cover.mp3").write_bytes(cover_mp3)

    for name in ("tagged.mp3", "tagged-cover.mp3"):
        path = OUT_DIR / name
        print(f"{name}  {path.stat().st_size} 字节")


if __name__ == "__main__":
    main()
