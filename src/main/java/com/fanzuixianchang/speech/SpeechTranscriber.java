package com.fanzuixianchang.speech;

import java.util.Map;

/** 语音转写边界；控制器只依赖这个接口，测试不需要真的调用 DeepSeek。 */
public interface SpeechTranscriber {
    Map<String,Object> recognize(SpeechAudio audio);
}
