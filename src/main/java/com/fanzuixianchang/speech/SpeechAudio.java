package com.fanzuixianchang.speech;

/**
 * 小程序录音的原始字节。
 *
 * <p>微信云托管 callContainer 对文本请求只有 100 KiB，对二进制请求有 20 MiB；
 * 录音走二进制而不是 base64 JSON。音频只作为一次请求的输入，服务端不落盘。
 */
public record SpeechAudio(byte[] bytes,String mimeType,Integer durationMs) {}
